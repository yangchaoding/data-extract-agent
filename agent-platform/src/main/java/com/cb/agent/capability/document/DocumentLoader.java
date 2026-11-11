package com.cb.agent.capability.document;

import com.cb.agent.capability.document.model.Document;
import com.cb.agent.capability.document.reader.DocumentReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 读取文档的统一入口：给一个路径，挑一个能读它的读取器，读成 {@link Document}。
 *
 * <p>调用方不需要知道文件是什么格式、该用哪个读取器。以后每支持一种新的来源，
 * 只要把它的读取器加进构造函数的列表里，这里和调用方都不用改。
 */
public final class DocumentLoader {

    private final List<DocumentReader> readers;

    /**
     * @param readers 可用的读取器。按顺序询问，第一个说"能读"的负责读，所以更具体的读取器要排在前面
     */
    public DocumentLoader(List<? extends DocumentReader> readers) {
        this.readers = List.copyOf(readers);
    }

    /**
     * 读一个文件或一个目录。
     *
     * @param path 文件，或者放着多个文件的目录
     * @return 读出的文档。一个目录里可以有多份文档
     * @throws IllegalArgumentException 路径不存在；没有读取器能读；或者内容不是认识的格式
     */
    public List<Document> load(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("找不到输入: " + path);
        }
        for (DocumentReader reader : readers) {
            if (reader.supports(path)) {
                return reader.read(path);
            }
        }
        List<String> names = readers.stream().map(DocumentReader::name).toList();
        throw new IllegalArgumentException("没有能读这个输入的读取器: " + path + "。目前支持的读取器：" + names);
    }
}
