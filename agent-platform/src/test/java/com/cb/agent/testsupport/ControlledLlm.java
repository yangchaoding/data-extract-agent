package com.cb.agent.testsupport;

import com.cb.agent.core.llm.LlmCallException;
import com.cb.agent.core.llm.LlmClient;
import com.cb.agent.core.llm.Usage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 假模型外面的一层开关：测试可以让它卡住不返回、让它出错，并且它会统计用量。测任务服务时用。
 *
 * <p>回复还是里面那个假模型给的（一般是 {@link RuleLlm}）。这一层加了三样东西：
 * <ul>
 *   <li>{@link #hold()} 之后，调用会停在那里，直到 {@link #release()}：用来造出"正在处理"这个状态，
 *       测排队、取消、服务停止。被打断时和真的客户端一样抛 {@link LlmCallException}；</li>
 *   <li>{@link #failWith} 之后，每次调用都抛这个异常：用来测模型接口出错；</li>
 *   <li>每次调用记 1 次调用、{@value #PROMPT_TOKENS} 个输入 token、{@value #COMPLETION_TOKENS} 个输出 token。
 *       {@link #metered()} 给出单独计量的视图，和真的客户端一样。</li>
 * </ul>
 */
public final class ControlledLlm implements LlmClient {

    public static final long PROMPT_TOKENS = 100;
    public static final long COMPLETION_TOKENS = 10;

    private final LlmClient inner;
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong entered = new AtomicLong();
    private volatile CountDownLatch gate;
    private volatile RuntimeException failure;

    public ControlledLlm(LlmClient inner) {
        this.inner = inner;
    }

    /** 从现在起，调用停住不返回，直到 {@link #release()}。 */
    public void hold() {
        gate = new CountDownLatch(1);
    }

    /** 放行停住的调用，之后的调用也不再停。 */
    public void release() {
        CountDownLatch current = gate;
        gate = null;
        if (current != null) {
            current.countDown();
        }
    }

    /** 从现在起每次调用都抛这个异常；传 null 恢复正常。 */
    public void failWith(RuntimeException e) {
        failure = e;
    }

    /** 到现在为止有多少次调用进来过（包括还停着的、出错的）。 */
    public long entered() {
        return entered.get();
    }

    @Override
    public String complete(String system, String user) {
        return complete(system, user, null);
    }

    private String complete(String system, String user, AtomicLong viewCalls) {
        entered.incrementAndGet();
        RuntimeException e = failure;
        if (e != null) {
            throw e;
        }
        CountDownLatch current = gate;
        if (current != null) {
            try {
                if (!current.await(30, TimeUnit.SECONDS)) {
                    throw new LlmCallException("测试里的假模型停了 30 秒还没被放行");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new LlmCallException("调用模型时被中断", interrupted);
            }
        }
        String reply = inner.complete(system, user);
        calls.incrementAndGet();
        if (viewCalls != null) {
            viewCalls.incrementAndGet();
        }
        return reply;
    }

    @Override
    public Usage usage() {
        return usage(calls.get());
    }

    private static Usage usage(long calls) {
        return new Usage(calls, calls * PROMPT_TOKENS, calls * COMPLETION_TOKENS);
    }

    @Override
    public LlmClient metered() {
        AtomicLong own = new AtomicLong();
        return new LlmClient() {
            @Override
            public String complete(String system, String user) {
                return ControlledLlm.this.complete(system, user, own);
            }

            @Override
            public Usage usage() {
                return ControlledLlm.usage(own.get());
            }

            @Override
            public LlmClient metered() {
                return ControlledLlm.this.metered();
            }
        };
    }
}
