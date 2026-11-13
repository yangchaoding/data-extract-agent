package com.cb.agent.core.llm;

/**
 * 大模型的调用接口。
 *
 * <p>抽取、问答、报告，以及以后的 agent 循环，都通过它调用模型，不关心背后是哪家的服务。
 * 换一个模型服务，只要再写一个实现。
 */
public interface LlmClient {

    /**
     * 给定系统提示词和用户消息，返回模型的文字回复。
     *
     * @param system 系统提示词
     * @param user   用户消息
     * @return 模型的回复；模型什么都没说时是空串
     * @throws OutputTruncatedException 模型的输出因为达到长度上限被截断了，拿到的内容不完整
     * @throws LlmCallException         调用失败：网络不通、认证失败、服务报错
     */
    String complete(String system, String user);

    /** 到目前为止的累计用量。调用前后各取一次相减，就是中间这一段的用量。 */
    Usage usage();

    /**
     * 一个单独计量的视图：调用还是发给同一个模型服务，但它的 {@link #usage()} 只统计经由这个视图发出的调用。
     *
     * <p>为什么需要它：用量是"前后各取一次相减"算出来的。几份文档同时用一个客户端时，
     * 这一份相减的结果里会混进另一份的调用。各拿一个视图，各自的用量才分得开。
     *
     * <p>默认返回自己，适合不统计用量的实现（测试里的假模型）。统计用量的实现要覆盖它。
     */
    default LlmClient metered() {
        return this;
    }
}
