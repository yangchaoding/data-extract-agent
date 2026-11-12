package com.cb.agent.capability.extract.format;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 按 Python 的规则把值格式化成文字：{@code repr()}、{@code str()}、{@code json.dumps()}。
 *
 * <p>为什么需要它：提示词和重问时的反馈里，有不少内容是 Python 版用这几个函数格式化出来的，例如
 * <pre>
 *   '张三' 不是数字                         ← repr()
 *   你的回答是 {"value": 1280000, …}        ← json.dumps()
 * </pre>
 * 这些文字原样发给模型。Java 自带的格式化和它们不一样（字符串不带引号、JSON 的冒号前面多一个空格、
 * 大的小数写成 1.28E7），用自带的格式化，模型看到的提示词就和 Python 版不同了，差分测试也对不上。
 *
 * <p>支持的值：字符串、整数、小数、布尔、null、List、Map（键是字符串）。
 */
public final class PyFormat {

    private PyFormat() {
    }

    // ---- str() 和 repr() ----------------------------------------------------------------

    /** Python 的 {@code str(value)}：字符串原样返回，其他的和 {@link #repr} 相同。 */
    public static String str(Object value) {
        return value instanceof String s ? s : repr(value);
    }

    /**
     * Python 的 {@code repr(value)}：字符串带引号，{@code None}、{@code True}、{@code False} 首字母大写。
     */
    public static String repr(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof String s) {
            return reprString(s);
        }
        if (value instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (value instanceof Double || value instanceof Float) {
            return floatRepr(((Number) value).doubleValue());
        }
        if (value instanceof BigDecimal d) {
            return d.toPlainString();
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                parts.add(repr(item));
            }
            return "[" + String.join(", ", parts) + "]";
        }
        if (value instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                parts.add(repr(e.getKey()) + ": " + repr(e.getValue()));
            }
            return "{" + String.join(", ", parts) + "}";
        }
        return value.toString();
    }

    /**
     * 字符串的 {@code repr()}：默认用单引号；字符串里有单引号而没有双引号时改用双引号。
     * 反斜杠、换行这些转义掉，看不见的字符写成 {@code \xNN}、{@code \}{@code uNNNN}。
     */
    private static String reprString(String s) {
        char quote = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder out = new StringBuilder(s.length() + 2).append(quote);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == quote || cp == '\\') {
                out.append('\\').appendCodePoint(cp);
            } else if (cp == '\n') {
                out.append("\\n");
            } else if (cp == '\r') {
                out.append("\\r");
            } else if (cp == '\t') {
                out.append("\\t");
            } else if (!isPrintable(cp)) {
                if (cp <= 0xFF) {
                    out.append(String.format("\\x%02x", cp));
                } else if (cp <= 0xFFFF) {
                    out.append(String.format("\\u%04x", cp));
                } else {
                    out.append(String.format("\\U%08x", cp));
                }
            } else {
                out.appendCodePoint(cp);
            }
        }
        return out.append(quote).toString();
    }

    /** 和 Python 的 {@code str.isprintable()} 相同的判断：控制字符、格式字符、除普通空格以外的分隔符都不算。 */
    private static boolean isPrintable(int cp) {
        if (cp == ' ') {
            return true;
        }
        return switch (Character.getType(cp)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
                 Character.UNASSIGNED, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                 Character.SPACE_SEPARATOR -> false;
            default -> true;
        };
    }

    /**
     * 小数的 {@code repr()}。
     *
     * <p>Java 把 12800000.0 写成 {@code 1.28E7}，Python 写成 {@code 12800000.0}。两边用的有效数字相同，
     * 只是什么时候改用科学记数法不同：Python 是指数小于 -4 或者不小于 16 时才用。
     */
    public static String floatRepr(double d) {
        if (Double.isNaN(d)) {
            return "nan";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "inf" : "-inf";
        }
        if (d == 0) {
            return (1 / d < 0) ? "-0.0" : "0.0";
        }
        BigDecimal exact = new BigDecimal(Double.toString(d)); // Double.toString 给出的是最短的有效数字
        int exponent = exact.precision() - exact.scale() - 1;   // 写成 a.bcd × 10^exponent 时的指数
        if (exponent < -4 || exponent >= 16) {
            String digits = exact.unscaledValue().abs().toString().replaceFirst("0+$", "");
            if (digits.isEmpty()) {
                digits = "0";
            }
            String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            String sign = d < 0 ? "-" : "";
            return sign + mantissa + "e" + (exponent < 0 ? "-" : "+") + String.format("%02d", Math.abs(exponent));
        }
        String plain = exact.stripTrailingZeros().toPlainString();
        return plain.contains(".") ? plain : plain + ".0";
    }

    // ---- json.dumps() -------------------------------------------------------------------

    /** {@code json.dumps(value, ensure_ascii=False)}：写在一行里，逗号和冒号后面各有一个空格。 */
    public static String json(Object value) {
        StringBuilder out = new StringBuilder();
        writeJson(out, value, null, 0, false);
        return out.toString();
    }

    /** {@code json.dumps(value, ensure_ascii=False, sort_keys=True)}：键按字典序排列。 */
    public static String jsonSorted(Object value) {
        StringBuilder out = new StringBuilder();
        writeJson(out, value, null, 0, true);
        return out.toString();
    }

    /** {@code json.dumps(value, ensure_ascii=False, indent=n)}：每一层缩进 n 个空格。 */
    public static String jsonIndented(Object value, int indent) {
        StringBuilder out = new StringBuilder();
        writeJson(out, value, indent, 0, false);
        return out.toString();
    }

    private static void writeJson(StringBuilder out, Object value, Integer indent, int depth, boolean sortKeys) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            writeJsonString(out, s);
        } else if (value instanceof Boolean b) {
            out.append(b ? "true" : "false");
        } else if (value instanceof Double || value instanceof Float) {
            String r = floatRepr(((Number) value).doubleValue());
            // json.dumps 里这三个特殊值的写法和 repr 不同
            out.append(switch (r) {
                case "nan" -> "NaN";
                case "inf" -> "Infinity";
                case "-inf" -> "-Infinity";
                default -> r;
            });
        } else if (value instanceof Number) {
            out.append(value instanceof BigDecimal d ? d.toPlainString() : value.toString());
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                separator(out, indent, depth + 1, i > 0);
                writeJson(out, list.get(i), indent, depth + 1, sortKeys);
            }
            closing(out, indent, depth);
            out.append(']');
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            Map<?, ?> ordered = sortKeys ? new TreeMap<Object, Object>(map) : map;
            out.append('{');
            int i = 0;
            for (Map.Entry<?, ?> e : ordered.entrySet()) {
                separator(out, indent, depth + 1, i++ > 0);
                writeJsonString(out, String.valueOf(e.getKey()));
                out.append(": ");
                writeJson(out, e.getValue(), indent, depth + 1, sortKeys);
            }
            closing(out, indent, depth);
            out.append('}');
        } else {
            // Python 版传了 default=str：不认识的类型按 str() 写成字符串
            writeJsonString(out, value.toString());
        }
    }

    /** 两个元素之间：不缩进时是逗号加空格；缩进时是逗号、换行、缩进。 */
    private static void separator(StringBuilder out, Integer indent, int depth, boolean notFirst) {
        if (indent == null) {
            if (notFirst) {
                out.append(", ");
            }
        } else {
            if (notFirst) {
                out.append(',');
            }
            out.append('\n').append(" ".repeat(indent * depth));
        }
    }

    private static void closing(StringBuilder out, Integer indent, int depth) {
        if (indent != null) {
            out.append('\n').append(" ".repeat(indent * depth));
        }
    }

    /** JSON 字符串。{@code ensure_ascii=False}：中文原样输出，只转义引号、反斜杠和控制字符。 */
    private static void writeJsonString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        out.append('"');
    }
}
