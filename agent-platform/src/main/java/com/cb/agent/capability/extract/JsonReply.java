package com.cb.agent.capability.extract;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从模型的回复里取出 JSON 对象。对应 Python 版 {@code llm.py} 的 {@code parse_json}。
 *
 * <p>要求模型只输出一个 JSON 对象，但它常常不完全照办：前面带一段思考过程，
 * 把 JSON 包在代码围栏里，或者前后加上几句说明。这些都容忍。
 */
public final class JsonReply {

    /** 有的模型把思考过程放在这对标签里。 */
    private static final Pattern THINK = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);
    /** Markdown 的代码围栏。 */
    private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*(.*?)```", Pattern.DOTALL);

    /** JSON 后面还跟着别的内容时算解析失败，这样才会接着去试"从第一个 { 到最后一个 }"那一段。 */
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private JsonReply() {
    }

    /**
     * 依次试三种取法，用第一个能解析成对象的：整段回复；代码围栏里的内容；从第一个 {@code {} 到最后一个 {@code }}。
     *
     * @return JSON 对象，键的顺序和回复里的相同
     * @throws IllegalArgumentException 回复里找不到一个 JSON 对象
     */
    public static Map<String, Object> parse(String text) {
        String s = THINK.matcher(text).replaceAll("").strip();
        List<String> candidates = new ArrayList<>();
        candidates.add(s);
        Matcher fence = FENCE.matcher(s);
        while (fence.find()) {
            candidates.add(fence.group(1));
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && start < end) {
            candidates.add(s.substring(start, end + 1));
        }
        for (String candidate : candidates) {
            Object data;
            try {
                data = JSON.readValue(candidate, Object.class);
            } catch (RuntimeException e) {
                continue;
            }
            if (data instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> object = (Map<String, Object>) map;
                return object;
            }
        }
        throw new IllegalArgumentException("模型的回复不是 JSON 对象");
    }
}
