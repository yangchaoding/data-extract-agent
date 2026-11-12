package com.cb.agent.testsupport;

import com.cb.agent.core.llm.LlmClient;
import com.cb.agent.core.llm.OutputTruncatedException;
import com.cb.agent.core.llm.Usage;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 录播模型：按 {@code golden/extract.json} 里记下的回复回答。
 *
 * <p>基准文件里每个用例记着一串调用，每条是"发给模型的提示词 + 模型的回复"。
 * 这里按<b>提示词原文</b>去找对应的回复，而不是按顺序：Java 版并发跑调用时顺序可能不同，
 * 但每条提示词必须和 Python 版发出的逐字相同，否则找不到对应的回复，测试就会失败并给出对比。
 *
 * <p>回复里标了 {@code truncated} 的那几条，抛出"输出被截断"，用来测二分拆分的路径。
 * 每次调用记 11 个输入 token、5 个输出 token，和假模型服务相同，这样用量统计也能对上。
 */
public final class ReplayLlm implements LlmClient {

    /** 记下来的一次调用。 */
    public record Record(String kind, String user, String reply, boolean truncated) {
    }

    private final List<JsonNode> pending = new ArrayList<>();
    private final List<String> consumed = new ArrayList<>();
    private long calls;
    private long promptTokens;
    private long completionTokens;

    public ReplayLlm(List<JsonNode> recordedCalls) {
        pending.addAll(recordedCalls);
    }

    @Override
    public synchronized String complete(String system, String user) {
        String kind = expectKind(system);
        calls++;
        promptTokens += 11;
        completionTokens += 5;
        int at = indexOf(kind, user);
        if (at < 0) {
            throw new AssertionError(notFound(kind, user));
        }
        JsonNode record = pending.remove(at);
        consumed.add(record.get("kind").stringValue() + "\u0000" + record.get("user").stringValue());
        if (record.get("truncated").booleanValue()) {
            throw new OutputTruncatedException("截断");
        }
        return record.get("reply") == null ? "" : record.get("reply").stringValue();
    }

    @Override
    public Usage usage() {
        return new Usage(calls, promptTokens, completionTokens);
    }

    /** 实际消费掉的调用，按发生的顺序。用来检查串行跑时的调用顺序也和 Python 版相同。 */
    public synchronized List<String> consumedOrder() {
        return List.copyOf(consumed);
    }

    /** 还有没被用到的记录。调用顺序和内容都对上时，跑完应该一条不剩。 */
    public synchronized List<String> unused() {
        List<String> out = new ArrayList<>();
        for (JsonNode record : pending) {
            out.add(record.get("kind").stringValue() + "\u0000" + record.get("user").stringValue());
        }
        return out;
    }

    private int indexOf(String kind, String user) {
        for (int i = 0; i < pending.size(); i++) {
            JsonNode record = pending.get(i);
            if (record.get("kind").stringValue().equals(kind)
                    && record.get("user").stringValue().equals(user)) {
                return i;
            }
        }
        return -1;
    }

    /** 系统提示词只有两条，用它区分这次是抽取还是定位。 */
    private static String expectKind(String system) {
        if (system.equals(com.cb.agent.capability.extract.prompt.Prompts.SYSTEM)) {
            return "extract";
        }
        if (system.equals(com.cb.agent.capability.extract.prompt.Prompts.ROUTE_SYSTEM)) {
            return "route";
        }
        throw new AssertionError("系统提示词和 Python 版不一样：\n" + system);
    }

    /** 找不到对应的回复时，把期望的第一条和实际收到的摆在一起，方便看出改动了哪里。 */
    private String notFound(String kind, String user) {
        if (pending.isEmpty()) {
            return "Python 版没有记下这次调用，但它发来了：\n" + head(user);
        }
        JsonNode next = pending.get(0);
        return "这不是 Python 版记下的那条提示词。\n期望（" + next.get("kind").stringValue() + "）：\n"
                + head(next.get("user").stringValue()) + "\n实际（" + kind + "）：\n" + head(user);
    }

    private static String head(String text) {
        String firstLine = text.length() > 400 ? text.substring(0, 400) + "……" : text;
        return "长度 " + text.length() + "，开头是：\n" + firstLine;
    }
}
