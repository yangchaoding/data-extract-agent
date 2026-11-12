package com.cb.agent.config;

import com.cb.agent.core.llm.LlmClientProvider;
import com.cb.agent.infra.llm.ConfiguredLlmClientProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * 模型接口的装配：把 application.yml 里 {@code agent.llm} 下的配置交给客户端的提供者。
 *
 * <p>各项配置的含义和对应的环境变量见 application.yml 里的注释。
 */
@Configuration
public class LlmConfig {

    @Bean
    public LlmClientProvider llmClientProvider(
            @Value("${agent.llm.base-url:}") String baseUrl,
            @Value("${agent.llm.model:}") String model,
            @Value("${agent.llm.api-key:}") String apiKey,
            @Value("${agent.llm.max-tokens:0}") int maxTokens,
            @Value("${agent.llm.timeout-seconds:300}") int timeoutSeconds,
            @Value("${agent.llm.allowed-hosts:}") String allowedHosts,
            @Value("${agent.llm.enable-thinking:}") String enableThinking) {
        // 名单写成逗号隔开的一行，这里拆开并去掉空的
        List<String> hosts = Arrays.stream(allowedHosts.split(","))
                .map(String::strip).filter(h -> !h.isEmpty()).toList();
        return new ConfiguredLlmClientProvider(new ConfiguredLlmClientProvider.Settings(
                baseUrl, model, apiKey, maxTokens > 0 ? maxTokens : null, timeoutSeconds, hosts, enableThinking));
    }
}
