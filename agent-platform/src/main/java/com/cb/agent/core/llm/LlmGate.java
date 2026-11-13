package com.cb.agent.core.llm;

import java.util.concurrent.Semaphore;

/**
 * 全局的模型并发闸门：整个服务同时发给模型服务的调用数不超过一个上限。对应方案第 14 节。
 *
 * <p>为什么要有它：每个抽取任务自己有一个并发上限（默认 4），但服务里可以同时跑好几个任务，
 * 加起来就可能超过模型服务承受得住的量。这道闸门按模型服务的容量设一个总的上限，
 * 所有任务（以后还有 agent 循环）共用。
 *
 * <p>用法是把客户端包一层：{@code gate.limit(client)}。包过的客户端每次调用前先等一个名额。
 */
public final class LlmGate {

    private final int limit;
    private final Semaphore permits;

    /**
     * @param limit 同时最多有几个调用在进行，至少是 1
     */
    public LlmGate(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("模型的并发上限要大于 0，现在是 " + limit);
        }
        this.limit = limit;
        // 公平：先来的先拿到名额，免得一个大任务一直占着，后面的小任务等不到
        this.permits = new Semaphore(limit, true);
    }

    /** 同时最多有几个调用在进行。 */
    public int limit() {
        return limit;
    }

    /** 现在有几个调用正在进行。 */
    public int inFlight() {
        return limit - permits.availablePermits();
    }

    /** 现在有几个调用在等名额。这个数一直不是 0，说明上限设小了，或者模型服务太慢。 */
    public int waiting() {
        return permits.getQueueLength();
    }

    /** 把一个客户端包起来：经由返回的客户端发出的调用受这道闸门限制。 */
    public LlmClient limit(LlmClient client) {
        return new Limited(client);
    }

    private final class Limited implements LlmClient {

        private final LlmClient inner;

        Limited(LlmClient inner) {
            this.inner = inner;
        }

        @Override
        public String complete(String system, String user) {
            try {
                permits.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmCallException("等待调用模型时被中断", e);
            }
            try {
                return inner.complete(system, user);
            } finally {
                permits.release();
            }
        }

        @Override
        public Usage usage() {
            return inner.usage();
        }

        @Override
        public LlmClient metered() {
            return new Limited(inner.metered());
        }
    }
}
