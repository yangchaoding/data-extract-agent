package com.cb.agent.app.extract;

import com.cb.agent.capability.document.model.Document;
import com.cb.agent.capability.extract.engine.BatchRunner;
import com.cb.agent.capability.extract.engine.EngineOptions;
import com.cb.agent.capability.extract.engine.ExtractionEngine;
import com.cb.agent.capability.extract.engine.ExtractionResult;
import com.cb.agent.capability.extract.schema.Schema;
import com.cb.agent.core.llm.LlmClient;

/**
 * 一次准备好的抽取：用哪套字段定义、哪些引擎参数、哪个模型，都已经定下来了。
 *
 * <p>准备和执行分开，是因为准备阶段的错误（skill 找不到、字段定义写错、模型接口地址不让连）
 * 应该在处理第一份文档之前就报出来，而不是每份文档报一遍。准备好之后可以用来抽取任意多份文档，
 * 它们共用同一个并发上限。
 */
public final class ExtractJob {

    private final String skill;
    private final Schema schema;
    private final EngineOptions options;
    private final ExtractionEngine engine;

    ExtractJob(String skill, Schema schema, EngineOptions options, LlmClient llm) {
        this.skill = skill;
        this.schema = schema;
        this.options = options;
        // 几份文档共用一个闸门：同时进行的模型调用数不会随着文档数成倍增加
        this.engine = new ExtractionEngine(llm, options, new BatchRunner(options.workers()));
    }

    /** 用的是哪个 skill；直接给字段定义文件时为 null。 */
    public String skill() {
        return skill;
    }

    public Schema schema() {
        return schema;
    }

    /** 实际生效的引擎参数：默认值、skill 清单里写的、调用方临时改的，三层合在一起。 */
    public EngineOptions options() {
        return options;
    }

    /**
     * 从一份文档里抽取。
     *
     * @throws com.cb.agent.core.llm.LlmCallException 模型调用失败
     */
    public ExtractionResult run(Document doc) {
        return engine.run(doc, schema);
    }
}
