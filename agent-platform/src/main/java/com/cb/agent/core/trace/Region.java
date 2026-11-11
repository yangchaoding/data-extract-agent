package com.cb.agent.core.trace;

/**
 * 原件上的一个位置。
 *
 * <p>不同的文件用不同的方式定位，所以字段分成几组，每种来源只填它有的那一组，其余为 {@code null}：
 * <ul>
 *   <li>PDF、OFD、图片、识别结果：{@code page} 加 {@code box}；</li>
 *   <li>Excel：{@code sheet} 加 {@code cellRange}；</li>
 *   <li>Word、txt：{@code paragraph}（或行号）加 {@code charStart}、{@code charEnd}。</li>
 * </ul>
 *
 * @param page        页码，从 1 开始。没有页的概念时为 null
 * @param box         页面上的矩形，相对比例。只知道页码时为 null
 * @param sheet       Excel 的工作表名
 * @param cellRange   Excel 的单元格区域，例如 {@code A12:G12}
 * @param paragraph   Word 里是第几段，txt 里是第几行，从 1 开始
 * @param charStart   在这一段（行）里从第几个字开始，从 0 开始
 * @param charEnd     到第几个字为止（不含）
 * @param granularity 这个位置精确到哪一级
 */
public record Region(Integer page, Box box,
                     String sheet, String cellRange,
                     Integer paragraph, Integer charStart, Integer charEnd,
                     Granularity granularity) {

    public Region {
        if (granularity == null) {
            throw new IllegalArgumentException("必须说明位置精确到哪一级");
        }
    }

    /** 只知道在哪一页。 */
    public static Region ofPage(int page) {
        return new Region(page, null, null, null, null, null, null, Granularity.PAGE);
    }

    /** 页面上的一个框。 */
    public static Region ofBox(int page, Box box, Granularity granularity) {
        if (box == null) {
            throw new IllegalArgumentException("没有框时请用 ofPage");
        }
        return new Region(page, box, null, null, null, null, null, granularity);
    }
}
