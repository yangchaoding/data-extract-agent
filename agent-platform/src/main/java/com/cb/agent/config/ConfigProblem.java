package com.cb.agent.config;

/**
 * 配置有问题，服务起不来。
 *
 * <p>单独一种异常，是为了让启动失败时打印出来的是"哪一项不对、怎么改"两段话（见 {@link ConfigProblemAnalyzer}），
 * 而不是几十行的调用栈：看到它的人是部署服务的人，要的是这两句。
 */
public class ConfigProblem extends RuntimeException {

    private final String action;

    /**
     * @param description 哪里不对
     * @param action      怎么改
     */
    public ConfigProblem(String description, String action) {
        this(description, action, null);
    }

    public ConfigProblem(String description, String action, Throwable cause) {
        super(description, cause);
        this.action = action;
    }

    /** 怎么改。 */
    public String action() {
        return action;
    }
}
