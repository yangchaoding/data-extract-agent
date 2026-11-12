package com.cb.agent.core.llm;

/**
 * 模型的用量。
 *
 * @param calls            调用了多少次
 * @param promptTokens     输入的 token 数
 * @param completionTokens 输出的 token 数
 */
public record Usage(long calls, long promptTokens, long completionTokens) {

    public static final Usage ZERO = new Usage(0, 0, 0);

    /** 两个时刻的累计用量相减，得到中间这一段的用量。 */
    public Usage minus(Usage earlier) {
        return new Usage(calls - earlier.calls, promptTokens - earlier.promptTokens,
                completionTokens - earlier.completionTokens);
    }
}
