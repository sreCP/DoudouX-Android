package com.doudou.x.ai.context;

/**
 * Token 估算器。
 *
 * <p>端侧拿不到真实 tokenizer，用启发式估算即可：CJK 按 1 字约 1 token，
 * 其余按 4 字符约 1 token。估高的后果只是少发一点历史，不会有超窗风险。
 */
public final class TokenEstimator {

    /** 单条消息的固定开销：role、content 键名与分隔符。 */
    public static final int PER_MESSAGE = 4;
    /** 工具结果消息多一个 tool_call_id。 */
    public static final int TOOL_MESSAGE = 8;
    /** 一次工具调用声明的壳开销：id + type + function。 */
    public static final int PER_TOOL_CALL = 12;

    private TokenEstimator() {
    }

    /** 估算一段文本的 token 数；null 与空串返回 0。 */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }

    /** 工具声明（tools[] JSON）的估算：文本本身 + 每条声明的壳开销。 */
    public static int estimateTools(String toolsJson, int toolCount) {
        return estimate(toolsJson) + toolCount * PER_TOOL_CALL;
    }

    /** CJK 汉字、假名、谚文、全角与中文标点都按 1 字符 1 token 计。 */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x3040 && c <= 0x30FF)
                || (c >= 0xAC00 && c <= 0xD7AF)
                || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0x3000 && c <= 0x303F);
    }
}
