package com.doudou.x.util;

/**
 * 纯文本小工具。
 *
 * <p>只放无状态、不依赖 Android API、不含业务语义的方法，
 * 这样 ai / ui / data 任何一层都能直接复用，不会反向依赖某个大引擎。
 */
public final class Texts {

    private Texts() {
    }

    /** null 转空串，省掉调用方到处写三元表达式。 */
    public static String orEmpty(String text) {
        return text == null ? "" : text;
    }

    /** null、空串、纯空白都算空。 */
    public static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    /**
     * 密钥脱敏：只留首尾，避免完整 Key 进日志。
     *
     * <p>长度不足 12 位时整段打码——短 Key 按比例截取等于把内容露出来。
     */
    public static String maskSecret(String secret) {
        if (secret == null || secret.length() <= 12) {
            return "***";
        }
        return secret.substring(0, 6) + "***" + secret.substring(secret.length() - 4);
    }

    /**
     * 超长文本截断，用于日志与 Toast 提示。
     *
     * @param max 保留的字符数，&le;0 表示不截断
     */
    public static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        if (max <= 0 || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…";
    }
}
