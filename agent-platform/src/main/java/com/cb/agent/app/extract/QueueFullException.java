package com.cb.agent.app.extract;

/** 排队的文档已经到上限了，这一次提交没有被接受。过一会儿再提交。 */
public class QueueFullException extends RuntimeException {

    public QueueFullException(String message) {
        super(message);
    }
}
