package com.cb.agent.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * 启动失败的原因是 {@link ConfigProblem} 时，告诉 Spring Boot 该怎么说。打印出来是这个样子：
 * <pre>
 *   ***************************
 *   APPLICATION FAILED TO START
 *   ***************************
 *
 *   Description:
 *
 *   agent.tasks.workers 要大于 0，现在是 0
 *
 *   Action:
 *
 *   改 application.yml 里对应的那一项，或者用环境变量、命令行参数（--配置项=值）盖住它，然后重新启动。
 * </pre>
 * 外面那几行英文是 Spring Boot 固定的格式。
 *
 * <p>这个类不是 Spring 管理的对象：它在容器还没建起来（或者已经建失败了）的时候用，
 * 所以登记在 {@code META-INF/spring.factories} 里，由 Spring Boot 自己去找。
 */
public class ConfigProblemAnalyzer extends AbstractFailureAnalyzer<ConfigProblem> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ConfigProblem cause) {
        return new FailureAnalysis(cause.getMessage(), cause.action(), cause);
    }
}
