package com.cb.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用入口。
 *
 * <p>整个平台是一个 Spring Boot 应用，按包分层：
 * <pre>
 *   api  →  app  →  core、capability  ←  infra
 * </pre>
 * <ul>
 *   <li>{@code api}：接入层。命令行、REST 接口都在这里，只负责接收请求和输出结果。</li>
 *   <li>{@code app}：应用层。一个用例一个服务，负责编排。</li>
 *   <li>{@code core}：通用 agent 的核心接口（模型、工具、skill、出处）。不依赖 Spring，不依赖其他包。</li>
 *   <li>{@code capability}：能力。每个能力一个包（文档读取、抽取、问答、报告），不依赖 Spring。</li>
 *   <li>{@code infra}：基础设施。实现 core 和 capability 里定义的接口（模型客户端、存储等）。</li>
 * </ul>
 * 依赖方向由 {@code LayerRulesTest} 在测试里检查。
 */
@SpringBootApplication
public class AgentPlatformApplication {

    public static void main(String[] args) {
        // 现在应用只有命令行这一种用法：执行完命令就退出，并把命令的退出码带出去（0 成功，非 0 失败），
        // 这样脚本里可以判断成没成功。等加上 HTTP 接口之后，这里要改成"给了命令才退出，否则作为服务一直运行"。
        System.exit(SpringApplication.exit(SpringApplication.run(AgentPlatformApplication.class, args)));
    }
}
