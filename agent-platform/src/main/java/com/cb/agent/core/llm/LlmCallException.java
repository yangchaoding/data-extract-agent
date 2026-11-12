package com.cb.agent.core.llm;

/**
 * 调用模型失败：网络不通、认证失败、额度用完、模型名写错、服务报错。
 *
 * <p>消息是写给人看的，说明了是哪个接口、什么原因。
 */
public class LlmCallException extends RuntimeException {

    public LlmCallException(String message) {
        super(message);
    }

    public LlmCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
