package com.cb.agent.app.extract;

import com.cb.agent.core.llm.Usage;

/**
 * 计量：每份文档有了最终的状态时报一次，供外面汇总成指标（按 skill、按状态的份数、耗时、模型用量）。
 * 对应方案第 14 节的"计量"。
 *
 * <p>做成接口，是为了让任务服务不绑在某个指标库上：服务里接的是 Micrometer（见 {@code config.ServerConfig}），
 * 测试里用 {@link #NONE} 或者自己记下来检查。
 */
public interface ExtractionMeter {

    /** 什么都不记。 */
    ExtractionMeter NONE = (skill, status, elapsedMs, usage) -> {
    };

    /**
     * 一份文档有了最终的状态。
     *
     * @param skill     用的 skill；还没定下来就结束了（没能确定 skill、排队时被取消、读不了）时为 null
     * @param status    最终的状态：succeeded、failed、unrouted、canceled
     * @param elapsedMs 从开始处理到结束用了多少毫秒；没开始就结束了的是 0
     * @param usage     用了多少次模型调用、多少 token。失败和被取消的也算，花掉的就是花掉了
     */
    void documentFinished(String skill, String status, long elapsedMs, Usage usage);
}
