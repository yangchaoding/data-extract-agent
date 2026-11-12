package com.cb.agent.testsupport;

import com.cb.agent.capability.extract.prompt.Prompts;
import com.cb.agent.core.llm.LlmClient;
import com.cb.agent.core.llm.Usage;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 假模型：像一个靠谱的模型那样回复——按每个字段的正则，在提示词给出的片段里找，找到就连同原文一起返回。
 * 对应 Python 版 {@code tests/support.py} 的 {@code RuleLLM}。
 *
 * <p>用它测抽取流程时不用调真实的模型：回复是从提示词里现算出来的，所以切片、成批、提示词拼装一改，
 * 结果就跟着变；哪一步错了会直接反映到结果上。
 *
 * <p>规则：标量字段给一个正则，整个匹配是证据，第 1 个分组是值；
 * 列表字段给 {@code items(正则, 子字段名…)}，每个匹配是一项，各分组依次是子字段的值。
 * 某个字段不给规则就是"找不到"，回复里写 null。
 *
 * <p>它不统计用量（Python 版也不统计），所以用量都是 0。
 */
public final class RuleLlm implements LlmClient {

    /** 一个字段的找法。 */
    public record Rule(Pattern pattern, boolean list, List<String> subfields) {

        /** 标量字段：整个匹配是证据，第 1 个分组是值。 */
        public static Rule value(String regex) {
            return new Rule(Pattern.compile(regex, Pattern.DOTALL), false, List.of());
        }

        /** 列表字段：每个匹配是一项，各分组依次是子字段的值；不给子字段名时每一项的值是第 1 个分组。 */
        public static Rule items(String regex, String... subfields) {
            return new Rule(Pattern.compile(regex, Pattern.DOTALL), true, List.of(subfields));
        }
    }

    private static final Pattern CHUNK = Pattern.compile("<chunk id=\"(c\\d+)\"[^>]*>\n(.*?)\n</chunk>", Pattern.DOTALL);
    private static final Pattern ASKED = Pattern.compile("^- (\\w+)", Pattern.MULTILINE);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Map<String, Rule> rules;
    private final Map<String, List<String>> route;
    private final List<String[]> calls = new ArrayList<>();

    /**
     * @param rules 字段名 → 找法
     * @param route 定位请求的回复：字段名 → 片段编号
     */
    public RuleLlm(Map<String, Rule> rules, Map<String, List<String>> route) {
        this.rules = rules;
        this.route = route;
    }

    public RuleLlm(Map<String, Rule> rules) {
        this(rules, Map.of());
    }

    @Override
    public synchronized String complete(String system, String user) {
        calls.add(new String[]{system, user});
        if (system.equals(Prompts.ROUTE_SYSTEM)) {
            return JSON.writeValueAsString(route);
        }
        Map<String, String> chunks = shownChunks(user);
        String section = fieldsSection(user);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Rule> rule : rules.entrySet()) {
            // 只回答这一次问到的字段。没有规则的字段不出现在回复里，引擎会当成没找到
            if (asked(section, rule.getKey())) {
                out.put(rule.getKey(), answer(rule.getValue(), chunks));
            }
        }
        return JSON.writeValueAsString(out);
    }

    @Override
    public Usage usage() {
        return Usage.ZERO;
    }

    /** 收到的调用，每次是 {@code {系统提示词, 用户提示词}}。 */
    public List<String[]> calls() {
        return calls;
    }

    /** 只要求抽取的那几次调用（去掉定位）。 */
    public List<String> extractionPrompts() {
        List<String> out = new ArrayList<>();
        for (String[] call : calls) {
            if (!call[0].equals(Prompts.ROUTE_SYSTEM)) {
                out.add(call[1]);
            }
        }
        return out;
    }

    /** 提示词里给模型看的片段：编号 → 文字。 */
    public static Map<String, String> shownChunks(String prompt) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher matcher = CHUNK.matcher(prompt);
        while (matcher.find()) {
            out.put(matcher.group(1), matcher.group(2));
        }
        return out;
    }

    /** 提示词里"要抽取的字段"那一段。 */
    private static String fieldsSection(String prompt) {
        return prompt.split("## 要抽取的字段\n", 2)[1].split("\n\n## 文档片段", 2)[0];
    }

    /**
     * 这一次有没有问到这个字段。字段说明每个字段一行，顶格写着"- 字段名（中文名）：类型"；
     * 子字段的行是缩进的，不会被当成问到了。字段名可以是中文，也可以带括号（如"出生年月（岁）"）。
     */
    private static boolean asked(String section, String name) {
        return Pattern.compile("^- " + Pattern.quote(name) + "[：（]", Pattern.MULTILINE).matcher(section).find();
    }

    /** 提示词里要求抽取的字段名。只认英文的字段名，测试里检查"这次调用问了哪几个字段"时用。 */
    public static List<String> askedFields(String prompt) {
        String section = fieldsSection(prompt);
        List<String> out = new ArrayList<>();
        Matcher matcher = ASKED.matcher(section);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return out;
    }

    private static Object answer(Rule rule, Map<String, String> chunks) {
        if (rule == null) {
            return null;
        }
        if (!rule.list()) {
            for (Map.Entry<String, String> chunk : chunks.entrySet()) {
                Matcher matcher = rule.pattern().matcher(chunk.getValue());
                if (matcher.find()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("value", matcher.group(1));
                    item.put("evidence", matcher.group());
                    item.put("chunk", chunk.getKey());
                    return item;
                }
            }
            return null;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, String> chunk : chunks.entrySet()) {
            Matcher matcher = rule.pattern().matcher(chunk.getValue());
            while (matcher.find()) {
                Object value;
                if (rule.subfields().isEmpty()) {
                    value = matcher.group(1);
                } else {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 0; i < rule.subfields().size(); i++) {
                        row.put(rule.subfields().get(i), matcher.group(i + 1));
                    }
                    value = row;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("value", value);
                item.put("evidence", matcher.group());
                item.put("chunk", chunk.getKey());
                items.add(item);
            }
        }
        return items;
    }
}
