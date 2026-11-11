package com.cb.agent.capability.document.model;

/**
 * 版面块归一化之后的类别。
 *
 * <p>各种来源对版面的叫法不同（PaddleOCR-VL 有二十多种 {@code block_label}），读取时统一成这几类，
 * 后面的切片和抽取只认这几类。原始的叫法保留在 {@link Block#label()} 里，排查问题时用。
 */
public enum BlockKind {
    /** 标题。层级见 {@link Block#level()}。 */
    HEADING,
    /** 普通正文。 */
    TEXT,
    /** 表格。网格见 {@link Block#table()}。 */
    TABLE,
    /** 题注：表题、图题。它说的是紧跟在后面的那个块，切片时要和后面的内容放在一起。 */
    CAPTION,
    /** 脚注。 */
    FOOTNOTE,
    /** 印章上的文字。 */
    SEAL,
    /** 公式。 */
    FORMULA,
    /** 目录页。目录会命中所有关键词，对定位只有干扰，切片时跳过。 */
    TOC;

    /** 小写的名字，和 Python 版 {@code Block.kind} 的取值相同，导出和比对时用。 */
    public String code() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
