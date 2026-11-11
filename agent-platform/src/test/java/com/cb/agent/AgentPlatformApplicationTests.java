package com.cb.agent;

import com.cb.agent.app.document.DocumentReadService;
import com.cb.agent.capability.document.model.Document;
import com.cb.agent.testsupport.Golden;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 整个应用能不能装配起来。
 *
 * <p>前面的测试都是直接 new 出对象来测的。这里启动完整的 Spring 容器，确认各层之间的装配是对的：
 * 配置类建出了读取器，应用层的服务拿到了它，并且真的能读出文档。
 */
@SpringBootTest
class AgentPlatformApplicationTests {

    @Autowired
    private DocumentReadService documents;

    @Test
    void 容器启动之后能通过应用层读出文档() {
        List<Document> docs = documents.read(List.of(Golden.FIXTURES.resolve("paddle").resolve("sample_contract")));

        assertEquals(1, docs.size(), "目录里的三个文件是同一份合同的三页");
        assertEquals("sample_contract", docs.get(0).name());
        assertEquals(3, docs.get(0).pageCount());
    }
}
