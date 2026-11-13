package com.cb.agent.app.extract;

import com.cb.agent.app.document.InputGroup;
import com.cb.agent.capability.extract.engine.EngineOptions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次提交抽取的请求。接入层把收到的东西整理成它，交给 {@link ExtractionTaskService}。
 *
 * @param skill          用哪个 skill：一个名称，或者 {@link #AUTO} 表示按文档内容自动选
 * @param documents      要抽取的文档，每一项是一组文件
 * @param options        这一次临时改的引擎参数
 * @param idempotencyKey 幂等键：同一个键再提交一次，得到的是原来那个任务，不会重复抽取。可以为 null
 */
public record ExtractionRequest(String skill, List<InputGroup> documents, Options options, String idempotencyKey) {

    /** {@code skill} 写成它，表示按文档内容自动选。 */
    public static final String AUTO = "auto";

    /** 幂等键最长多少个字。 */
    public static final int MAX_KEY_LENGTH = 200;

    public ExtractionRequest {
        if (skill == null || skill.isBlank()) {
            throw new IllegalArgumentException("要用 skill 指明用哪个 skill；不知道文档是哪一类时写 \"auto\"");
        }
        skill = skill.strip();
        documents = List.copyOf(documents);
        options = options == null ? Options.NONE : options;
        if (idempotencyKey != null) {
            idempotencyKey = idempotencyKey.strip();
            if (idempotencyKey.isEmpty() || idempotencyKey.length() > MAX_KEY_LENGTH) {
                throw new IllegalArgumentException("idempotencyKey 要有 1 到 " + MAX_KEY_LENGTH + " 个字");
            }
        }
    }

    /** 是不是要自动选 skill。 */
    public boolean auto() {
        return AUTO.equalsIgnoreCase(skill);
    }

    /**
     * 调用方这一次临时改的引擎参数。没给的是 null，用 skill 清单里写的或默认值。
     *
     * <p>并发数不在这里：它是部署时按模型服务的容量定的，不由调用方决定。
     *
     * @param chunkTokens   每片的大小上限
     * @param contextTokens 一次调用里放多少原文；整篇文档不超过它时不切片
     * @param topK          每个字段每一轮看几个片段
     * @param maxRounds     一个字段最多找几轮
     * @param route         片段多的时候是否先让模型按目录定位
     */
    public record Options(Integer chunkTokens, Integer contextTokens, Integer topK, Integer maxRounds,
                          Boolean route) {

        public static final Options NONE = new Options(null, null, null, null, null);

        /**
         * 把给了的几项盖到 {@code base} 上。
         *
         * @throws IllegalArgumentException 某一项不在范围里（由 {@link EngineOptions} 检查）
         */
        public EngineOptions applyTo(EngineOptions base) {
            EngineOptions out = base;
            if (chunkTokens != null) {
                out = out.chunkTokens(chunkTokens);
            }
            if (contextTokens != null) {
                out = out.contextTokens(contextTokens);
            }
            if (topK != null) {
                out = out.topK(topK);
            }
            if (maxRounds != null) {
                out = out.maxRounds(maxRounds);
            }
            if (route != null) {
                out = out.route(route);
            }
            return out;
        }

        /** 只包含给了的那几项。 */
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            if (chunkTokens != null) {
                out.put("chunkTokens", chunkTokens);
            }
            if (contextTokens != null) {
                out.put("contextTokens", contextTokens);
            }
            if (topK != null) {
                out.put("topK", topK);
            }
            if (maxRounds != null) {
                out.put("maxRounds", maxRounds);
            }
            if (route != null) {
                out.put("route", route);
            }
            return out;
        }

        /** {@link #toMap()} 的逆过程，从存下来的任务记录里读回来时用。 */
        public static Options fromMap(Map<?, ?> map) {
            if (map == null) {
                return NONE;
            }
            return new Options(integer(map.get("chunkTokens")), integer(map.get("contextTokens")),
                    integer(map.get("topK")), integer(map.get("maxRounds")),
                    map.get("route") instanceof Boolean flag ? flag : null);
        }

        private static Integer integer(Object value) {
            return value instanceof Number number ? number.intValue() : null;
        }
    }
}
