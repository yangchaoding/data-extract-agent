package com.cb.agent.capability.document.model;

import com.cb.agent.core.trace.Box;

import java.util.List;

/**
 * 识别结果里原样给出的框，单位是该页图像上的像素：{@code [x1, y1, x2, y2]}，原点在左上角。
 *
 * <p>保留原始的像素值有两个用处：一是和 Python 版的输出逐项比对；二是排查问题时能直接对上识别结果文件。
 * 对外给出的出处用相对比例，见 {@link #toBox(PageInfo)}。
 */
public record PixelBox(int x1, int y1, int x2, int y2) {

    /** 宽度（像素）。判断两页的表格是不是同一张表时要比较宽度。 */
    public int width() {
        return x2 - x1;
    }

    /**
     * 换算成相对页面的比例。
     *
     * @param page 这个框所在的页。不知道页面尺寸时无法换算
     * @return 相对比例的框；页面尺寸未知时返回 null
     */
    public Box toBox(PageInfo page) {
        if (page == null || page.width() == null || page.height() == null
                || page.width() <= 0 || page.height() <= 0) {
            return null;
        }
        return Box.fromPixels(x1, y1, x2, y2, page.width(), page.height());
    }

    /** 和 Python 版一样的列表形式，导出时用。 */
    public List<Integer> toList() {
        return List.of(x1, y1, x2, y2);
    }
}
