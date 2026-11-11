package com.cb.agent.capability.document.model;

import java.util.List;

/**
 * 读取报告：这份文档是怎么读进来的，过程中有哪些要注意的情况。
 *
 * <p>读取时做的每一处"自作主张"都要记在这里，例如把一个图题当成了文档标题、
 * 把两页的表格拼成了一张。使用者看到结果不对劲时，先看这里。
 *
 * @param reader   用的是哪个读取器，例如 {@code paddle-ocr-vl-json}
 * @param warnings 要注意的情况，每条一句话
 */
public record IngestReport(String reader, List<String> warnings) {

    public IngestReport {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
