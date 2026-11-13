package com.cb.agent.core.store;

import java.util.List;

/**
 * 任务的存储：每个任务一条记录，外加若干个文件（提交上来的输入、做出来的结果）。
 *
 * <p>为什么是接口：方案里任务最终要进数据库、文件进对象存储（第 3 节），用哪种数据库还没有定。
 * 现在的实现把它们放在本机的一个目录里（{@code infra.store.FileJobStore}）；定下来之后换一个实现，
 * 用它的代码不用改。
 *
 * <p>这里不知道任务是做什么的：记录是一段 JSON 文字，文件是一串字节。
 *
 * <p>实现要能被多个线程同时使用。同一个任务的写入由调用方保证不同时进行。
 */
public interface JobStore {

    /** 现有的全部任务的 ID，顺序不保证。 */
    List<String> ids();

    /**
     * 读一个任务的记录。
     *
     * @return 记录的 JSON 文字；没有这个任务时返回 null
     */
    String readRecord(String id);

    /** 写一个任务的记录，已有的被整个换掉。要么写成功，要么保持原样，不会留下写了一半的记录。 */
    void writeRecord(String id, String json);

    /**
     * 存一个属于这个任务的文件。
     *
     * @param name 文件在任务里的名字，用 {@code /} 分层，例如 {@code input/0/form.json}
     */
    void writeFile(String id, String name, byte[] content);

    /**
     * 读一个属于这个任务的文件。
     *
     * @return 文件的内容；没有这个文件时返回 null
     */
    byte[] readFile(String id, String name);

    /** 删掉一个任务：记录和它的全部文件。没有这个任务时什么都不做。 */
    void delete(String id);
}
