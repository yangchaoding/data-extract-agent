package com.cb.agent.core.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局的模型并发闸门：同时进行的调用数不超过上限，出错、被打断时名额都还得回来。
 */
class LlmGateTest {

    /** 一个调用进来就停住、等放行的假模型，记着同时进行的调用数最高到过多少。 */
    private static final class Blocking implements LlmClient {
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String complete(String system, String user) {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
            return user;
        }

        @Override
        public Usage usage() {
            return new Usage(7, 70, 7);
        }
    }

    @Test
    void 同时进行的调用数不超过上限_几个客户端共用一个上限() throws InterruptedException {
        LlmGate gate = new LlmGate(2);
        Blocking inner = new Blocking();
        // 两个任务各包一层，用的是同一道闸门
        LlmClient first = gate.limit(inner);
        LlmClient second = gate.limit(inner);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            LlmClient client = i % 2 == 0 ? first : second;
            threads.add(Thread.ofVirtual().start(() -> client.complete("s", "u")));
        }

        await(() -> gate.inFlight() == 2 && gate.waiting() == 4);
        assertEquals(2, gate.limit());
        inner.release.countDown();
        for (Thread thread : threads) {
            thread.join(10_000);
        }

        assertEquals(2, inner.peak.get(), "上限是 2，而且确实并发了");
        assertEquals(0, gate.inFlight());
        assertEquals(0, gate.waiting());
    }

    @Test
    void 调用出错时名额也还回来() {
        LlmGate gate = new LlmGate(1);
        LlmClient failing = gate.limit(new LlmClient() {
            @Override
            public String complete(String system, String user) {
                throw new LlmCallException("连不上");
            }

            @Override
            public Usage usage() {
                return Usage.ZERO;
            }
        });

        assertThrows(LlmCallException.class, () -> failing.complete("s", "u"));
        assertThrows(LlmCallException.class, () -> failing.complete("s", "u"));

        assertEquals(0, gate.inFlight(), "不还的话，出错一次就少一个名额，几次之后整个服务都调不了模型");
    }

    @Test
    void 等名额的时候被打断_抛出调用失败_不占名额() throws InterruptedException {
        LlmGate gate = new LlmGate(1);
        Blocking inner = new Blocking();
        LlmClient client = gate.limit(inner);
        Thread holder = Thread.ofVirtual().start(() -> client.complete("s", "u"));
        await(() -> gate.inFlight() == 1);
        List<Throwable> thrown = new ArrayList<>();
        Thread waiter = Thread.ofPlatform().start(() -> {
            try {
                client.complete("s", "u");
            } catch (LlmCallException e) {
                thrown.add(e);
                if (Thread.currentThread().isInterrupted()) {
                    thrown.add(new IllegalStateException("打断标记还在"));
                }
            }
        });
        await(() -> gate.waiting() == 1);

        waiter.interrupt(); // 任务被取消时就是这样打断的
        waiter.join(10_000);

        assertEquals(2, thrown.size(), "抛出调用失败，并且线程的打断标记留着，外层知道是被打断的");
        assertEquals("等待调用模型时被中断", thrown.get(0).getMessage());
        assertEquals(0, gate.waiting());
        assertEquals(1, gate.inFlight(), "没拿到名额的不该还名额");
        inner.release.countDown();
        holder.join(10_000);
        assertEquals(0, gate.inFlight());
    }

    @Test
    void 用量和单独计量的视图都透传给里面的客户端() {
        LlmGate gate = new LlmGate(3);
        AtomicInteger views = new AtomicInteger();
        LlmClient inner = new LlmClient() {
            @Override
            public String complete(String system, String user) {
                return "ok";
            }

            @Override
            public Usage usage() {
                return new Usage(7, 70, 7);
            }

            @Override
            public LlmClient metered() {
                views.incrementAndGet();
                return this;
            }
        };

        LlmClient limited = gate.limit(inner);
        LlmClient view = limited.metered();

        assertEquals(new Usage(7, 70, 7), limited.usage());
        assertEquals(1, views.get(), "单独计量的视图是里面的客户端给的");
        assertEquals("ok", view.complete("s", "u"));
        assertEquals(0, gate.inFlight());
        // 视图发出的调用同样受这道闸门限制：包在外面的还是它
        assertTrue(view.getClass().getName().contains("LlmGate"), view.getClass().getName());
    }

    @Test
    void 上限要大于零() {
        assertEquals("模型的并发上限要大于 0，现在是 0",
                assertThrows(IllegalArgumentException.class, () -> new LlmGate(0)).getMessage());
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("等了 10 秒还没等到");
            }
            Thread.sleep(5);
        }
    }
}
