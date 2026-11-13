package com.cb.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
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
 *
 * <p>两种用法，由第一个参数决定：
 * <ul>
 *   <li>{@code serve}：作为服务一直运行，提供 HTTP 接口，直到被停掉；</li>
 *   <li>其他命令（{@code inspect}、{@code chunks}、{@code skills}、{@code extract}），或者不带参数：
 *       执行完就退出，不启动 HTTP 服务，不碰任务的存储。</li>
 * </ul>
 */
@SpringBootApplication
public class AgentPlatformApplication {

    /** 启动 HTTP 服务的那个命令。 */
    public static final String SERVE = "serve";

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(AgentPlatformApplication.class);
        if (serving(args)) {
            // 服务：run 返回之后主线程结束，内嵌的 Tomcat 让进程继续运行。
            // 启动失败时 Spring 已经打印了原因，这里只负责让退出码不是 0
            try {
                app.run(args);
            } catch (RuntimeException e) {
                System.exit(1);
            }
            return;
        }
        // 命令行：不启动 Web 容器（快，也不占端口），执行完把命令的退出码带出去（0 成功，非 0 失败），
        // 这样脚本里可以判断成没成功
        app.setWebApplicationType(WebApplicationType.NONE);
        System.exit(SpringApplication.exit(app.run(args)));
    }

    /**
     * 是不是要作为服务运行：第一个不以横线开头的参数是 {@code serve}。
     * 前面可以有 {@code --server.port=8081} 这样的 Spring 配置项。
     */
    static boolean serving(String[] args) {
        for (String arg : args) {
            if (!arg.startsWith("-")) {
                return arg.equals(SERVE);
            }
        }
        return false;
    }
}
