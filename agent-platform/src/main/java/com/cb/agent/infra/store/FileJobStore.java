package com.cb.agent.infra.store;

import com.cb.agent.core.store.JobStore;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 把任务存在本机的一个目录里：
 * <pre>
 *   &lt;根目录&gt;/&lt;任务 ID&gt;/record.json      任务的记录
 *   &lt;根目录&gt;/&lt;任务 ID&gt;/files/…          任务的文件（输入、结果）
 * </pre>
 *
 * <p>这是数据库和对象存储定下来之前的实现，只适合单个实例：几个实例指向同一个目录时，
 * 没有任何机制防止它们同时处理同一个任务。
 *
 * <p><b>目录里是原始的文档内容和抽取结果</b>，人事材料含个人信息。目录放在哪、谁能读、保留多久，
 * 部署时要按材料的要求来定。
 *
 * <p>ID 和文件名都只接受很窄的一类写法，带路径分隔符、{@code ..} 的进不来，所以写不到根目录之外。
 *
 * <p>读写用的是老式的流（{@code FileInputStream}、{@code FileOutputStream}）而不是 {@code Files.write}：
 * 后者在线程被打断时会失败。取消一个任务靠的正是打断它的线程，打断之后还要把"已取消"这个状态写下来。
 */
public final class FileJobStore implements JobStore {

    private static final String RECORD = "record.json";
    private static final String FILES = "files";

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    /** 路径里的一段：字母（含中文）、数字、点、横线、下划线；不以点开头或结尾，所以不会是 {@code .} 或 {@code ..}。 */
    private static final Pattern SEGMENT = Pattern.compile(
            "[\\p{L}\\p{N}_-](?:[\\p{L}\\p{N}._-]{0,198}[\\p{L}\\p{N}_-])?");

    private final Path root;

    /**
     * @param root 存放任务的目录，不存在时自动建
     */
    public FileJobStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException("建不了存放任务的目录 " + this.root, e);
        }
    }

    /** 存放任务的目录（绝对路径）。 */
    public Path root() {
        return root;
    }

    @Override
    public List<String> ids() {
        try (Stream<Path> children = Files.list(root)) {
            List<String> out = new ArrayList<>();
            for (Path child : (Iterable<Path>) children::iterator) {
                String name = child.getFileName().toString();
                // 只认有记录的目录：建到一半就断掉的任务没有记录，不算
                if (ID.matcher(name).matches() && Files.isRegularFile(child.resolve(RECORD))) {
                    out.add(name);
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("读不了存放任务的目录 " + root, e);
        }
    }

    @Override
    public String readRecord(String id) {
        byte[] bytes = read(dir(id).resolve(RECORD));
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void writeRecord(String id, String json) {
        Path target = dir(id).resolve(RECORD);
        // 先写到旁边的临时文件再改名：进程在写的中途被杀掉时，原来的记录还是完整的
        Path temp = dir(id).resolve(RECORD + ".tmp");
        write(temp, json.getBytes(StandardCharsets.UTF_8));
        try {
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写不了任务 " + id + " 的记录", e);
        }
    }

    @Override
    public void writeFile(String id, String name, byte[] content) {
        write(file(id, name), content);
    }

    @Override
    public byte[] readFile(String id, String name) {
        return read(file(id, name));
    }

    @Override
    public void delete(String id) {
        Path dir = dir(id);
        if (!Files.exists(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    if (e != null) {
                        throw e;
                    }
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("删不掉任务 " + id + " 的目录", e);
        }
    }

    private Path dir(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("任务 ID 不对：" + id);
        }
        return root.resolve(id);
    }

    private Path file(String id, String name) {
        Path path = dir(id).resolve(FILES);
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("文件名是空的");
        }
        for (String segment : name.split("/", -1)) {
            if (!SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("文件名不对：" + name);
            }
            path = path.resolve(segment);
        }
        return path;
    }

    private static void write(Path path, byte[] content) {
        try {
            Files.createDirectories(path.getParent());
            try (FileOutputStream out = new FileOutputStream(path.toFile())) {
                out.write(content);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写不了 " + path, e);
        }
    }

    private static byte[] read(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(path.toFile())) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("读不了 " + path, e);
        }
    }
}
