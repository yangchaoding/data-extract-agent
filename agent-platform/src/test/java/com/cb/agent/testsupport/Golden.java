package com.cb.agent.testsupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试里读基准数据用的小工具。
 *
 * <p>基准数据在 {@code src/test/resources/golden} 下，由 {@code tools/make_golden.py} 用 Python 版生成。
 * 输入在 {@code src/test/resources/fixtures} 下。Maven 跑测试时的当前目录是项目根目录，所以直接用相对路径。
 */
public final class Golden {

    public static final Path RESOURCES = Path.of("src", "test", "resources");
    public static final Path FIXTURES = RESOURCES.resolve("fixtures");
    public static final Path GOLDEN = RESOURCES.resolve("golden");

    public static final JsonMapper JSON = JsonMapper.builder().build();

    private Golden() {
    }

    /** 读一个 JSON 文件。 */
    public static JsonNode read(Path path) {
        try {
            return JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("读不了基准文件 " + path + "。是不是还没有运行 tools/make_golden.py？", e);
        }
    }

    /** 把任意对象（Map、List、记录）变成 JSON 树，方便和基准数据比较。 */
    public static JsonNode tree(Object value) {
        return JSON.valueToTree(value);
    }
}
