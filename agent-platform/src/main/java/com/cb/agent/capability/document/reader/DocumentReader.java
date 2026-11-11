package com.cb.agent.capability.document.reader;

import com.cb.agent.capability.document.model.Document;

import java.nio.file.Path;
import java.util.List;

/**
 * 文档读取器：把某一种来源的文件读成统一的 {@link Document}。
 *
 * <p>每支持一种新的来源（一种文件格式，或者一个识别引擎的输出），就加一个实现。
 * 后面的切片、抽取、问答只认 {@link Document}，不用跟着改。
 */
public interface DocumentReader {

    /** 读取器的名字，写进读取报告里，例如 {@code paddle-ocr-vl-json}。 */
    String name();

    /**
     * 这个路径是不是自己能读的。
     *
     * <p>只做便宜的判断（看扩展名、看目录里有没有对应的文件），不要在这里把文件读完。
     *
     * @param path 一个文件，或者一个目录
     */
    boolean supports(Path path);

    /**
     * 读取。
     *
     * @param path 一个文件，或者放着多个文件的目录
     * @return 读出的文档。一个目录里可以有多份文档，所以是列表；按发现的先后排列
     * @throws IllegalArgumentException 路径不存在，或者内容不是这个读取器认识的格式
     */
    List<Document> read(Path path);
}
