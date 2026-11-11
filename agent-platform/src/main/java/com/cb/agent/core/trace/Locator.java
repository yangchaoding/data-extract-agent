package com.cb.agent.core.trace;

import java.util.List;

/**
 * 一个结果的出处：它来自哪份文件，证据在原件的什么位置，值本身又在什么位置。
 *
 * <p>抽取出来的值、问答里的结论、报告里的数字都带着它。三层由粗到细：
 * <ol>
 *   <li>文件：{@code fileId} 加 {@code fileHash}，指明是哪一份文件的哪个版本；</li>
 *   <li>证据：{@code evidenceRegions}，原文摘抄在原件上占的区域，人看一眼就能核对；</li>
 *   <li>值：{@code valueRegions}，值本身占的那一小块，用来精确高亮。</li>
 * </ol>
 *
 * <p>证据跨行、跨页时会有多个区域，所以是列表。值的区域取不到时是空列表。
 *
 * @param fileId          文件在平台里的 ID
 * @param fileHash        文件内容的哈希。文件换了，旧结果的坐标仍然指向旧文件
 * @param origin          证据的文字是怎么来的
 * @param evidenceRegions 证据在原件上的区域
 * @param valueRegions    值在原件上的区域
 */
public record Locator(String fileId, String fileHash, TextOrigin origin,
                      List<Region> evidenceRegions, List<Region> valueRegions) {

    public Locator {
        // 复制一份并禁止修改：出处一旦给出就不该再变
        evidenceRegions = evidenceRegions == null ? List.of() : List.copyOf(evidenceRegions);
        valueRegions = valueRegions == null ? List.of() : List.copyOf(valueRegions);
    }
}
