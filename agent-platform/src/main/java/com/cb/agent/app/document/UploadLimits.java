package com.cb.agent.app.document;

import java.util.List;

/**
 * 一次请求里能交来多少东西。对应方案第 16 节"文件本身也不可信"里的大小限制。
 *
 * <p>识别结果是 JSON，一页十几 KB，正常的请求离这些上限很远。设上限是为了挡住写错了的和恶意的请求，
 * 免得一个请求把内存或磁盘占满。
 *
 * @param maxDocuments    一次请求最多几组文件（一组是一份文档，或者同一个目录下的几份）
 * @param maxFiles        一次请求最多几个文件，各组加在一起
 * @param maxFileBytes    单个文件最大多少字节
 * @param maxRequestBytes 一次请求的全部文件加起来最大多少字节
 */
public record UploadLimits(int maxDocuments, int maxFiles, long maxFileBytes, long maxRequestBytes) {

    /** 交来的东西超过了大小上限。单独一种异常，是为了让 HTTP 接口回 413 而不是 400。 */
    public static final class TooLargeException extends IllegalArgumentException {
        public TooLargeException(String message) {
            super(message);
        }
    }

    public UploadLimits {
        if (maxDocuments < 1 || maxFiles < 1 || maxFileBytes < 1 || maxRequestBytes < 1) {
            throw new IllegalArgumentException("上传的各项上限（agent.api.max-*）都要大于 0");
        }
    }

    /**
     * @throws TooLargeException        某个文件或者整个请求太大
     * @throws IllegalArgumentException 没有文档、文档或文件太多、有空文件。消息里说明了是哪一项、上限是多少
     */
    public void check(List<InputGroup> groups) {
        checkDocumentCount(groups.size());
        long files = 0;
        long total = 0;
        for (InputGroup group : groups) {
            for (InputGroup.InputFile file : group.files()) {
                checkFileSize(file.name(), file.content().length);
                total = addBytes(total, file.content().length);
            }
            files += group.files().size();
        }
        checkFileCount(files);
    }

    /** 检查文档组数。接入层在读取各个文件前也使用这套规则。 */
    public void checkDocumentCount(int documents) {
        if (documents < 1) {
            throw new IllegalArgumentException("请求里没有文档");
        }
        if (documents > maxDocuments) {
            throw new IllegalArgumentException("一次最多提交 " + maxDocuments + " 份文档，现在是 " + documents
                    + " 份。分几次提交（上限是配置 agent.api.max-documents）");
        }
    }

    /** 检查文件总数，不需要先读取文件内容。 */
    public void checkFileCount(long files) {
        if (files > maxFiles) {
            throw new IllegalArgumentException("一次最多提交 " + maxFiles + " 个文件，现在是 " + files
                    + " 个。分几次提交（上限是配置 agent.api.max-files）");
        }
    }

    /** 检查单个文件的大小。上传时可以先传入容器提供的大小，读完后再核对实际字节数。 */
    public void checkFileSize(String name, long bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("文件 " + name + (bytes == 0 ? " 是空的" : " 的大小不合法"));
        }
        if (bytes > maxFileBytes) {
            throw new TooLargeException("文件 " + name + " 有 " + bytes
                    + " 字节，超过了单个文件的上限 " + maxFileBytes + " 字节（配置 agent.api.max-file-bytes）");
        }
    }

    /**
     * 把一个文件的大小计入总量，超限时直接拒绝。
     *
     * @param total 已计入的字节数
     * @param bytes 当前文件的字节数
     * @return 加上当前文件后的总量
     */
    public long addBytes(long total, long bytes) {
        // 先比较差值，避免把几个大文件相加时溢出后绕过总量限制。
        if (bytes > maxRequestBytes - total) {
            String amount = bytes > Long.MAX_VALUE - total ? "超过 " + Long.MAX_VALUE : String.valueOf(total + bytes);
            throw new TooLargeException("这次提交的文件一共 " + amount + " 字节，超过了一次请求的上限 "
                    + maxRequestBytes + " 字节（配置 agent.api.max-request-bytes）。分几次提交");
        }
        return total + bytes;
    }
}
