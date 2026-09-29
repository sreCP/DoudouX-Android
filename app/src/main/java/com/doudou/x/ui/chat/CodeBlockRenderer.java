package com.doudou.x.ui.chat;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 代码块渲染：暗色背景 + 左侧行号。
 *
 * 两种超长行渲染模式：
 * 1) SINGLE_LINE（默认）：代码不换行，整体横向滚动，行号固定不随之滚动；
 * 2) WRAP：按逻辑行换行显示，每一行独立成行，底色黑白灰相间做行间标记。
 */
public class CodeBlockRenderer {

    public static final int MODE_SINGLE_LINE = 0;
    public static final int MODE_WRAP = 1;

    private static final int TEXT_SIZE_SP = 12;
    private static final int GUTTER_WIDTH_DP = 38;
    private static final int PADDING_DP = 10;
    private static final int LINE_SPACING_EXTRA_DP = 3;

    private static final int COLOR_TEXT = 0xFFD7DEE9;
    private static final int COLOR_NUMBER = 0xFF6B7688;
    private static final int COLOR_HEADER_TEXT = 0xFF9AA5B1;
    private static final int COLOR_LINE_EVEN = 0xFF1E222B;
    private static final int COLOR_LINE_ODD = 0xFF2A303C;

    /**
     * @param lang     代码语言（可为空）
     * @param code     代码内容
     * @param mode     MODE_SINGLE_LINE / MODE_WRAP
     * @param listener 复制按钮回调（可为 null）
     */
    public static View render(Context context, String lang, String code,
                              int mode, final OnCopyListener listener) {
        float density = context.getResources().getDisplayMetrics().density;
        int padding = dp(density, PADDING_DP);
        int gutterWidth = dp(density, GUTTER_WIDTH_DP);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(com.doudou.x.R.drawable.bg_code_block);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 头部：语言 + 复制
        if (lang != null && !lang.isEmpty()) {
            root.addView(buildHeader(context, lang, code, density, listener));
        }

        String[] lines = code.split("\n", -1);
        if (lines.length == 0) {
            return root;
        }

        if (mode == MODE_WRAP) {
            // 换行模式：一行一个横向 row（行号 + 可换行代码），黑灰相间
            LinearLayout body = new LinearLayout(context);
            body.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < lines.length; i++) {
                body.addView(buildWrapRow(context, i + 1, lines[i], density, gutterWidth, padding));
            }
            root.addView(body);
        } else {
            // 单行模式：行号列固定 + 代码区横向滚动
            LinearLayout body = new LinearLayout(context);
            body.setOrientation(LinearLayout.HORIZONTAL);
            body.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout gutter = new LinearLayout(context);
            gutter.setOrientation(LinearLayout.VERTICAL);
            gutter.setLayoutParams(new LinearLayout.LayoutParams(
                    gutterWidth, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout codeColumn = new LinearLayout(context);
            codeColumn.setOrientation(LinearLayout.VERTICAL);
            codeColumn.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            for (int i = 0; i < lines.length; i++) {
                int bg = (i % 2 == 0) ? COLOR_LINE_EVEN : COLOR_LINE_ODD;
                TextView numberView = buildLineNumber(context, i + 1, density);
                numberView.setBackgroundColor(bg);
                gutter.addView(numberView);

                TextView codeLine = buildCodeLine(context, lines[i], density, padding);
                codeLine.setSingleLine(true);
                codeLine.setHorizontallyScrolling(true);
                codeLine.setBackgroundColor(bg);
                codeColumn.addView(codeLine);
            }

            body.addView(gutter);

            HorizontalScrollView scroller = new HorizontalScrollView(context);
            scroller.setHorizontalScrollBarEnabled(false);
            scroller.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            scroller.addView(codeColumn);
            body.addView(scroller);

            root.addView(body);
        }
        return root;
    }

    private static View buildHeader(Context context, String lang, final String code,
                                    float density, final OnCopyListener listener) {
        int pad = dp(density, PADDING_DP);
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(0xFF12151C);
        header.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.setPadding(pad, dp(density, 6), pad, dp(density, 6));

        TextView langView = new TextView(context);
        langView.setText(lang);
        langView.setTextSize(11);
        langView.setTypeface(Typeface.DEFAULT_BOLD);
        langView.setTextColor(COLOR_HEADER_TEXT);
        langView.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(langView);

        if (listener != null) {
            TextView copy = new TextView(context);
            copy.setText(context.getString(com.doudou.x.R.string.code_copy));
            copy.setTextSize(11);
            copy.setTextColor(0xFF8FA7FF);
            copy.setPadding(dp(density, 8), dp(density, 2), dp(density, 8), dp(density, 2));
            copy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    listener.onCopy(code);
                }
            });
            header.addView(copy);
        }
        return header;
    }

    private static View buildWrapRow(Context context, int lineNumber, String line,
                                     float density, int gutterWidth, int padding) {
        int bg = (lineNumber % 2 == 1) ? COLOR_LINE_EVEN : COLOR_LINE_ODD;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(bg);
        row.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView number = buildLineNumber(context, lineNumber, density);
        number.setLayoutParams(new LinearLayout.LayoutParams(
                gutterWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(number);

        TextView codeLine = buildCodeLine(context, line, density, padding);
        codeLine.setSingleLine(false);
        codeLine.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(codeLine);
        return row;
    }

    private static TextView buildLineNumber(Context context, int lineNumber, float density) {
        TextView tv = new TextView(context);
        tv.setText(String.valueOf(lineNumber));
        tv.setTextSize(TEXT_SIZE_SP);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(COLOR_NUMBER);
        tv.setGravity(Gravity.END | Gravity.TOP);
        tv.setPadding(0, dp(density, LINE_SPACING_EXTRA_DP), dp(density, 8), 0);
        tv.setLineSpacing(dp(density, LINE_SPACING_EXTRA_DP), 1f);
        return tv;
    }

    private static TextView buildCodeLine(Context context, String line,
                                          float density, int padding) {
        TextView tv = new TextView(context);
        // 空行放一个空格，保证行高一致
        tv.setText(line == null || line.isEmpty() ? " " : line);
        tv.setTextSize(TEXT_SIZE_SP);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(COLOR_TEXT);
        tv.setTextIsSelectable(true);
        tv.setPadding(padding, dp(density, LINE_SPACING_EXTRA_DP), padding, 0);
        tv.setLineSpacing(dp(density, LINE_SPACING_EXTRA_DP), 1f);
        // 横屏时代码可用更宽的空间，允许内容随宽度变化
        tv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    private static int dp(float density, int value) {
        return (int) (value * density + 0.5f);
    }

    public interface OnCopyListener {
        void onCopy(String code);
    }
}
