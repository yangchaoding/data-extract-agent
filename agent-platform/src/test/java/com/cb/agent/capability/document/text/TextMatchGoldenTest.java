package com.cb.agent.capability.document.text;

import com.cb.agent.testsupport.Golden;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐字比对的差分测试：和 Python 版对同一批"原文 + 摘抄"算出的结果逐项比较。
 *
 * <p>"证据是不是原文"是整个抽取的根基。用例覆盖了哪些差别可以忽略（空白、标点、全半角、千分位、大小写），
 * 哪些不行（改了一个字、一个数；从一个数的中间取；把相邻单元格的数粘起来）。
 */
class TextMatchGoldenTest {

    @Test
    void 比对的各项结果和Python版相同() {
        JsonNode cases = Golden.read(Golden.GOLDEN.resolve("textmatch.json"));
        assertTrue(cases.size() >= 40, "基准数据太少，可能没有生成完整");
        for (JsonNode c : cases.values()) {
            String source = c.get("source").stringValue();
            String quote = c.get("quote").stringValue();
            String label = "原文：[" + source + "] 摘抄：[" + quote + "]";

            assertEquals(c.get("key").stringValue(), TextMatch.matchKey(source), "原文的比对形式不一致。" + label);
            assertEquals(c.get("key_glued").stringValue(), TextMatch.matchKey(source, true), "号码连写的比对形式不一致。" + label);
            assertEquals(c.get("quote_key").stringValue(), TextMatch.matchKey(quote), "摘抄的比对形式不一致。" + label);
            assertEquals(c.get("parts"), Golden.tree(TextMatch.quoteParts(quote)), "摘抄的分段不一致。" + label);

            SourceText src = new SourceText(source);
            assertEquals(c.get("supports").booleanValue(), src.supports(quote), "是不是原文的判断不一致。" + label);
            assertEquals(c.get("contains_value").booleanValue(), src.containsValue(quote), "值是不是原文的判断不一致。" + label);
            assertEquals(c.get("position").intValue(), src.position(quote), "位置不一致。" + label);
            assertEquals(c.get("similarity").doubleValue(), src.similarity(quote), 1e-12, "相似度不一致。" + label);
        }
    }

    @Test
    void 分词结果和Python版相同() {
        JsonNode cases = Golden.read(Golden.GOLDEN.resolve("lexical.json"));
        for (JsonNode c : cases.values()) {
            String text = c.get("text").stringValue();
            assertEquals(c.get("tokens"), Golden.tree(LexicalTokenizer.tokens(text)), "分词不一致。输入：[" + text + "]");
        }
    }

    // 下面几条把最要紧的规则单独写出来，读测试的人不用去翻基准数据

    @Test
    void 空白标点全半角千分位的差别都忽略() {
        SourceText src = new SourceText("含税总价为人民币1,280,000.00元（大写：壹佰贰拾捌万元整）");
        assertTrue(src.supports("含税总价为人民币 1280000.00 元"));
        assertTrue(src.supports("含税总价为人民币1,280,000.00元(大写:壹佰贰拾捌万元整)"));
    }

    @Test
    void 改了一个字或一个数就不是原文() {
        SourceText src = new SourceText("含税总价为人民币1,280,000.00元");
        assertFalse(src.supports("含税总价为人民币1,290,000.00元"), "改了一个数");
        assertFalse(src.supports("含税总价为美元1,280,000.00元"), "改了字");
    }

    @Test
    void 数字必须完整() {
        assertFalse(new SourceText("金额 1234 元").supports("23"), "不能从一个数的中间取");
        assertFalse(new SourceText("单价 1.5 元").supports("15"), "小数点不能丢");
        assertFalse(new SourceText("| 30 | 384 |").supports("30384"), "相邻单元格的数不能粘起来");
        assertTrue(new SourceText("金额 1234 元").supports("1234"));
    }

    @Test
    void 摘抄可以分几段但每一段都要是原文() {
        SourceText src = new SourceText("| 项目 | 本期 |\n| --- | --- |\n| 营业收入 | 12,345.67 |");
        assertTrue(src.supports("| 项目 | 本期 |\n| 营业收入 | 12,345.67 |"), "表头加一行");
        assertFalse(src.supports("| 项目 | 本期 |\n| 营业成本 | 8,000.00 |"), "有一段不是原文");
    }

    @Test
    void 分组书写的号码值可以连着写() {
        SourceText src = new SourceText("账号：6222 0212 3456 7890 123");
        assertFalse(src.supports("6222021234567890123"), "按摘抄的标准，分组的号码不算连着的");
        assertTrue(src.containsValue("6222021234567890123"), "按值的标准可以");
    }
}
