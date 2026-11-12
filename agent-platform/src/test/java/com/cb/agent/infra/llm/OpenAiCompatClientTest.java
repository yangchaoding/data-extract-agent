package com.cb.agent.infra.llm;

import com.cb.agent.core.llm.LlmCallException;
import com.cb.agent.core.llm.LlmClient;
import com.cb.agent.core.llm.OutputTruncatedException;
import com.cb.agent.core.llm.Usage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型客户端的测试。不调用真实的模型：在本机起一个假的模型服务，接口的样子和 OpenAI 的
 * {@code /v1/chat/completions} 相同，让客户端走真实的 HTTP 请求。
 *
 * <p>对应 Python 版 {@code tests/test_llm.py} 和 {@code tests/support.py} 里的假服务。
 */
class OpenAiCompatClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 假服务的一次应答。 */
    private record Reply(int status, String body) {
    }

    private HttpServer server;
    private String baseUrl;
    /** 假服务收到的请求体，按先后。 */
    private final List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> authHeaders = Collections.synchronizedList(new ArrayList<>());
    /** 假服务怎么应答。每个测试自己设。 */
    private volatile Function<JsonNode, Reply> responder = body -> ok("{\"ok\": true}", "stop");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        JsonNode body = JSON.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        requests.add(body);
        authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
        Reply reply = responder.apply(body);
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** 一次正常的应答。{@code content} 为 null 表示模型什么都没说。 */
    private static Reply ok(String content, String finishReason) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", finishReason);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", "chatcmpl-test");
        body.put("choices", List.of(choice));
        body.put("usage", Map.of("prompt_tokens", 11, "completion_tokens", 5, "total_tokens", 16));
        return new Reply(200, JSON.writeValueAsString(body));
    }

    private static Reply error(int status, String message) {
        return new Reply(status, JSON.writeValueAsString(Map.of("error", Map.of("message", message))));
    }

    private OpenAiCompatClient client(Integer maxTokens) {
        return new OpenAiCompatClient(baseUrl, "test-model", "KEY", maxTokens, Duration.ofSeconds(10));
    }

    // ---- 正常情况 ----------------------------------------------------------------------

    @Test
    void 发出的请求和取回的回复() {
        responder = body -> ok("你好", "stop");
        OpenAiCompatClient client = client(null);

        assertEquals("你好", client.complete("系统提示词", "用户消息"));

        JsonNode request = requests.get(0);
        assertEquals("test-model", request.get("model").stringValue());
        assertEquals("system", request.get("messages").get(0).get("role").stringValue());
        assertEquals("系统提示词", request.get("messages").get(0).get("content").stringValue());
        assertEquals("用户消息", request.get("messages").get(1).get("content").stringValue());
        assertEquals(0.0, request.get("temperature").doubleValue(), "抽取要稳定，温度设为 0");
        assertEquals("json_object", request.get("response_format").get("type").stringValue());
        assertFalse(request.has("max_tokens"), "没有设上限时不该带这个参数");
        assertEquals("Bearer KEY", authHeaders.get(0));
    }

    @Test
    void 每次调用都累计用量() {
        OpenAiCompatClient client = client(null);
        client.complete("s", "u");
        client.complete("s", "u");
        assertEquals(new Usage(2, 22, 10), client.usage());
    }

    @Test
    void 设了输出上限就带上() {
        client(4096).complete("s", "u");
        assertEquals(4096, requests.get(0).get("max_tokens").intValue());
    }

    @Test
    void 接口地址末尾多一个斜杠也行() {
        new OpenAiCompatClient(baseUrl + "/", "m", "K", null, Duration.ofSeconds(10)).complete("s", "u");
        assertEquals(1, requests.size());
    }

    @Test
    void 模型什么都没说时返回空串() {
        responder = body -> ok(null, "stop");
        assertEquals("", client(null).complete("s", "u"));
    }

    // ---- 参数降级 ----------------------------------------------------------------------

    @Test
    void 服务不支持temperature时去掉它重试并且记住() {
        responder = body -> body.has("temperature") ? error(400, "temperature is not supported") : ok("好", "stop");
        OpenAiCompatClient client = client(null);

        assertEquals("好", client.complete("s", "u"));
        assertEquals(2, requests.size(), "第一次带着 temperature 被拒，第二次去掉之后成功");
        assertFalse(requests.get(1).has("temperature"));
        assertTrue(requests.get(1).has("response_format"), "只去掉不支持的那个参数");

        client.complete("s", "u");
        assertEquals(3, requests.size(), "已经记住这个服务不支持 temperature，不再先试一次");
        assertFalse(requests.get(2).has("temperature"));
    }

    @Test
    void 服务不支持response_format时也去掉() {
        responder = body -> body.has("response_format") ? error(400, "response_format is not supported") : ok("好", "stop");
        OpenAiCompatClient client = client(null);

        assertEquals("好", client.complete("s", "u"));
        assertEquals(3, requests.size(), "依次试：都带、去掉 temperature、两个都去掉");
        assertFalse(requests.get(2).has("temperature"));
        assertFalse(requests.get(2).has("response_format"));
    }

    @Test
    void 怎么降级都是400时抛出最初的错误并且不改设置() {
        responder = body -> error(400, "输入太长");
        OpenAiCompatClient client = client(null);

        LlmCallException e = assertThrows(LlmCallException.class, () -> client.complete("s", "u"));
        assertTrue(e.getMessage().contains("400"), e.getMessage());
        assertTrue(e.getMessage().contains("输入太长"), e.getMessage());
        assertEquals(3, requests.size());

        // 400 另有原因，不能因此认为服务不支持这两个参数：下一次还是从都带着开始试
        assertThrows(LlmCallException.class, () -> client.complete("s", "u"));
        assertTrue(requests.get(3).has("temperature"));
        assertTrue(requests.get(3).has("response_format"));
    }

    // ---- 截断和错误 --------------------------------------------------------------------

    @Test
    void 输出被截断时抛出专门的异常() {
        responder = body -> ok("{\"a\": [1, 2", "length");
        OpenAiCompatClient client = client(null);

        OutputTruncatedException e = assertThrows(OutputTruncatedException.class, () -> client.complete("s", "u"));
        assertTrue(e.getMessage().contains("EXTRACT_LLM_MAX_TOKENS"), "要告诉使用者怎么办：" + e.getMessage());
        assertEquals(1, client.usage().calls(), "被截断的这一次也花了 token，要算进用量");
    }

    @Test
    void 认证失败不重试直接报错() {
        responder = body -> error(401, "invalid api key");
        LlmCallException e = assertThrows(LlmCallException.class, () -> client(null).complete("s", "u"));
        assertTrue(e.getMessage().contains("401"), e.getMessage());
        assertEquals(1, requests.size());
    }

    @Test
    void 服务端临时出错时等一会儿再试() {
        responder = body -> requests.size() == 1 ? error(503, "overloaded") : ok("好了", "stop");
        assertEquals("好了", client(null).complete("s", "u"));
        assertEquals(2, requests.size());
    }

    @Test
    void 连不上时报错并说明是哪个接口() {
        server.stop(0);
        OpenAiCompatClient client = new OpenAiCompatClient("http://127.0.0.1:1/v1", "m", "K", null, Duration.ofSeconds(2));
        LlmCallException e = assertThrows(LlmCallException.class, () -> client.complete("s", "u"));
        assertTrue(e.getMessage().contains("http://127.0.0.1:1/v1/chat/completions"), e.getMessage());
    }

    // ---- 端点白名单 --------------------------------------------------------------------

    private static ConfiguredLlmClientProvider provider(String baseUrl, List<String> allowed) {
        return new ConfiguredLlmClientProvider(
                new ConfiguredLlmClientProvider.Settings(baseUrl, "cb-llm", "EMPTY", null, 300, allowed));
    }

    @Test
    void 名单里的主机可以连() {
        LlmClient a = provider("http://10.10.10.123:9503/v1", List.of("10.10.10.123")).get(null, null);
        assertTrue(a instanceof OpenAiCompatClient);
    }

    @Test
    void 本机地址总是可以连() {
        provider("http://localhost:8000/v1", List.of()).get(null, null);
        provider("http://127.0.0.1:8000/v1", List.of()).get(null, null);
    }

    @Test
    void 名单外的主机被拒绝并说明怎么加() {
        // 这正是要防的情况：环境里设过 OPENAI_BASE_URL，文档的内容会被悄悄发到外部服务
        ConfiguredLlmClientProvider p = provider("https://api.example.com/v1", List.of("10.10.10.123"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> p.get(null, null));
        assertTrue(e.getMessage().contains("api.example.com"), e.getMessage());
        assertTrue(e.getMessage().contains("EXTRACT_LLM_ALLOWED_HOSTS"), "要告诉使用者怎么放行：" + e.getMessage());
    }

    @Test
    void 调用时指定的地址也要过名单() {
        ConfiguredLlmClientProvider p = provider("http://10.10.10.123:9503/v1", List.of("10.10.10.123"));
        assertThrows(IllegalArgumentException.class, () -> p.get(null, "https://api.example.com/v1"));
        p.get("另一个模型", "http://localhost:1234/v1");
    }

    @Test
    void 同一个接口和模型只建一个客户端() {
        ConfiguredLlmClientProvider p = provider("http://localhost:8000/v1", List.of());
        assertSame(p.get(null, null), p.get("cb-llm", null), "它记着服务不支持哪些参数，也累计着用量");
        assertNotSame(p.get(null, null), p.get("另一个模型", null));
    }

    @Test
    void 地址写得不对时报错() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> provider("不是地址", List.of()).get(null, null));
        assertTrue(e.getMessage().contains("地址不对"), e.getMessage());
    }
}
