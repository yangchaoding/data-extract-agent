package com.cb.agent.capability.document.model;

/**
 * 一页的基本信息。
 *
 * @param number 页码，从 1 开始
 * @param width  页面图像的宽度（像素）。识别结果里没有给出时为 null
 * @param height 页面图像的高度（像素）。识别结果里没有给出时为 null
 */
public record PageInfo(int number, Integer width, Integer height) {
}
