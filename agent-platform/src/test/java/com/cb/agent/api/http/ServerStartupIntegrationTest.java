package com.cb.agent.api.http;

import com.cb.agent.AgentPlatformApplication;
import com.cb.agent.config.ConfigProblem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务配置在启动时检查：本机服务可以省略令牌，外部监听必须鉴权，错误的并发配置不能等到提交任务才暴露。
 * 使用真实应用的配置和随机端口，模型装配沿用 HTTP 验收的假模型，不会向网络上的模型发请求。
 */
class ServerStartupIntegrationTest {

    @TempDir
    Path store;

    @Test
    void 仅本机监听时可以省略令牌() throws Exception {
        try (ConfigurableApplicationContext context = application().run(arguments("--server.address=127.0.0.1"))) {
            String port = context.getEnvironment().getRequiredProperty("local.server.port");
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/skills"))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            assertEquals(200, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test
    void 在所有网卡监听却没令牌时拒绝启动() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> application().run(arguments("--server.address=0.0.0.0")));
        ConfigProblem problem = configurationProblem(failure);
        assertTrue(problem.getMessage().contains("没有配置令牌"), problem.getMessage());
    }

    @Test
    void 模型并发上限写成零时拒绝启动并说明配置项() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> application().run(arguments("--server.address=127.0.0.1", "--agent.llm.max-concurrency=0")));
        ConfigProblem problem = configurationProblem(failure);
        assertTrue(problem.getMessage().contains("agent.llm.max-concurrency 要大于 0"), problem.getMessage());
    }

    private static SpringApplication application() {
        SpringApplication app = new SpringApplication(AgentPlatformApplication.class, HttpApiIntegrationTest.FakeModel.class);
        app.setRegisterShutdownHook(false); // 测试自己关闭上下文，不为每个配置用例积累进程退出钩子
        return app;
    }

    private String[] arguments(String... overrides) {
        List<String> arguments = new ArrayList<>(List.of("serve", "--server.port=0", "--agent.api.token=",
                "--agent.store.dir=" + store, "--agent.skills.dir=../.claude/skills", "--agent.tasks.workers=1",
                "--agent.tasks.retention-days=0"));
        arguments.addAll(Arrays.asList(overrides));
        return arguments.toArray(String[]::new);
    }

    /** Spring 在 bean 创建异常外面还有包装，检查实际的配置错误，不依赖包装层数。 */
    private static ConfigProblem configurationProblem(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ConfigProblem problem) {
                return problem;
            }
            current = current.getCause();
        }
        throw new AssertionError("启动失败却没有明确的配置错误", failure);
    }
}
