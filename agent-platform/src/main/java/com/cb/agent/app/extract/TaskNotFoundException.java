package com.cb.agent.app.extract;

/** 没有要找的东西：任务的 ID 写错了，任务已经被删掉了，或者任务里没有这个序号的文档。 */
public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(String message) {
        super(message);
    }
}
