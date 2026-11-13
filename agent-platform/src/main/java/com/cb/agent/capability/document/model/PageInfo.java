package com.cb.agent.capability.document.model;

/**
 * 一页的基本信息。
 *
 * @param number 页码，从 1 开始
 * @param width  页面图像的宽度（像素）。识别结果里没有给出时为 null
 * @param height 页面图像的高度（像素）。识别结果里没有给出时为 null
 * @param file   这一页是从哪个文件读出来的（文件名，不带目录）。每页存一个文件时，各页的文件不同；
 *               识别结果是直接从内存里给的、不是从文件读的时候为 null。
 *               出处的"文件"那一层靠它知道一个位置落在哪个文件上
 */
public record PageInfo(int number, Integer width, Integer height, String file) {
}
