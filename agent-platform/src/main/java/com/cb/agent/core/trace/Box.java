package com.cb.agent.core.trace;

/**
 * 页面上的一个矩形，用相对比例表示。
 *
 * <p>四个值都在 0 到 1 之间：x 是相对页面宽度的比例，y 是相对页面高度的比例，原点在页面左上角。
 *
 * <p>为什么不用像素：各种来源的坐标单位不一样。同一份文档，PaddleOCR-VL 按 1191×1684 渲染，
 * Xiaomi-OCR 按 1240×1754 渲染；PDF 的单位是磅，原点还在左下角；OFD 的单位是毫米。
 * 读取时统一换算成比例，前端按比例画框，不用关心文件是从哪来的。
 *
 * @param x1 左边界
 * @param y1 上边界
 * @param x2 右边界
 * @param y2 下边界
 */
public record Box(double x1, double y1, double x2, double y2) {

    /**
     * 从像素坐标换算。
     *
     * @param px1    左边界（像素）
     * @param py1    上边界（像素）
     * @param px2    右边界（像素）
     * @param py2    下边界（像素）
     * @param width  页面宽度（像素），必须大于 0
     * @param height 页面高度（像素），必须大于 0
     * @return 相对比例表示的矩形。超出页面的部分会被截到页面边界上
     */
    public static Box fromPixels(double px1, double py1, double px2, double py2, double width, double height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("页面的宽和高必须大于 0，现在是 " + width + "×" + height);
        }
        return new Box(clamp(px1 / width), clamp(py1 / height), clamp(px2 / width), clamp(py2 / height));
    }

    /** 识别结果里的框偶尔会略微超出页面，截到 0 到 1 之间，免得前端画到页面外面。 */
    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
