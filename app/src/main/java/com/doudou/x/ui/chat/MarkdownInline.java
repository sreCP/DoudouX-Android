package com.doudou.x.ui.chat;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;

/**
 * Markdown 行内样式：**加粗**。
 *
 * 把文本里的 **xxx** 转成带粗体 Span 的 CharSequence，
 * 供标题、列表项、正文直接 setText 使用。
 * 没有闭合的 ** 会按原样输出，避免流式输出中途出现奇怪的截断。
 */
public class MarkdownInline {

    private static final String MARKER = "**";

    /** 把 **加粗** 转成粗体 Span；没有标记时返回原字符串。 */
    public static CharSequence apply(String text) {
        if (text == null || text.isEmpty() || text.indexOf(MARKER) < 0) {
            return text == null ? "" : text;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder();
        int i = 0;
        int len = text.length();
        while (i < len) {
            int start = text.indexOf(MARKER, i);
            if (start < 0) {
                builder.append(text.substring(i));
                break;
            }
            builder.append(text.substring(i, start));
            int end = text.indexOf(MARKER, start + MARKER.length());
            if (end <= start + MARKER.length()) {
                // 没有闭合（或空内容），剩余部分按原样输出
                builder.append(text.substring(start));
                break;
            }
            String inner = text.substring(start + MARKER.length(), end);
            int spanStart = builder.length();
            builder.append(inner);
            builder.setSpan(new StyleSpan(Typeface.BOLD), spanStart,
                    spanStart + inner.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            i = end + MARKER.length();
        }
        return builder;
    }
}
