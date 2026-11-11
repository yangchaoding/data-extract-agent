package com.cb.agent.capability.document.text;

import com.cb.agent.testsupport.Golden;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本工具的差分测试：和 Python 版对同一批文字算出的结果逐项比较。
 *
 * <p>这些函数决定切片的边界和"两段文字是否相同"，差一个字符后面都会跟着不一样。
 * 用例里特意放了容易出差异的文字：全角字符、各种引号和横线、不换行空格、零宽字符、
 * 占两个 char 的生僻字和表情、大小写特殊的字母。
 */
class TextNormGoldenTest {

    @Test
    void 和Python版的结果逐项相同() {
        JsonNode cases = Golden.read(Golden.GOLDEN.resolve("textnorm.json"));
        assertTrue(cases.size() > 10, "基准数据太少，可能没有生成完整");
        for (JsonNode c : cases.values()) {
            String text = c.get("text").stringValue();
            String label = "输入：[" + text + "]";
            assertEquals(c.get("tokens").intValue(), TextNorm.estimateTokens(text), "token 估算不一致。" + label);
            assertEquals(c.get("normalize").stringValue(), TextNorm.normalizeChars(text), "normalizeChars 不一致。" + label);
            assertEquals(c.get("squash").stringValue(), TextNorm.squash(text), "squash 不一致。" + label);
            assertEquals(c.get("one_line").stringValue(), TextNorm.oneLine(text), "oneLine 不一致。" + label);
            assertEquals(c.get("one_line_10").stringValue(), TextNorm.oneLine(text, 10), "oneLine(10) 不一致。" + label);
        }
    }

    @Test
    void 空串和null的token数是0() {
        assertEquals(0, TextNorm.estimateTokens(""));
        assertEquals(0, TextNorm.estimateTokens(null));
    }

    @Test
    void strip会去掉不换行空格() {
        // String.strip() 去不掉 U+00A0，识别结果里却常有它
        assertEquals("内容", TextNorm.strip(" \u00A0内容\u3000 "));
        assertEquals("", TextNorm.strip(" \u00A0\t"));
    }
}
