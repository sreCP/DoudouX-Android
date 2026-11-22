package com.doudou.x.util;

/**
 * 带上限的文本累积器。
 *
 * <p>流式响应要整段留作「查看原始返回」，但思考型模型可能吐几百 KB，
 * 无上限累积会把内存吃光。这里在到达上限后停止追加并打一次截断标记，
 * 既保住内存，又让用户知道内容不完整。
 */
public final class CappedTextBuffer {

    private final StringBuilder buffer = new StringBuilder();
    private final int limitChars;
    private final String truncatedMark;
    private boolean truncated;

    public CappedTextBuffer(int limitChars) {
        this(limitChars, "…（内容过长，已截断）");
    }

    public CappedTextBuffer(int limitChars, String truncatedMark) {
        this.limitChars = limitChars;
        this.truncatedMark = truncatedMark == null ? "" : truncatedMark;
    }

    /** 追加一行（自动补换行）。超过上限后不再追加。 */
    public void appendLine(String line) {
        if (truncated) {
            return;
        }
        buffer.append(line == null ? "" : line).append('\n');
        if (buffer.length() >= limitChars) {
            buffer.append(truncatedMark).append('\n');
            truncated = true;
        }
    }

    /** 追加任意片段，不做换行处理。 */
    public void append(String text) {
        if (truncated || text == null) {
            return;
        }
        buffer.append(text);
        if (buffer.length() >= limitChars) {
            buffer.append(truncatedMark).append('\n');
            truncated = true;
        }
    }

    /** 当前已累积的字符数。 */
    public int length() {
        return buffer.length();
    }

    /** 是否发生过截断。 */
    public boolean isTruncated() {
        return truncated;
    }

    public boolean isEmpty() {
        return buffer.length() == 0;
    }

    @Override
    public String toString() {
        return buffer.toString();
    }
}
