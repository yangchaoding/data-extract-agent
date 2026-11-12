package com.cb.agent.capability.extract.value;

import com.cb.agent.capability.document.chunk.Chunk;
import com.cb.agent.capability.document.model.Block;
import com.cb.agent.capability.document.text.TextMatch;
import com.cb.agent.capability.document.text.TextNorm;
import com.cb.agent.capability.extract.format.PyFormat;
import com.cb.agent.capability.extract.schema.FieldSpec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数字和单位：读出模型写的数，认出原文给它的单位，核对两者对不对得上。
 *
 * <p>对应 Python 版 {@code values.py} 里"中文数字"和"数字和单位"两部分。
 *
 * <p>设计上的分工是：<b>模型照原文写数字和单位，换算成字段要求的单位由程序做</b>。
 * 模型自己乘除容易出错，而且没法核对；让它照抄，程序既能核对"这个数确实写在证据里"，又能可靠地换算。
 *
 * <p>原文给一个数的单位有两种写法：紧跟在数字后面（{@code 5,000万元}），或者另外说明
 * （表头里的 {@code 金额(万元)}、表格上方的 {@code 单位：万元}）。两种都要认。
 */
public final class Numbers {

    private Numbers() {
    }

    private static final int UCC = Pattern.UNICODE_CHARACTER_CLASS;
    private static final int ICASE = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    /** 除法的精度，和 Python 的 Decimal 默认精度相同。换算用的除数都是 10 的整数次方，实际上都能除尽。 */
    private static final MathContext PRECISION = new MathContext(28);

    // ---- 中文数字 -----------------------------------------------------------------------

    /** 逐位写的中文数字里每个字是几。年份里的"〇"常被写成或识别成圆圈、字母 O、数字 0。 */
    private static final Map<Character, Integer> CN_DIGITS = Map.ofEntries(
            Map.entry('〇', 0), Map.entry('一', 1), Map.entry('二', 2), Map.entry('三', 3), Map.entry('四', 4),
            Map.entry('五', 5), Map.entry('六', 6), Map.entry('七', 7), Map.entry('八', 8), Map.entry('九', 9),
            Map.entry('零', 0), Map.entry('○', 0), Map.entry('O', 0), Map.entry('o', 0), Map.entry('0', 0));
    /** 一串逐位写的中文数字：二〇二四、十五、三。 */
    private static final Pattern CN_RUN =
            Pattern.compile("[〇○零一二三四五六七八九十][〇○零一二三四五六七八九十Oo0]*");

    /** 带单位写的中文数字里的"十百千"。 */
    private static final Map<Character, Integer> CN_UNITS = Map.of(
            '十', 10, '拾', 10, '百', 100, '佰', 100, '千', 1000, '仟', 1000);
    /** 带单位写的中文数字里每个字是几，小写和大写都认。 */
    private static final Map<Character, Integer> CN_VALUES = Map.ofEntries(
            Map.entry('零', 0), Map.entry('一', 1), Map.entry('二', 2), Map.entry('三', 3), Map.entry('四', 4),
            Map.entry('五', 5), Map.entry('六', 6), Map.entry('七', 7), Map.entry('八', 8), Map.entry('九', 9),
            Map.entry('壹', 1), Map.entry('贰', 2), Map.entry('叁', 3), Map.entry('肆', 4), Map.entry('伍', 5),
            Map.entry('陆', 6), Map.entry('柒', 7), Map.entry('捌', 8), Map.entry('玖', 9),
            Map.entry('〇', 0), Map.entry('两', 2));
    private static final Pattern CN_AMOUNT =
            Pattern.compile("[零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟]+");
    /** "百分之六"里的"百分之"：去掉之后按 6 读。 */
    private static final Pattern FRACTION_WORD = Pattern.compile("[百千]分之");
    private static final Pattern WAN_YI = Pattern.compile("[万亿]");

    /** 一串逐位写的中文数字换成阿拉伯数字。对应 {@code _cn_run}。 */
    private static String cnRun(String run) {
        int ten = run.indexOf('十');
        if (ten >= 0) {
            String left = run.substring(0, ten);
            String right = run.substring(ten + 1);
            // 只处理"十""十五""三十""三十五"这几种；别的写法（三十五十）看不懂，原样留着
            if (left.length() > 1 || right.length() > 1 || right.indexOf('十') >= 0) {
                return run;
            }
            int tens = left.isEmpty() ? 1 : CN_DIGITS.get(left.charAt(0));
            int ones = right.isEmpty() ? 0 : CN_DIGITS.get(right.charAt(0));
            return String.valueOf(tens * 10 + ones);
        }
        StringBuilder out = new StringBuilder();
        for (char c : run.toCharArray()) {
            out.append(CN_DIGITS.get(c));
        }
        return out.toString();
    }

    /**
     * 把逐位写的中文数字换成阿拉伯数字：二〇二四年三月十五日 → 2024年3月15日。
     * 对应 {@code arabic_numerals}。
     */
    public static String arabicNumerals(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC);
        return CN_RUN.matcher(s).replaceAll(m -> Matcher.quoteReplacement(cnRun(m.group())));
    }

    /**
     * 带单位写的中文数字：一百二十 → 120，壹佰贰拾捌万 → 1280000。对应 {@code parse_cn_number}。
     *
     * @return 数值；不是这种写法时返回 null。逐位写的（如年份"二〇二四"）也返回 null，由 {@link #arabicNumerals} 处理
     */
    public static BigInteger parseCnNumber(String run) {
        boolean hasValue = false;
        boolean hasUnit = false;
        for (char c : run.toCharArray()) {
            hasValue |= CN_VALUES.containsKey(c) || CN_UNITS.containsKey(c);
            hasUnit |= CN_UNITS.containsKey(c) || c == '万' || c == '亿';
        }
        if (!(hasValue && hasUnit)) {
            return null;
        }
        // total 是已经算完的"亿""万"以上的部分，section 是当前这一节（千百十），digit 是刚读到还没配上单位的那个数字
        BigInteger total = BigInteger.ZERO;
        long section = 0;
        long digit = 0;
        for (char c : run.toCharArray()) {
            if (CN_UNITS.containsKey(c)) {
                section += (digit == 0 ? 1 : digit) * CN_UNITS.get(c);
                digit = 0;
            } else if (c == '万') {
                total = total.add(BigInteger.valueOf((section + digit) * 10_000L));
                section = 0;
                digit = 0;
            } else if (c == '亿') {
                total = total.add(BigInteger.valueOf(section + digit)).multiply(BigInteger.valueOf(100_000_000L));
                section = 0;
                digit = 0;
            } else {
                digit = CN_VALUES.get(c);
            }
        }
        return total.add(BigInteger.valueOf(section + digit));
    }

    // ---- 单位 ---------------------------------------------------------------------------

    /** 一个数量级的词和它代表的倍数。 */
    private record ScaleWord(Pattern pattern, long scale) {
    }

    /** 数量级的词，长的排在前面（"万亿"要先于"万"和"亿"被认出来）。 */
    private static final List<ScaleWord> SCALE_WORDS = List.of(
            scaleWord("万亿", 1_000_000_000_000L), scaleWord("千亿", 100_000_000_000L),
            scaleWord("百亿", 10_000_000_000L), scaleWord("十亿", 1_000_000_000L), scaleWord("亿", 100_000_000L),
            scaleWord("千万", 10_000_000L), scaleWord("百万", 1_000_000L), scaleWord("十万", 100_000L),
            scaleWord("万", 10_000L),
            // "千"只在货币和股份前面才是数量级：千克、千米、千瓦本身就是单位
            scaleWord("千(?=美元|港元|港币|欧元|日元|英镑|元|股|份)", 1_000L),
            scaleWord("billion", 1_000_000_000L), scaleWord("million", 1_000_000L), scaleWord("thousand", 1_000L));

    private static ScaleWord scaleWord(String pattern, long scale) {
        return new ScaleWord(Pattern.compile(pattern, ICASE), scale);
    }

    private static final Pattern CURRENCY_PREFIX = Pattern.compile("(?:人民币|rmb|cny)\\s*", ICASE | UCC);
    private static final Pattern BASE_UNIT = Pattern.compile("美元|港元|港币|欧元|日元|英镑|元|股|份|%|‰");

    /** 单位的规范写法：全半角统一，去掉前面的币种（人民币万元 → 万元）。对应 {@code _unit_text}。 */
    private static String unitText(String unit) {
        String u = TextNorm.strip(Normalizer.normalize(unit, Normalizer.Form.NFKC));
        Matcher m = CURRENCY_PREFIX.matcher(u);
        return m.lookingAt() && m.end() < u.length() ? u.substring(m.end()) : u;
    }

    /**
     * 单位里的数量级：万元 → 10000，百万美元 → 1000000，元、%、千克 → 1。对应 {@code unit_scale}。
     *
     * @return 数量级；{@code unit} 不像单位（空的，或者以括号这类符号开头）时返回 null
     */
    public static Long unitScale(String unit) {
        String u = unitText(unit);
        for (ScaleWord w : SCALE_WORDS) {
            if (w.pattern().matcher(u).lookingAt()) {
                return w.scale();
            }
        }
        if (u.isEmpty()) {
            return null;
        }
        int first = u.codePointAt(0);
        return Character.isLetter(first) || first == '%' || first == '‰' ? Long.valueOf(1) : null;
    }

    /** 去掉数量级之后的单位：万元 → 元，百万美元 → 美元。对应 {@code _base_unit}。 */
    private static String baseUnit(String unit) {
        String u = unitText(unit);
        for (ScaleWord w : SCALE_WORDS) {
            Matcher m = w.pattern().matcher(u);
            if (m.lookingAt()) {
                return u.substring(m.end());
            }
        }
        return u;
    }

    /**
     * 原文里紧跟在一个数后面的单位的数量级：万元 → 10000，元、股、% → 1。对应 {@code _attached_scale}。
     *
     * <p>原文里数字后面什么字都可能出现（表格竖线、标点、别的词），所以只认数量级的词和几种常见的基本单位，
     * 别的一律当作"后面不是单位"。
     *
     * @param tail 这个数后面的文字
     * @return 数量级；后面不是单位时返回 null
     */
    private static Long attachedScale(String tail) {
        String t = unitText(lstrip(tail, " ("));
        Long scale = unitScale(t);
        if (scale != null && scale > 1) {
            return scale;
        }
        return BASE_UNIT.matcher(t).lookingAt() ? Long.valueOf(1) : null;
    }

    private static String lstrip(String s, String chars) {
        int i = 0;
        while (i < s.length() && chars.indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        return s.substring(i);
    }

    // ---- 读出一个数 ----------------------------------------------------------------------

    private static final Pattern NUM = Pattern.compile("[-+]?[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern PLAIN_NUMBER = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");

    /**
     * 模型写出来的一个数。
     *
     * @param value 写出来的数值，还没有换算
     * @param scale 它带的单位的数量级；没带单位时为 null
     */
    public record Num(BigDecimal value, Long scale) {
    }

    /**
     * 读出模型给的一个数。对应 {@code read_number}。
     *
     * <p>例子：{@code 1234.56} → (1234.56, 没带单位)；{@code "1234.56万元"} → (1234.56, 10000)；
     * {@code "56%"} → (56, 1)；{@code "(1,234.56)"} → (-1234.56, 没带单位)，会计上括号表示负数；
     * {@code "壹佰贰拾捌万元整"} → (1280000, 1)。
     *
     * <p>用十进制小数表示，换算时不会有浮点误差。
     *
     * @return 读出的数；读不出来返回 null
     */
    public static Num readNumber(Object raw) {
        if (raw instanceof Boolean || raw == null) {
            return null;
        }
        if (raw instanceof Double || raw instanceof Float) {
            double d = ((Number) raw).doubleValue();
            return Double.isFinite(d) ? new Num(new BigDecimal(PyFormat.floatRepr(d)), null) : null;
        }
        if (raw instanceof BigDecimal d) {
            return new Num(d, null);
        }
        if (raw instanceof Number n) {
            return new Num(new BigDecimal(n.toString()), null);
        }
        if (!(raw instanceof String)) {
            return null; // 对象或数组：不是一个数
        }
        String s = TextNorm.strip(TextMatch.stripThousands(Normalizer.normalize((String) raw, Normalizer.Form.NFKC)));
        s = FRACTION_WORD.matcher(s).replaceAll("");
        Matcher m = NUM.matcher(s);
        if (m.find()) {
            BigDecimal num = new BigDecimal(m.group());
            if (s.startsWith("(") && s.indexOf(')', m.end()) >= 0) {
                num = num.abs().negate(); // 会计写法：括号表示负数
            }
            // 单位可能写在括号里：1,234.56（万元）
            return new Num(num, unitScale(lstrip(s.substring(m.end()), " ()")));
        }
        // 没有阿拉伯数字：按带单位的中文数字读
        Matcher cn = CN_AMOUNT.matcher(s);
        if (cn.find()) {
            BigInteger n = parseCnNumber(cn.group());
            if (n != null) {
                Long scale = unitScale(s.substring(cn.end()));
                if (scale == null && WAN_YI.matcher(cn.group()).find()) {
                    scale = 1L; // 壹佰贰拾捌万：数量级已经算进数里了，这是按基本单位写的
                }
                return new Num(new BigDecimal(n), scale);
            }
        }
        // 逐位写的：二〇二四
        String converted = arabicNumerals(s);
        m = NUM.matcher(converted);
        return m.find() ? new Num(new BigDecimal(m.group()), unitScale(converted.substring(m.end()))) : null;
    }

    /**
     * 把十进制小数变成输出用的值：是整数就给整数，否则给小数。对应 {@code _plain}。
     *
     * @return {@link Long}、{@link BigInteger}（超出 long 的范围时）或 {@link Double}
     */
    public static Object plain(BigDecimal num) {
        if (num.stripTrailingZeros().scale() <= 0) {
            BigInteger i = num.toBigIntegerExact();
            return i.bitLength() < 64 ? (Object) i.longValueExact() : i;
        }
        return num.doubleValue();
    }

    /** 证据里写着的一个数，和紧跟在它后面的单位的数量级（后面不是单位时为 null）。 */
    private record Occurrence(BigDecimal value, Long scale) {
    }

    /**
     * 找出一段文字里写着的每一个数（不带正负号）。对应 {@code _occurrences}。
     *
     * <p>三种写法都找：阿拉伯数字、逐位写的中文数字（二〇二四）、带单位的中文数字（壹佰贰拾捌万，
     * 这种的数量级已经算进数里了）。
     */
    private static List<Occurrence> occurrences(String text) {
        String plain = FRACTION_WORD.matcher(TextMatch.stripThousands(TextNorm.normalizeChars(text))).replaceAll("");
        List<Occurrence> found = new ArrayList<>();
        for (String source : new String[]{plain, arabicNumerals(plain)}) {
            Matcher m = PLAIN_NUMBER.matcher(source);
            while (m.find()) {
                found.add(new Occurrence(new BigDecimal(m.group()), attachedScale(after(source, m.end()))));
            }
        }
        Matcher m = CN_AMOUNT.matcher(plain);
        while (m.find()) {
            BigInteger n = parseCnNumber(m.group());
            if (n != null) {
                boolean folded = WAN_YI.matcher(m.group()).find();
                found.add(new Occurrence(new BigDecimal(n), folded ? Long.valueOf(1) : attachedScale(after(plain, m.end()))));
            }
        }
        return found;
    }

    /** 从 {@code from} 开始往后最多十个字：看一个数后面跟着什么单位，十个字足够了。 */
    private static String after(String s, int from) {
        return s.substring(from, Math.min(s.length(), from + 10));
    }

    // ---- 原文对单位的说明 -----------------------------------------------------------------

    /** 一个单位的写法：万元、百万美元、元/股。 */
    private static final String UNIT_WORD = "(?:[十百千]?[万亿]|千)?(?:美元|港元|元|股|份)(?:/[^\\s()|,;]{1,4})?";
    /** 对单位的明确说明：{@code 单位：万元}、{@code 金额单位为人民币千元}、{@code 金额（百万元）}。 */
    private static final Pattern UNIT_NOTE = Pattern.compile(
            "单位\\s*[:为]?\\s*(?:人民币|rmb)?\\s*(" + UNIT_WORD + ")|\\(\\s*(?:人民币|rmb)?\\s*(" + UNIT_WORD + ")\\s*\\)",
            ICASE | UCC);
    /** 题注、单位行里顺带提到的带数量级的单位（前面不是数字）：下表金额均以万元列示。 */
    private static final Pattern UNIT_MENTION = Pattern.compile(
            "(?<![0-9余多几零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟])((?:[十百千]?[万亿]|千)(?:美元|港元|元|股|份))");

    /**
     * 一段文字里对单位的说明给出的数量级。对应 {@code _noted_scales}。
     *
     * @param base     只看和它同类的单位：{@code base} 是"元"时，"万元"算，"万股"不算
     * @param mentions true 时，没有写成"单位：…"、只是顺带提到的带数量级的单位也算（用于题注和单位行）
     */
    private static Set<Long> notedScales(String text, String base, boolean mentions) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC);
        List<String> notes = new ArrayList<>();
        Matcher m = UNIT_NOTE.matcher(s);
        while (m.find()) {
            notes.add(m.group(1) != null && !m.group(1).isEmpty() ? m.group(1) : m.group(2));
        }
        if (mentions) {
            Matcher mm = UNIT_MENTION.matcher(s);
            while (mm.find()) {
                notes.add(mm.group(1));
            }
        }
        Set<Long> out = new TreeSet<>();
        for (String note : notes) {
            if (baseUnit(note).equals(base)) {
                Long scale = unitScale(note);
                out.add(scale == null || scale == 0 ? 1L : scale);
            }
        }
        return out;
    }

    /**
     * 原文对这个数的单位的说明，由近到远找：证据里的；证据所在的块里的；紧挨在块前面的题注、单位行里的。
     * 对应 {@code _unit_notes}。
     *
     * <p>字段没有指定单位时不看这些说明：不知道它要的是金额还是比例，表格上的"单位：万元"未必是在说它。
     */
    private static Set<Long> unitNotes(FieldSpec field, String evidence, Block block) {
        if (field.unit().isEmpty()) {
            return new TreeSet<>();
        }
        String base = baseUnit(field.unit());
        Set<Long> notes = notedScales(evidence, base, false);
        if (!notes.isEmpty() || block == null) {
            return notes;
        }
        Set<Long> out = notedScales(block.text(), base, false);
        out.addAll(notedScales(String.join("\n", block.lead()), base, true));
        return out;
    }

    /** 一段文字里有没有出现过这个数量级的词（万、亿、百万…）。对应 {@code _mentions_scale}。 */
    private static boolean mentionsScale(long scale, String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC);
        for (ScaleWord w : SCALE_WORDS) {
            if (w.scale() == scale && w.pattern().matcher(s).find()) {
                return true;
            }
        }
        return false;
    }

    // ---- 核对单位 ------------------------------------------------------------------------

    /**
     * 定出来的数量级和发现的问题。
     *
     * @param scale  模型写的这个数是按什么数量级写的；null 表示"就是字段要求的单位"
     * @param issues 问题，写给模型看的，重问时原样带上
     */
    public record Settled(Long scale, List<String> issues) {
    }

    /**
     * 定出模型给的这个数是按什么数量级写的，同时核对它和原文对不对得上。对应 {@code _settle_scale}。
     *
     * <p>模型写的单位和原文注明的对不上，或者在原文里找不到依据，都算问题。
     * 证据里根本没有这个数时，模型可能自己做了换算：换算得对就接受，否则让它照原文重写。
     *
     * @param number   模型写出来的数（不带正负号）
     * @param stated   它带的单位的数量级；没带单位时为 null
     * @param evidence 模型给的证据
     * @param chunk    证据所在的片段；没有定位到时为 null
     * @param block    证据所在的版面块；没有定位到时为 null
     */
    public static Settled settleScale(FieldSpec field, BigDecimal number, Long stated, String evidence,
                                      Chunk chunk, Block block) {
        List<Occurrence> occurrences = occurrences(evidence);
        // 证据里这个数出现的各处，后面各跟着什么数量级的单位。可能含 null：后面没跟单位
        Set<Long> attached = new LinkedHashSet<>();
        for (Occurrence o : occurrences) {
            if (o.value().compareTo(number) == 0) {
                attached.add(o.scale());
            }
        }
        Set<Long> notes = unitNotes(field, evidence, block);

        if (attached.isEmpty()) {
            // 证据里没有这个数。看它是不是由证据里的某个数换算来的
            List<Object[]> readings = new ArrayList<>(); // {换算成基本单位之后的数, 对应的数量级}
            if (stated == null) {
                // 没带单位：它可能是按基本单位写的，也可能是按字段要求的单位写的
                Long target = unitScale(field.unit());
                readings.add(new Object[]{number, 1L});
                readings.add(new Object[]{number.multiply(BigDecimal.valueOf(target == null ? 1 : target)), null});
            } else {
                readings.add(new Object[]{number.multiply(BigDecimal.valueOf(stated)), stated});
            }
            for (Occurrence o : occurrences) {
                Iterable<Long> scales = o.scale() == null ? notes : List.of(o.scale());
                for (Long s : scales) {
                    for (Object[] reading : readings) {
                        if (o.value().multiply(BigDecimal.valueOf(s)).compareTo((BigDecimal) reading[0]) == 0) {
                            return new Settled((Long) reading[1], new ArrayList<>());
                        }
                    }
                }
            }
            List<String> issues = new ArrayList<>();
            issues.add("evidence 里没有 " + PyFormat.str(plain(number)) + " 这个数。数字要照原文写，后面带上原文的单位"
                    + "（例如 1234.56万元），换算由程序来做，不要自己乘除");
            return new Settled(stated, issues);
        }

        // 原文给这个数注明的单位：紧跟在它后面的优先，没有再看另外的说明
        Set<Long> known = new TreeSet<>();
        for (Long s : attached) {
            if (s != null) {
                known.add(s);
            }
        }
        if (known.isEmpty()) {
            known = notes;
        }
        if (stated == null) {
            if (known.size() > 1) {
                return new Settled(null, new ArrayList<>(List.of(
                        "原文里注明的单位不止一种，这个数却没有带单位。请在数字后面写上它在原文里的单位")));
            }
            return new Settled(known.isEmpty() ? null : known.iterator().next(), new ArrayList<>());
        }
        if (!known.isEmpty() && !known.contains(stated)) {
            Long expected = known.size() == 1 ? known.iterator().next() : stated;
            return new Settled(expected, new ArrayList<>(List.of("这个数的单位和原文注明的不一样。数字后面的单位要照原文写")));
        }
        if (known.isEmpty() && stated > 1) {
            String shown = evidence + "\n" + (chunk != null ? chunk.text() : "");
            if (!mentionsScale(stated, shown)) {
                return new Settled(stated, new ArrayList<>(List.of(
                        "原文里找不到这个单位的依据。单位要照原文写，原文没有写单位的只写数字")));
            }
        }
        return new Settled(stated, new ArrayList<>());
    }

    private static final Pattern MINUS_BEFORE = Pattern.compile("(?:^|[\\s|(:])-$", UCC);
    private static final Pattern PAREN_BEFORE = Pattern.compile("\\|\\s*\\($", UCC);
    private static final Pattern PAREN_AFTER = Pattern.compile("\\)\\s*(?:\\||$)", UCC);

    /**
     * 证据里这个数每次出现都明确标着是负的：前面紧挨着负号，或者在表格单元格里用括号括着（会计写法）。
     * 对应 {@code _marked_negative}。
     */
    public static boolean markedNegative(BigDecimal number, String evidence) {
        String text = TextMatch.stripThousands(TextNorm.normalizeChars(evidence));
        Matcher m = PLAIN_NUMBER.matcher(text);
        boolean any = false;
        while (m.find()) {
            if (new BigDecimal(m.group()).compareTo(number) != 0) {
                continue;
            }
            String before = text.substring(0, m.start());
            String after = text.substring(m.end());
            boolean minus = MINUS_BEFORE.matcher(before).find();
            boolean paren = PAREN_BEFORE.matcher(before).find() && PAREN_AFTER.matcher(after).lookingAt();
            if (!(minus || paren)) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /**
     * 把模型写的数换算成字段要求的单位。
     *
     * @param written 模型写出来的数
     * @param scale   它是按什么数量级写的；null 表示已经是字段要求的单位
     * @param unit    字段要求的单位
     */
    public static Object convert(BigDecimal written, Long scale, String unit) {
        if (scale == null) {
            return plain(written);
        }
        Long target = unitScale(unit);
        return plain(written.multiply(BigDecimal.valueOf(scale))
                .divide(BigDecimal.valueOf(target == null ? 1 : target), PRECISION));
    }
}
