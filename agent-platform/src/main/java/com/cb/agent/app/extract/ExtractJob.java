package com.cb.agent.app.extract;

import com.cb.agent.capability.document.model.Document;
import com.cb.agent.capability.extract.engine.BatchRunner;
import com.cb.agent.capability.extract.engine.EngineOptions;
import com.cb.agent.capability.extract.engine.ExtractionEngine;
import com.cb.agent.capability.extract.engine.ExtractionResult;
import com.cb.agent.capability.extract.schema.Schema;
import com.cb.agent.core.llm.LlmClient;
import com.cb.agent.core.llm.LlmGate;
import com.cb.agent.core.llm.Usage;

/**
 * 一次准备好的抽取：用哪套字段定义、哪些引擎参数、哪个模型，都已经定下来了。
 *
 * <p>准备和执行分开，是因为准备阶段的错误（skill 找不到、字段定义写错、模型接口地址不让连）
 * 应该在处理第一份文档之前就报出来，而不是每份文档报一遍。准备好之后可以用来抽取任意多份文档，
 * 它们共用同一个并发上限。
 *
 * <p>名字里的 job 指"一次准备好的抽取"，不是 HTTP 接口里提交的异步任务，那个是 {@link ExtractionTask}。
 */
public final class ExtractJob {

    private final String skill;
    private final Integer skillVersion;
    private final Schema schema;
    private final EngineOptions options;
    private final LlmClient llm;
    private final BatchRunner runner;

    ExtractJob(String skill, Integer skillVersion, Schema schema, EngineOptions options, LlmClient llm) {
        // 几份文档共用一个闸门：同时进行的模型调用数不会随着文档数成倍增加
        this(skill, skillVersion, schema, options, llm, new BatchRunner(options.workers()));
    }

    private ExtractJob(String skill, Integer skillVersion, Schema schema, EngineOptions options, LlmClient llm,
                       BatchRunner runner) {
        this.skill = skill;
        this.skillVersion = skillVersion;
        this.schema = schema;
        this.options = options;
        this.llm = llm;
        this.runner = runner;
    }

    /** 用的是哪个 skill；直接给字段定义文件时为 null。 */
    public String skill() {
        return skill;
    }

    /** 用的 skill 是第几版；直接给字段定义文件时为 null。 */
    public Integer skillVersion() {
        return skillVersion;
    }

    public Schema schema() {
        return schema;
    }

    /** 实际生效的引擎参数：默认值、skill 清单里写的、调用方临时改的，三层合在一起。 */
    public EngineOptions options() {
        return options;
    }

    /**
     * 同样的抽取，但模型调用另外受一道全局闸门限制。服务里几个任务同时跑时用它，
     * 这样每个任务自己的并发上限之外，所有任务加起来也有一个上限。
     */
    public ExtractJob limitedBy(LlmGate gate) {
        return new ExtractJob(skill, skillVersion, schema, options, gate.limit(llm), runner);
    }

    /**
     * 从一份文档里抽取。可以被几个线程同时调用。
     *
     * @throws com.cb.agent.core.llm.LlmCallException 模型调用失败
     */
    public ExtractionResult run(Document doc) {
        return newRun().extract(doc);
    }

    /** 准备抽一份文档。和直接调 {@link #run(Document)} 的区别：抽取失败、被打断时，也查得到已经花掉的用量。 */
    public Run newRun() {
        return new Run();
    }

    /** 一份文档的一次抽取。只用一次。 */
    public final class Run {

        // 每份文档拿一个单独计量的视图：同一个模型客户端可能同时在给别的文档用，
        // 直接拿累计用量前后相减的话，别的文档的调用会算到这一份头上
        private final LlmClient metered = llm.metered();

        private Run() {
        }

        /**
         * @throws com.cb.agent.core.llm.LlmCallException 模型调用失败
         */
        public ExtractionResult extract(Document doc) {
            return new ExtractionEngine(metered, options, runner).run(doc, schema);
        }

        /** 这一次抽取到目前为止的模型用量。抽取没做完（失败、被打断）时，是已经花掉的那部分。 */
        public Usage usage() {
            return metered.usage();
        }
    }
}
