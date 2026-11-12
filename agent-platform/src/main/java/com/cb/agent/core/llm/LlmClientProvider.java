package com.cb.agent.core.llm;

/**
 * 按需要建出模型客户端。
 *
 * <p>为什么不直接注入一个 {@link LlmClient}：不是每个命令都要调模型（看切片就不用），
 * 而建客户端时要检查接口地址在不在允许的范围里，检查不过会报错。所以等真的要调模型时才建。
 */
public interface LlmClientProvider {

    /**
     * @param model   模型名；null 或空表示用配置里的
     * @param baseUrl 接口地址；null 或空表示用配置里的
     * @return 可以用的客户端
     * @throws IllegalArgumentException 接口地址不在允许的范围里，或者配置不完整。消息里说明了怎么改
     */
    LlmClient get(String model, String baseUrl);
}
