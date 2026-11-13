package com.cb.agent.app.document;

import com.cb.agent.capability.document.DocumentDump;
import com.cb.agent.capability.document.DocumentLoader;
import com.cb.agent.capability.document.chunk.Chunk;
import com.cb.agent.capability.document.chunk.Chunker;
import com.cb.agent.capability.document.model.Document;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "读取文档"这个用例。
 *
 * <p>接入层（命令行、HTTP 接口）只调用这里，不直接碰读取器。
 * 文件已经在磁盘上时用 {@link #read(List)}；内容是调用方传上来的时用 {@link #read(InputGroup)}。
 */
@Service
public class DocumentReadService {

    private final DocumentLoader loader;

    public DocumentReadService(DocumentLoader loader) {
        this.loader = loader;
    }

    /**
     * 读若干个文件或目录。
     *
     * @param paths 每一项是一个文件，或者一个放着多个文件的目录
     * @return 读出的全部文档，按给出的顺序
     * @throws IllegalArgumentException 某个路径读不了。出错时前面已经读成功的也不返回，
     *                                  免得调用方拿着不完整的结果继续往下做
     */
    public List<Document> read(List<Path> paths) {
        List<Document> docs = new ArrayList<>();
        for (Path path : paths) {
            docs.addAll(loader.load(path));
        }
        return docs;
    }

    /**
     * 读调用方传上来的一组文件。
     *
     * <p>做法是把它们放进一个临时目录，按读文件、读目录的老办法读，读完就删掉。这样上传的和磁盘上的走同一套读取逻辑，
     * 结果一定相同：只有一个文件时等于读这个文件，有几个时等于读放着它们的目录（同一份文档的几页合成一份）。
     *
     * @return 读出的文档，至少一份。{@link Document#source()} 是文件名，不是临时目录
     * @throws IllegalArgumentException 某个文件不是认识的格式；或者指定了文档名，读出来却不止一份
     */
    public List<Document> read(InputGroup group) {
        Path dir;
        try {
            dir = Files.createTempDirectory("agent-upload-");
        } catch (IOException e) {
            throw new UncheckedIOException("建不了临时目录", e);
        }
        try {
            List<Path> paths = new ArrayList<>();
            for (InputGroup.InputFile file : group.files()) {
                Path path = dir.resolve(file.name());
                // 用流来写，不用 Files.write：任务被取消时线程会被打断，后者在被打断的线程上会直接失败
                try (FileOutputStream out = new FileOutputStream(path.toFile())) {
                    out.write(file.content());
                }
                paths.add(path);
            }
            List<Document> docs;
            if (paths.size() == 1) {
                docs = loadFile(paths.get(0));
            } else {
                // 读目录时不是识别结果的文件会被悄悄跳过（目录里可能有别的东西）。
                // 传上来的每个文件都是调用方有意给的，哪一个读不了要说出来，不能让一页就这么不见了
                for (Path path : paths) {
                    loadFile(path);
                }
                docs = loader.load(dir);
            }
            return renamed(group, docs);
        } catch (IllegalArgumentException e) {
            // 读取器的报错里带着临时目录的路径，对调用方没有意义，去掉
            String message = String.valueOf(e.getMessage())
                    .replace(dir + File.separator, "").replace(dir.toString(), "这一组文件");
            throw new IllegalArgumentException(message, e);
        } catch (IOException e) {
            throw new UncheckedIOException("写不了临时文件", e);
        } finally {
            deleteQuietly(dir);
        }
    }

    /** 读一个传上来的文件。读不了时说明是哪一个文件：一次传了几个的话，光说"不是识别结果"查不出是谁。 */
    private List<Document> loadFile(Path path) {
        try {
            return loader.load(path);
        } catch (IllegalArgumentException e) {
            String name = path.getFileName().toString();
            String message = String.valueOf(e.getMessage());
            throw new IllegalArgumentException(message.contains(name) ? message : "文件 " + name + "：" + message, e);
        }
    }

    private static List<Document> renamed(InputGroup group, List<Document> docs) {
        if (group.name() != null && docs.size() > 1) {
            throw new IllegalArgumentException("给这一组文件指定了文档名 " + group.name() + "，但它们读出来是 "
                    + docs.size() + " 份文档（" + String.join("、", docs.stream().map(Document::name).toList())
                    + "）。分成几组提交，或者不指定文档名");
        }
        String source = String.join(", ", group.files().stream().map(InputGroup.InputFile::name).toList());
        List<Document> out = new ArrayList<>();
        for (Document doc : docs) {
            out.add(new Document(group.name() != null ? group.name() : doc.name(), source, doc.blocks(),
                    doc.title(), doc.pageCount(), doc.pages(), doc.report()));
        }
        return out;
    }

    /** 删掉临时目录。里面是文档的原文，不能留着；删不掉时也不让读取因此失败。 */
    private static void deleteQuietly(Path dir) {
        File[] children = dir.toFile().listFiles();
        if (children != null) {
            for (File child : children) {
                child.delete();
            }
        }
        dir.toFile().delete();
    }

    /** 把文档导出成可以直接序列化成 JSON 的结构。 */
    public Map<String, Object> dump(Document doc) {
        return DocumentDump.toMap(doc);
    }

    /**
     * 把文档切成片。
     *
     * @param chunkTokens   每片的大小上限（估算的 token 数）
     * @param contextTokens 整篇文档不超过这个大小时不切，全文就是一片
     */
    public List<Chunk> chunk(Document doc, int chunkTokens, int contextTokens) {
        return Chunker.chunk(doc, chunkTokens, contextTokens);
    }
}
