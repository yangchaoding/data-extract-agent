package com.cb.agent.infra.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 把任务存在本机目录里：存取、删除，以及 ID 和文件名写得再怪也出不了这个目录。
 */
class FileJobStoreTest {

    @TempDir
    Path dir;

    @Test
    void 记录和文件的存取() throws IOException {
        FileJobStore store = new FileJobStore(dir.resolve("tasks"));

        store.writeRecord("ex1", "{\"status\": \"排队\"}");
        store.writeFile("ex1", "input/0/第1页.json", "页面".getBytes(StandardCharsets.UTF_8));
        store.writeFile("ex1", "result/0.json", new byte[]{1, 2, 3});

        assertEquals("{\"status\": \"排队\"}", store.readRecord("ex1"));
        assertEquals("页面", new String(store.readFile("ex1", "input/0/第1页.json"), StandardCharsets.UTF_8));
        assertArrayEquals(new byte[]{1, 2, 3}, store.readFile("ex1", "result/0.json"));
        assertEquals(List.of("ex1"), store.ids());
        // 磁盘上的样子
        Path root = dir.resolve("tasks").toAbsolutePath().normalize();
        assertEquals(root, store.root());
        assertTrue(Files.isRegularFile(root.resolve("ex1/record.json")));
        assertTrue(Files.isRegularFile(root.resolve("ex1/files/input/0/第1页.json")));
        // 记录重写之后是新的内容，旁边不留临时文件
        store.writeRecord("ex1", "{\"status\": \"成功\"}");
        assertEquals("{\"status\": \"成功\"}", store.readRecord("ex1"));
        try (Stream<Path> children = Files.list(root.resolve("ex1"))) {
            assertEquals(List.of("files", "record.json"),
                    children.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void 没有的东西读出来是null() {
        FileJobStore store = new FileJobStore(dir);

        assertNull(store.readRecord("ex404"));
        assertNull(store.readFile("ex404", "result/0.json"));
        store.writeRecord("ex1", "{}");
        assertNull(store.readFile("ex1", "result/0.json"));
        assertNull(store.readFile("ex1", "result"), "目录不是文件");
    }

    @Test
    void 只有带着记录的目录才算任务() throws IOException {
        FileJobStore store = new FileJobStore(dir);
        store.writeRecord("ex1", "{}");
        store.writeFile("ex2", "input/0/a.json", new byte[]{1}); // 建到一半就断了：有文件，没有记录
        Files.createDirectories(dir.resolve(".git"));               // 别的东西
        Files.writeString(dir.resolve("notes.txt"), "x");

        assertEquals(List.of("ex1"), store.ids());
    }

    @Test
    void 删除一个任务_连同它的全部文件() {
        FileJobStore store = new FileJobStore(dir);
        store.writeRecord("ex1", "{}");
        store.writeFile("ex1", "input/0/a.json", new byte[]{1});
        store.writeFile("ex1", "result/0.json", new byte[]{2});
        store.writeRecord("ex2", "{}");

        store.delete("ex1");
        store.delete("ex1"); // 已经没有了：不报错
        store.delete("ex404");

        assertFalse(Files.exists(dir.resolve("ex1")));
        assertEquals(List.of("ex2"), store.ids());
    }

    @Test
    void ID和文件名写得再怪也出不了存储的目录() throws IOException {
        FileJobStore store = new FileJobStore(dir.resolve("tasks"));
        Files.writeString(dir.resolve("secret.txt"), "别的文件");

        for (String id : new String[]{"..", "../x", "a/b", "a\\b", "", ".hidden", "ex 1", "C:x", null}) {
            assertEquals("任务 ID 不对：" + id, assertThrows(IllegalArgumentException.class,
                    () -> store.readRecord(id)).getMessage());
            assertThrows(IllegalArgumentException.class, () -> store.writeRecord(id, "{}"));
            assertThrows(IllegalArgumentException.class, () -> store.delete(id));
        }
        for (String name : new String[]{"../secret.txt", "../../secret.txt", "input/../../../secret.txt", "/etc/passwd",
                "input//a.json", "input/", "", ".", "..", "a\\..\\b", "C:\\x.json", "input/.hidden", "a b.json",
                "input/a.json.", null}) {
            assertThrows(IllegalArgumentException.class, () -> store.readFile("ex1", name), "读 " + name);
            assertThrows(IllegalArgumentException.class, () -> store.writeFile("ex1", name, new byte[]{1}), "写 " + name);
        }

        assertEquals("别的文件", Files.readString(dir.resolve("secret.txt")));
        try (Stream<Path> children = Files.list(dir)) {
            assertEquals(List.of("secret.txt", "tasks"), children.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void 线程被打断之后还能读写() {
        // 取消任务靠打断线程；打断之后还要把"已取消"写下来
        FileJobStore store = new FileJobStore(dir);
        Thread.currentThread().interrupt();
        try {
            store.writeRecord("ex1", "{\"status\": \"canceled\"}");
            store.writeFile("ex1", "result/0.json", new byte[]{9});

            assertEquals("{\"status\": \"canceled\"}", store.readRecord("ex1"));
            assertArrayEquals(new byte[]{9}, store.readFile("ex1", "result/0.json"));
            assertTrue(Thread.currentThread().isInterrupted(), "存储不吞掉打断标记");
        } finally {
            Thread.interrupted();
        }
    }
}
