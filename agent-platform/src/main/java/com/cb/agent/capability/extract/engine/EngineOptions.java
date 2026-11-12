package com.cb.agent.capability.extract.engine;

/**
 * 抽取引擎的参数。对应 Python 版 {@code ExtractionAgent} 的构造参数。
 *
 * <p>默认值就是 Python 版的默认值。skill 可以在自己的清单里改其中几项。
 *
 * @param chunkTokens       每个切片的大小上限（估算的 token 数）
 * @param contextTokens     一次调用里放进去的片段总量上限；整篇文档不超过它时不切片
 * @param topK              每个字段每一轮看几个片段（字段定义里可以单独设）
 * @param maxRounds         一个字段最多找几轮
 * @param retries           没通过核对时，带着具体问题重问几次
 * @param route             片段多的时候，是否先让模型按目录给字段挑片段
 * @param routeTokens       目录的大小上限，超过就只用关键词检索定位
 * @param maxFieldsPerCall  一次调用最多抽几个字段
 * @param workers           同时最多有几个调用在跑
 */
public record EngineOptions(int chunkTokens, int contextTokens, int topK, int maxRounds, int retries,
                            boolean route, int routeTokens, int maxFieldsPerCall, int workers) {

    public static final EngineOptions DEFAULTS = new EngineOptions(1000, 6000, 5, 4, 1, true, 16000, 8, 4);

    public EngineOptions {
        if (chunkTokens <= 0) {
            throw new IllegalArgumentException("chunkTokens 要大于 0，现在是 " + chunkTokens);
        }
        if (maxRounds <= 0) {
            throw new IllegalArgumentException("maxRounds 要大于 0，现在是 " + maxRounds);
        }
        if (retries < 0) {
            throw new IllegalArgumentException("retries 不能是负数，现在是 " + retries);
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 要大于 0，现在是 " + topK);
        }
        if (maxFieldsPerCall <= 0) {
            throw new IllegalArgumentException("maxFieldsPerCall 要大于 0，现在是 " + maxFieldsPerCall);
        }
        if (workers <= 0) {
            throw new IllegalArgumentException("workers 要大于 0，现在是 " + workers);
        }
    }

    /**
     * 切片的大小上限。
     *
     * <p>每片比一次调用的上限还大是自相矛盾的（切片切了也放不进一次调用），所以取两者中小的那个。
     * Python 版里这一步在构造函数里做，效果相同。
     */
    public int effectiveChunkTokens() {
        return contextTokens > 0 ? Math.min(chunkTokens, contextTokens) : chunkTokens;
    }

    public EngineOptions chunkTokens(int value) {
        return new EngineOptions(value, contextTokens, topK, maxRounds, retries, route, routeTokens,
                maxFieldsPerCall, workers);
    }

    public EngineOptions contextTokens(int value) {
        return new EngineOptions(chunkTokens, value, topK, maxRounds, retries, route, routeTokens,
                maxFieldsPerCall, workers);
    }

    public EngineOptions topK(int value) {
        return new EngineOptions(chunkTokens, contextTokens, value, maxRounds, retries, route, routeTokens,
                maxFieldsPerCall, workers);
    }

    public EngineOptions maxRounds(int value) {
        return new EngineOptions(chunkTokens, contextTokens, topK, value, retries, route, routeTokens,
                maxFieldsPerCall, workers);
    }

    public EngineOptions retries(int value) {
        return new EngineOptions(chunkTokens, contextTokens, topK, maxRounds, value, route, routeTokens,
                maxFieldsPerCall, workers);
    }

    public EngineOptions route(boolean value) {
        return new EngineOptions(chunkTokens, contextTokens, topK, maxRounds, retries, value, routeTokens,
                maxFieldsPerCall, workers);
    }

    public EngineOptions routeTokens(int value) {
        return new EngineOptions(chunkTokens, contextTokens, topK, maxRounds, retries, route, value,
                maxFieldsPerCall, workers);
    }

    public EngineOptions maxFieldsPerCall(int value) {
        return new EngineOptions(chunkTokens, contextTokens, topK, maxRounds, retries, route, routeTokens,
                value, workers);
    }

    public EngineOptions workers(int value) {
        return new EngineOptions(chunkTokens, contextTokens, topK, maxRounds, retries, route, routeTokens,
                maxFieldsPerCall, value);
    }
}
