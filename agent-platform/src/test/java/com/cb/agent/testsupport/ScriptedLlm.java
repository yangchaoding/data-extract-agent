package com.cb.agent.testsupport;

import com.cb.agent.core.llm.LlmClient;
import com.cb.agent.core.llm.Usage;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 假模型：按顺序给出预先写好的回复，最后一条会一直重复。
 * 对应 Python 版 {@code tests/support.py} 的 {@code ScriptedLLM}。
 *
 * <p>用来测重问、回复不成形这些按顺序发生的路径：模型什么时候答错、错在哪里，都由脚本说了算。
 */
public final class ScriptedLlm implements LlmClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<Object> replies;
    private final List<String[]> calls = new ArrayList<>();

    /**
     * @param replies 一条条回复。是字符串就原样发出，是 Map、List 这类对象就先写成 JSON
     */
    public ScriptedLlm(List<Object> replies) {
        this.replies = new ArrayList<>(replies);
    }

    @Override
    public synchronized String complete(String system, String user) {
        calls.add(new String[]{system, user});
        Object reply = replies.size() > 1 ? replies.remove(0) : replies.get(0);
        return reply instanceof String text ? text : JSON.writeValueAsString(reply);
    }

    @Override
    public Usage usage() {
        return Usage.ZERO;
    }

    public List<String[]> calls() {
        return calls;
    }

    /** 第几次调用的用户提示词。 */
    public String prompt(int index) {
        return calls.get(index)[1];
    }
}
