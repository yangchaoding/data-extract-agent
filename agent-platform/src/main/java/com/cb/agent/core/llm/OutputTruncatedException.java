package com.cb.agent.core.llm;

/**
 * 模型的输出因为达到长度上限被截断了，拿到的内容不完整。
 *
 * <p>单独作为一种异常，是因为调用方对它有专门的处理：把这一次的任务对半拆开重做
 * （先少抽几个字段，还不行就少看几个片段），而不是当成失败。
 */
public class OutputTruncatedException extends RuntimeException {

    public OutputTruncatedException(String message) {
        super(message);
    }
}
