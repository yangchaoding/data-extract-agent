package com.cb.agent.capability.extract.value;

import com.cb.agent.capability.document.text.TextNorm;
import com.cb.agent.capability.extract.format.PyFormat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日期：把各种写法归一成 {@code YYYY-MM-DD}，并核对它确实写在证据里。
 *
 * <p>对应 Python 版 {@code values.py} 的"日期"部分。
 *
 * <p>核对的要点是<b>年、月、日必须属于同一个日期</b>。证据里有两个日期时，不能从一个日期里取年份、
 * 从另一个里取月日，凑出一个原文里没有的日期。
 */
public final class Dates {

    private Dates() {
    }

    private static final int UCC = Pattern.UNICODE_CHARACTER_CLASS;

    /** 年月日，日可以没有：2024-03-18、2024年3月18日、2024.3、2024年3月。 */
    private static final Pattern YMD =
            Pattern.compile("(?<!\\d)(\\d{4})\\s*[-/.年]\\s*(\\d{1,2})(?:\\s*[-/.月]\\s*(\\d{1,2}))?(?!\\d)", UCC);
    /** 八位数字连写：20240318。 */
    private static final Pattern COMPACT = Pattern.compile("(?<!\\d)(\\d{4})(\\d{2})(\\d{2})(?!\\d)", UCC);
    /** 年份在最后：18/03/2024 或 03/18/2024。 */
    private static final Pattern DMY = Pattern.compile("(?<!\\d)(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})(?!\\d)", UCC);
    /** 只有月和日：3月18日。 */
    private static final Pattern MONTH_DAY = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]", UCC);
    private static final Pattern ISO = Pattern.compile("\\d{4}(?:-\\d{2}){0,2}", UCC);
    private static final Pattern YEAR_ONLY = Pattern.compile("\\d{4}年?", UCC);
    private static final String[] EN_MONTHS =
            {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};

    /**
     * 拼成标准写法。月份不在 1 到 12、日不在 1 到 31 的不是日期。对应 {@code _format_date}。
     *
     * @param day 可以为 null，表示只有年月
     * @return {@code YYYY-MM-DD} 或 {@code YYYY-MM}；不是合法的日期时返回 null
     */
    private static String format(String year, String month, String day) {
        int y = Integer.parseInt(year);
        int m = Integer.parseInt(month);
        if (m < 1 || m > 12) {
            return null;
        }
        if (day == null) {
            return String.format(Locale.ROOT, "%04d-%02d", y, m);
        }
        int d = Integer.parseInt(day);
        if (d < 1 || d > 31) {
            return null;
        }
        return String.format(Locale.ROOT, "%04d-%02d-%02d", y, m, d);
    }

    /**
     * 把模型给的日期归一化。对应 {@code parse_date}。
     *
     * @return {@code YYYY-MM-DD}；只有年月时是 {@code YYYY-MM}；只有年份时是 {@code YYYY}；读不出来返回 null
     */
    public static String parse(Object raw) {
        String s = TextNorm.strip(Numbers.arabicNumerals(PyFormat.str(raw)));
        if (YEAR_ONLY.matcher(s).matches()) {
            return s.substring(0, 4);
        }
        Matcher compact = COMPACT.matcher(s);
        if (compact.matches()) {
            return format(compact.group(1), compact.group(2), compact.group(3));
        }
        Matcher ymd = YMD.matcher(s);
        if (ymd.find()) {
            return format(ymd.group(1), ymd.group(2), ymd.group(3));
        }
        Matcher dmy = DMY.matcher(s);
        if (dmy.matches()) {
            // 日和月谁先谁后，只有其中一个大于 12 时才分得清
            String a = dmy.group(1);
            String b = dmy.group(2);
            String year = dmy.group(3);
            if (Integer.parseInt(a) > 12) {
                return format(year, b, a);
            }
            if (Integer.parseInt(b) > 12) {
                return format(year, a, b);
            }
        }
        return null;
    }

    /**
     * 证据里是不是写着这个日期。对应 {@code date_in}。
     *
     * <p>年、月、日必须属于同一个日期。先找证据里写全了的日期；找不到时，再认年份写在别处的写法：
     * "自2024年4月1日起至12月31日止"里的 12 月 31 日、"24年3月18日"、"March 18, 2024"。
     * 已经是完整日期的部分不能再拆开来用。
     *
     * @param value 归一化之后的日期（{@link #parse} 的结果）
     */
    public static boolean in(String value, String evidence) {
        if (!ISO.matcher(value).matches()) {
            return false;
        }
        String text = Numbers.arabicNumerals(evidence);
        List<String> fullMatches = new ArrayList<>(); // 证据里写全了的日期的原文
        Set<String> found = new HashSet<>();
        for (Pattern p : new Pattern[]{YMD, COMPACT}) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                fullMatches.add(m.group());
                found.add(format(m.group(1), m.group(2), m.group(3)));
            }
        }
        Matcher dmy = DMY.matcher(text);
        while (dmy.find()) {
            // 分不清日月的先后，两种读法都算
            found.add(format(dmy.group(3), dmy.group(1), dmy.group(2)));
            found.add(format(dmy.group(3), dmy.group(2), dmy.group(1)));
        }
        for (String d : found) {
            // 值只到年月时，证据里同年同月的完整日期也算对得上
            if (d != null && (d.equals(value) || d.startsWith(value + "-"))) {
                return true;
            }
        }

        // 年份写在别处的日期
        String[] parts = value.split("-");
        String year = parts[0];
        boolean yearPresent = Pattern.compile("(?<!\\d)" + year + "(?!\\d)", UCC).matcher(text).find()
                || Pattern.compile("(?<!\\d)" + year.substring(2) + "\\s*年", UCC).matcher(text).find();
        if (!yearPresent) {
            return false;
        }
        if (parts.length == 1) {
            return true;
        }
        for (String full : fullMatches) {
            text = text.replace(full, " "); // 已经是完整日期的部分不能再拆开用
        }
        int month = Integer.parseInt(parts[1]);
        if (month < 1 || month > 12) {
            return false;
        }
        boolean named = text.toLowerCase(Locale.ROOT).contains(EN_MONTHS[month - 1]);
        if (parts.length == 2) {
            return named || Pattern.compile("(?<!\\d)0?" + month + "\\s*月", UCC).matcher(text).find();
        }
        int day = Integer.parseInt(parts[2]);
        Matcher md = MONTH_DAY.matcher(text);
        while (md.find()) {
            if (Integer.parseInt(md.group(1)) == month && Integer.parseInt(md.group(2)) == day) {
                return true;
            }
        }
        return named && Pattern.compile("(?<!\\d)0?" + day + "(?!\\d)", UCC).matcher(text).find();
    }
}
