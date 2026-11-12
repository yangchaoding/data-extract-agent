package com.cb.agent.infra.llm;

import com.cb.agent.core.llm.LlmCallException;
import com.cb.agent.core.llm.LlmClient;
import com.cb.agent.core.llm.OutputTruncatedException;
import com.cb.agent.core.llm.Usage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容接口的模型客户端。对应 Python 版 {@code llm.py} 的 {@code OpenAICompatLLM}。
 *
 * <p>国内外大多数模型服务，以及 vLLM、Ollama 这类本地部署，都提供这种接口：
 * 往 {@code {接口地址}/chat/completions} 发一个 JSON，拿回模型的回复。
 *
 * <p>三处行为和 Python 版相同：
 * <ul>
 *   <li><b>参数降级重试。</b>有的服务不支持自定义 {@code temperature} 或 {@code response_format}，
 *       会直接返回 400。依次去掉这两个参数重试；哪种组合成功了就记住，之后都用它。</li>
 *   <li><b>识别截断。</b>{@code finish_reason} 是 {@code length} 时抛出 {@link OutputTruncatedException}。</li>
 *   <li><b>统计用量。</b>每次调用累加，供调用方计算一次抽取用了多少。</li>
 * </ul>
 *
 * <p>可以被多个线程同时使用。
 */
public final class OpenAiCompatClient implements LlmClient {

    /** 网络错误和服务端临时性的错误最多重试几次。 */
    private static final int MAX_RETRIES = 3;

    private final String endpoint;
    private final String model;
    private final String apiKey;
    private final Integer maxTokens;
    private final Duration timeout;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    /** 当前用的可选参数。发现服务不支持某个参数之后会被改掉。 */
    private volatile Double temperature;
    private volatile boolean jsonMode;

    private long calls;
    private long promptTokens;
    private long completionTokens;

    /**
     * @param baseUrl   接口地址，只填到 {@code /v1}，这里自动加上 {@code /chat/completions}
     * @param model     模型名
     * @param apiKey    密钥。本地部署的服务一般不校验，随便填一个占位值
     * @param maxTokens 单次输出的上限；null 表示用服务的默认值
     * @param timeout   单次请求最长等多久
     */
    public OpenAiCompatClient(String baseUrl, String model, String apiKey, Integer maxTokens, Duration timeout) {
        this.endpoint = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        this.model = model;
        this.apiKey = apiKey;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
        this.temperature = 0.0;  // 抽取要的是稳定，不要发挥
        this.jsonMode = true;    // 要求模型只输出 JSON
        // 固定用 HTTP/1.1：默认会先试着升级到 HTTP/2，有的本地模型服务处理不了这个升级请求
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String complete(String system, String user) {
        // 依次去掉 temperature 和 response_format 重试。全部失败说明 400 另有原因（比如输入太长），抛出最初的那个错误
        List<Object[]> attempts = new ArrayList<>();
        Double t = temperature;
        boolean j = jsonMode;
        attempts.add(new Object[]{t, j});
        if (t != null) {
            attempts.add(new Object[]{null, j});
        }
        if (j) {
            attempts.add(new Object[]{null, false});
        }
        LlmCallException firstBadRequest = null;
        for (Object[] attempt : attempts) {
            Double temp = (Double) attempt[0];
            boolean jsonOut = (Boolean) attempt[1];
            HttpResponse<String> response = send(body(system, user, temp, jsonOut));
            if (response.statusCode() == 400) {
                if (firstBadRequest == null) {
                    firstBadRequest = new LlmCallException(describe(response));
                }
                continue;
            }
            if (response.statusCode() / 100 != 2) {
                throw new LlmCallException(describe(response));
            }
            temperature = temp; // 这种组合能用，记住
            jsonMode = jsonOut;
            return read(response.body());
        }
        throw firstBadRequest;
    }

    /** 请求体。可选参数只在需要时才带上。 */
    private String body(String system, String user, Double temp, boolean jsonOut) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));
        if (maxTokens != null && maxTokens > 0) {
            body.put("max_tokens", maxTokens);
        }
        if (temp != null) {
            body.put("temperature", temp);
        }
        if (jsonOut) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        return json.writeValueAsString(body);
    }

    /**
     * 发请求。网络错误、限流、服务端的临时错误会等一会儿再试，每次等的时间加倍。
     * 400 这类请求本身的问题不在这里重试。
     */
    private HttpResponse<String> send(String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                boolean transientError = status == 408 || status == 409 || status == 429 || status >= 500;
                if (!transientError || attempt >= MAX_RETRIES) {
                    return response;
                }
            } catch (IOException e) {
                if (attempt >= MAX_RETRIES) {
                    throw new LlmCallException("连不上模型接口 " + endpoint + "：" + e, e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmCallException("调用模型时被中断", e);
            }
            try {
                Thread.sleep(Math.min(500L << attempt, 8000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmCallException("调用模型时被中断", e);
            }
        }
    }

    /** 从返回里取出回复，顺带记下用量。 */
    private String read(String responseBody) {
        JsonNode root;
        try {
            root = json.readTree(responseBody);
        } catch (RuntimeException e) {
            throw new LlmCallException("模型接口返回的不是 JSON：" + excerpt(responseBody), e);
        }
        JsonNode usage = root.get("usage");
        record(usage == null || usage.isNull() ? 0 : usage.path("prompt_tokens").asLong(0),
                usage == null || usage.isNull() ? 0 : usage.path("completion_tokens").asLong(0));
        JsonNode choice = root.path("choices").path(0);
        if (choice.isMissingNode()) {
            throw new LlmCallException("模型接口的返回里没有 choices：" + excerpt(responseBody));
        }
        if ("length".equals(choice.path("finish_reason").asString(""))) {
            throw new OutputTruncatedException("模型的输出达到长度上限被截断。调大 max-tokens（环境变量 EXTRACT_LLM_MAX_TOKENS），"
                    + "或者减小 --context-tokens 让每次调用处理的内容少一些");
        }
        JsonNode content = choice.path("message").path("content");
        return content.isString() ? content.stringValue() : "";
    }

    private synchronized void record(long prompt, long completion) {
        calls++;
        promptTokens += prompt;
        completionTokens += completion;
    }

    @Override
    public synchronized Usage usage() {
        return new Usage(calls, promptTokens, completionTokens);
    }

    private String describe(HttpResponse<String> response) {
        return "模型接口 " + endpoint + " 返回 " + response.statusCode() + "：" + excerpt(response.body());
    }

    /** 报错时只带上返回内容的开头，免得一大段 HTML 刷屏。 */
    private static String excerpt(String body) {
        if (body == null) {
            return "";
        }
        String s = body.strip();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
