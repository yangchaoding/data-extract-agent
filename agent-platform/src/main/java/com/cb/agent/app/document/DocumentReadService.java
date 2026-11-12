package com.cb.agent.app.document;

import com.cb.agent.capability.document.DocumentDump;
import com.cb.agent.capability.document.DocumentLoader;
import com.cb.agent.capability.document.chunk.Chunk;
import com.cb.agent.capability.document.chunk.Chunker;
import com.cb.agent.capability.document.model.Document;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "读取文档"这个用例。
 *
 * <p>接入层（命令行，以后还有 REST 接口）只调用这里，不直接碰读取器。
 * 现在它只是转发，等加上"上传的文件先落盘""读过的文档存起来以后按 ID 取用"这些事情时，都加在这一层。
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
