package com.cb.agent.app.document;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 调用方交来的一组文件，读出来是一份文档（也可能是几份）。
 *
 * <p>一组文件相当于命令行里给的一个文件或一个目录：PaddleOCR-VL 每页存一个 JSON 时，同一份文档的几页放在一组里；
 * 一组里放了几份文档的文件时，按识别时的原始文件名分开，规则和读目录相同。
 *
 * @param name  文档名；null 表示按文件里的信息取。只有这一组读出来正好是一份文档时才能指定
 * @param files 这一组里的文件，至少一个，名字不能重复
 */
public record InputGroup(String name, List<InputFile> files) {

    /** 文件名里允许的样子：字母（含中文）、数字、点、横线、下划线，不以点开头。存储那一层用的是同一个规则。 */
    private static final Pattern SAFE = Pattern.compile("[\\p{L}\\p{N}_-][\\p{L}\\p{N}._-]{0,149}");
    private static final Pattern UNSAFE_CHAR = Pattern.compile("[^\\p{L}\\p{N}._-]");
    private static final Pattern WINDOWS_DEVICE = Pattern.compile("(?i)CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]");

    public InputGroup {
        files = List.copyOf(files);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("一份文档至少要有一个文件");
        }
        Set<String> seen = new HashSet<>();
        for (InputFile file : files) {
            if (!seen.add(file.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("同一份文档里有两个文件都叫 " + file.name());
            }
        }
    }

    /**
     * 一个文件。
     *
     * @param name    文件名，必须是 {@link #safeName} 整理过的。它会被用作磁盘上的文件名，所以不能带路径
     * @param content 文件的内容
     */
    public record InputFile(String name, byte[] content) {

        public InputFile {
            if (name == null || !SAFE.matcher(name).matches()) {
                throw new IllegalArgumentException("文件名不对：" + name);
            }
            if (!name.toLowerCase(Locale.ROOT).endsWith(".json")) {
                throw new IllegalArgumentException("文件 " + name + " 不是 .json。现在只能读 PaddleOCR-VL 的识别结果（JSON）");
            }
        }
    }

    /**
     * 把调用方给的文件名整理成可以放心用的：只留最后一段（去掉目录），不认识的字符换成下划线。
     * 浏览器上传时文件名可能带着完整的路径，也可能有人故意写成 {@code ../../x}。
     *
     * @param raw      调用方给的名字；可以为 null
     * @param fallback 没给名字时用的
     */
    public static String safeName(String raw, String fallback) {
        String name = raw == null ? "" : raw.strip();
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = UNSAFE_CHAR.matcher(name.substring(cut + 1)).replaceAll("_");
        if (name.startsWith(".")) {
            name = "_" + name.substring(1);
        }
        if (name.length() > 149) {
            name = name.substring(name.length() - 149); // 留后面的：页码和扩展名在后面
            if (name.startsWith(".")) {
                name = "_" + name.substring(1);
            }
        }
        // Windows 上 NUL、CON、COM1 这些名字（带不带扩展名都算）指的是设备，不是文件，往里写不会报错，内容却没了
        int dot = name.indexOf('.');
        if (WINDOWS_DEVICE.matcher(dot < 0 ? name : name.substring(0, dot)).matches()) {
            name = "_" + name;
        }
        return name.isEmpty() ? fallback : name;
    }

    /** 这一组文件一共多少字节。 */
    public long bytes() {
        long sum = 0;
        for (InputFile file : files) {
            sum += file.content().length;
        }
        return sum;
    }
}
