package com.cb.agent.config;

import com.cb.agent.capability.document.DocumentLoader;
import com.cb.agent.capability.document.reader.DocumentReader;
import com.cb.agent.capability.document.reader.paddle.PaddleOcrVlJsonReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 文档读取的装配。
 *
 * <p>{@code capability} 包里的类不依赖 Spring，它们在这里被组装成 Spring 管理的对象。
 * 以后每加一个读取器（Word、Excel、PDF、OFD、Xiaomi-OCR…），在 {@link #documentLoader} 的列表里加一项。
 */
@Configuration
public class DocumentConfig {

    @Bean
    public PaddleOcrVlJsonReader paddleOcrVlJsonReader() {
        return new PaddleOcrVlJsonReader();
    }

    /**
     * 读取入口。列表的顺序就是询问的顺序，更具体的读取器排在前面。
     */
    @Bean
    public DocumentLoader documentLoader(PaddleOcrVlJsonReader paddle) {
        List<DocumentReader> readers = List.of(paddle);
        return new DocumentLoader(readers);
    }
}
