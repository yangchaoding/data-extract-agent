package com.cb.agent.app.extract;

/**
 * 请求和任务现在的状况冲突，做不了：同一个幂等键提交了不同的内容；任务还没结束就要删它；
 * 文档还没有结果就来取结果。消息里说明了是哪一种、该怎么办。
 */
public class TaskConflictException extends RuntimeException {

    public TaskConflictException(String message) {
        super(message);
    }
}
