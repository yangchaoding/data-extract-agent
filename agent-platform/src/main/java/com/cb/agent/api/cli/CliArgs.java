package com.cb.agent.api.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 解析命令行参数：{@code 命令 路径… --选项 值 --开关}。
 *
 * <p>没有用 Spring 自带的参数解析，因为它只认 {@code --name=value} 这一种写法，
 * {@code --chunk-tokens 300} 里的 300 会被当成一个路径。这里两种写法都认，和 Python 版的命令行一致。
 */
final class CliArgs {

    /** 命令写错了：不认识的命令或选项，或者选项少了值。和"读取失败"区分开，退出码不同。 */
    static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    /** 不带值的开关。 */
    private static final Set<String> FLAGS = Set.of("show", "json", "no-route");
    /** 后面要跟一个值的选项。 */
    private static final Set<String> VALUED = Set.of(
            "chunk-tokens", "context-tokens", "skill", "schema", "output",
            "top-k", "max-rounds", "workers", "model", "base-url");
    /** 单个横线的简写。 */
    private static final Map<String, String> SHORT = Map.of("-o", "output");

    private final String command;
    private final List<String> inputs = new ArrayList<>();
    private final Set<String> flags = new HashSet<>();
    private final Map<String, String> values = new HashMap<>();

    private CliArgs(String command) {
        this.command = command;
    }

    /**
     * @param raw 原始的参数，第一个是命令
     * @throws UsageException 有不认识的选项，或者选项后面少了值
     */
    static CliArgs parse(String[] raw) {
        CliArgs out = new CliArgs(raw[0]);
        for (int i = 1; i < raw.length; i++) {
            String arg = raw[i];
            String name;
            String inline = null; // 写成 --name=value 时等号后面的部分
            if (SHORT.containsKey(arg)) {
                name = SHORT.get(arg);
            } else if (arg.startsWith("--")) {
                int eq = arg.indexOf('=');
                name = eq < 0 ? arg.substring(2) : arg.substring(2, eq);
                inline = eq < 0 ? null : arg.substring(eq + 1);
            } else {
                out.inputs.add(arg);
                continue;
            }
            if (FLAGS.contains(name)) {
                out.flags.add(name);
            } else if (VALUED.contains(name)) {
                if (inline == null) {
                    if (i + 1 >= raw.length) {
                        throw new UsageException("--" + name + " 后面要跟一个值");
                    }
                    inline = raw[++i];
                }
                out.values.put(name, inline);
            } else {
                throw new UsageException("不认识的选项：" + arg);
            }
        }
        return out;
    }

    String command() {
        return command;
    }

    /** 命令后面给出的文件或目录。 */
    List<String> inputs() {
        return inputs;
    }

    boolean flag(String name) {
        return flags.contains(name);
    }

    /** 选项的值；没有给出时返回 null。 */
    String value(String name) {
        return values.get(name);
    }

    /**
     * 整数选项的值。
     *
     * @param fallback 没有给出这个选项时用的默认值
     * @throws UsageException 给出的不是整数
     */
    int intValue(String name, int fallback) {
        String v = values.get(name);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new UsageException("--" + name + " 后面要跟一个整数，现在是：" + v);
        }
    }
}
