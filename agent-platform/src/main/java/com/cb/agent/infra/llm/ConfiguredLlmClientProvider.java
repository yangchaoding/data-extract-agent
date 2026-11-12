package com.cb.agent.infra.llm;

import com.cb.agent.core.llm.LlmClient;
import com.cb.agent.core.llm.LlmClientProvider;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按配置建出模型客户端，并且只允许连到登记过的地址。
 *
 * <p><b>为什么要限制地址：</b>抽取时文档的全文会发给模型。接口地址可以用环境变量改，
 * 而 {@code OPENAI_BASE_URL} 这个变量很多人为了别的用途早就设过。不加限制的话，
 * 人事、财务材料会在没人注意的情况下被发到外部服务。所以接口的主机必须在允许的名单里，不在就拒绝，并说明怎么加。
 * 本机地址（localhost、127.x）总是允许的。
 *
 * <p>配置的优先级和 Python 版相同：调用时指定的 &gt; {@code EXTRACT_LLM_*} &gt; {@code OPENAI_*} &gt; 默认值。
 * 后三级在 application.yml 里用占位符表达，这里只处理"调用时指定的"这一级。
 */
public final class ConfiguredLlmClientProvider implements LlmClientProvider {

    /**
     * 模型接口的配置。
     *
     * @param baseUrl        接口地址，只填到 {@code /v1}
     * @param model          模型名
     * @param apiKey         密钥
     * @param maxTokens      单次输出的上限；0 或 null 表示用服务的默认值
     * @param timeoutSeconds 单次请求最长等多少秒
     * @param allowedHosts   允许连接的主机名或 IP
     */
    public record Settings(String baseUrl, String model, String apiKey, Integer maxTokens,
                           int timeoutSeconds, List<String> allowedHosts) {
    }

    private final Settings settings;
    /** 同一个接口和模型只建一个客户端：它记着"这个服务不支持哪些参数"，也累计着用量。 */
    private final Map<String, LlmClient> clients = new ConcurrentHashMap<>();

    public ConfiguredLlmClientProvider(Settings settings) {
        this.settings = settings;
    }

    @Override
    public LlmClient get(String model, String baseUrl) {
        String url = blank(baseUrl) ? settings.baseUrl() : baseUrl;
        String name = blank(model) ? settings.model() : model;
        if (blank(url)) {
            throw new IllegalArgumentException("没有配置模型接口的地址：设置 agent.llm.base-url，或者环境变量 EXTRACT_LLM_BASE_URL");
        }
        if (blank(name)) {
            throw new IllegalArgumentException("没有指定模型：用 --model 传入，或者设置环境变量 EXTRACT_LLM_MODEL");
        }
        checkAllowed(url, settings.allowedHosts());
        return clients.computeIfAbsent(url + "\n" + name, k -> new OpenAiCompatClient(
                url, name, blank(settings.apiKey()) ? "EMPTY" : settings.apiKey(),
                settings.maxTokens(), Duration.ofSeconds(settings.timeoutSeconds())));
    }

    /**
     * 检查接口的主机在不在允许的名单里。
     *
     * @throws IllegalArgumentException 不在名单里，或者地址本身不对
     */
    static void checkAllowed(String baseUrl, List<String> allowedHosts) {
        String host;
        try {
            host = URI.create(baseUrl.strip()).getHost();
        } catch (IllegalArgumentException e) {
            host = null;
        }
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("模型接口的地址不对：" + baseUrl + "。应该是 http://主机:端口/v1 这样的形式");
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.startsWith("127.") || h.equals("::1") || h.equals("[::1]")) {
            return;
        }
        for (String allowed : allowedHosts) {
            if (allowed != null && allowed.strip().toLowerCase(Locale.ROOT).equals(h)) {
                return;
            }
        }
        throw new IllegalArgumentException("模型接口 " + baseUrl + " 的主机 " + host + " 不在允许的名单里。"
                + "抽取时文档的内容会发给这个接口，所以只允许发往登记过的地址。"
                + "确认可以发给它之后，把 " + host + " 加进配置 agent.llm.allowed-hosts"
                + "（或者环境变量 EXTRACT_LLM_ALLOWED_HOSTS，多个用逗号隔开）。现在允许的是：" + allowedHosts);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
