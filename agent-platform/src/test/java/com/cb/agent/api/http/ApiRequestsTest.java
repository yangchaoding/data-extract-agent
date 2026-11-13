package com.cb.agent.api.http;

import com.cb.agent.app.document.InputGroup;
import com.cb.agent.app.document.UploadLimits;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求解析的上传边界：拒绝超限文件时不能先把它们读进内存；编码、文件名、字节数也按同一套约定处理。
 * HTTP 状态码和整个抽取流程由服务集成测试检查。
 */
class ApiRequestsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte[] CONTENT = "{}".getBytes(StandardCharsets.UTF_8);

    @Test
    void multipart单文件超限时一个文件也不读取() {
        SizedFile file = new SizedFile("large.json", 9, CONTENT);

        UploadLimits.TooLargeException error = assertThrows(UploadLimits.TooLargeException.class,
                () -> ApiRequests.documents(List.of(file), null, new UploadLimits(1, 2, 8, 16)));

        assertTrue(error.getMessage().contains("单个文件的上限 8"), error.getMessage());
        assertEquals(0, file.reads);
    }

    @Test
    void multipart总量超限时前面的文件也不读取() {
        SizedFile first = new SizedFile("a.json", 6, CONTENT);
        SizedFile second = new SizedFile("b.json", 6, CONTENT);

        UploadLimits.TooLargeException error = assertThrows(UploadLimits.TooLargeException.class,
                () -> ApiRequests.documents(List.of(first, second), null, new UploadLimits(1, 2, 8, 10)));

        assertTrue(error.getMessage().contains("一共 12 字节"), error.getMessage());
        assertEquals(0, first.reads);
        assertEquals(0, second.reads);
    }

    @Test
    void multipart文件数超限时不读取内容() {
        SizedFile first = new SizedFile("a.json", 2, CONTENT);
        SizedFile second = new SizedFile("b.json", 2, CONTENT);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ApiRequests.documents(List.of(first, second), null, new UploadLimits(1, 1, 8, 16)));

        assertTrue(error.getMessage().contains("最多提交 1 个文件"), error.getMessage());
        assertEquals(0, first.reads);
        assertEquals(0, second.reads);
    }

    @Test
    void multipart读完后仍按实际大小核对() {
        SizedFile file = new SizedFile("changed.json", 2, new byte[9]);

        assertThrows(UploadLimits.TooLargeException.class,
                () -> ApiRequests.documents(List.of(file), null, new UploadLimits(1, 1, 8, 16)));

        assertEquals(1, file.reads);
    }

    @Test
    void multipart保留内容并把调用方的路径整理成文件名() {
        SizedFile file = new SizedFile("../../任免 表.json", 2, CONTENT);

        InputGroup group = ApiRequests.documents(List.of(file), " 示例任免表 ",
                new UploadLimits(1, 1, 8, 16)).get(0);

        assertEquals("示例任免表", group.name());
        assertEquals("任免_表.json", group.files().get(0).name());
        assertArrayEquals(CONTENT, group.files().get(0).content());
        assertEquals(1, file.reads);
    }

    @Test
    void JSON文件数在开始解码之前检查() {
        // 第一个文件的编码也有错；数量错误应先发现，不能开始处理前面的内容。
        JsonNode request = JSON.readTree("""
                {"documents":[{"files":[{"name":"a.json","content":"%%%"},
                                        {"name":"b.json","content":"e30="}]}]}
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ApiRequests.documents(request, new UploadLimits(1, 1, 8, 16)));

        assertTrue(error.getMessage().contains("最多提交 1 个文件"), error.getMessage());
    }

    @Test
    void JSON接受带换行或没有尾部填充的base64且按解码后的大小限制() {
        byte[] expected = "{\"标题\":\"示例😀\"}".getBytes(StandardCharsets.UTF_8);
        List<String> encoded = List.of(Base64.getEncoder().encodeToString(expected),
                Base64.getEncoder().withoutPadding().encodeToString(expected),
                Base64.getMimeEncoder(4, "\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(expected));

        for (String value : encoded) {
            JsonNode request = encoded(value);
            InputGroup group = ApiRequests.documents(request,
                    new UploadLimits(1, 1, expected.length, expected.length)).get(0);
            assertArrayEquals(expected, group.files().get(0).content());
            assertThrows(UploadLimits.TooLargeException.class,
                    () -> ApiRequests.documents(request, new UploadLimits(1, 1, expected.length - 1, expected.length)));
        }
    }

    @Test
    void JSON编码文件超限仍保留专用的大小异常() {
        JsonNode request = encoded(Base64.getEncoder().encodeToString(new byte[9]));

        UploadLimits.TooLargeException error = assertThrows(UploadLimits.TooLargeException.class,
                () -> ApiRequests.documents(request, new UploadLimits(1, 1, 8, 16)));

        assertTrue(error.getMessage().contains("有 9 字节"), error.getMessage());
    }

    @Test
    void JSON直接嵌入的中文按UTF8字节数检查() {
        JsonNode inline = JSON.readTree("{\"标题\":\"示例😀\"}");
        int bytes = inline.toString().getBytes(StandardCharsets.UTF_8).length;
        JsonNode request = JSON.valueToTree(Map.of("documents",
                List.of(Map.of("files", List.of(Map.of("name", "inline.json", "json", inline))))));

        InputGroup group = ApiRequests.documents(request, new UploadLimits(1, 1, bytes, bytes)).get(0);
        assertEquals(bytes, group.files().get(0).content().length);
        assertThrows(UploadLimits.TooLargeException.class,
                () -> ApiRequests.documents(request, new UploadLimits(1, 1, bytes - 1, bytes)));
    }

    @Test
    void JSON总量包括所有文档组() {
        JsonNode request = JSON.readTree("""
                {"documents":[{"files":[{"name":"a.json","content":"e30="}]},
                              {"files":[{"name":"b.json","content":"e30="}]}]}
                """);

        UploadLimits.TooLargeException error = assertThrows(UploadLimits.TooLargeException.class,
                () -> ApiRequests.documents(request, new UploadLimits(2, 2, 3, 3)));

        assertTrue(error.getMessage().contains("一共 4 字节"), error.getMessage());
    }

    @Test
    void JSON格式写错时抛出请求错误而不是内部错误() {
        List<String> invalid = List.of(
                "{\"documents\":[null]}",
                "{\"documents\":[{\"files\":[null]}]}",
                "{\"documents\":[{\"files\":[{\"content\":12}]}]}",
                "{\"documents\":[{\"files\":[{\"content\":\"%%\"}]}]}",
                "{\"documents\":[{\"files\":[{\"content\":\"e30=\",\"json\":{}}]}]}",
                "{\"documents\":[{\"files\":[{}]}]}",
                "{\"documents\":[{\"format\":\"txt\",\"files\":[{\"json\":{}}]}]}",
                "{\"documents\":[{\"files\":{}}]}");

        for (String value : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> ApiRequests.documents(JSON.readTree(value), new UploadLimits(2, 2, 64, 128)), value);
        }
    }

    private static JsonNode encoded(String content) {
        return JSON.valueToTree(Map.of("documents",
                List.of(Map.of("files", List.of(Map.of("name", "form.json", "content", content))))));
    }

    /** 只用很小的内容模拟大文件；reads 能确认限额是在访问内容之前生效的。 */
    private static final class SizedFile extends MockMultipartFile {
        private final long reportedSize;
        private final byte[] actualContent;
        private int reads;

        private SizedFile(String name, long reportedSize, byte[] content) {
            super("files", name, "application/json", content);
            this.reportedSize = reportedSize;
            this.actualContent = content;
        }

        @Override
        public long getSize() {
            return reportedSize;
        }

        @Override
        public byte[] getBytes() {
            reads++;
            return actualContent;
        }
    }
}
