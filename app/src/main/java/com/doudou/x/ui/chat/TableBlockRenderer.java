package com.doudou.x.ui.chat;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.doudou.x.R;

import java.util.List;

/**
 * Markdown 表格渲染（GFM）。
 *
 * 结构：外层圆角描边卡片 + 表头行 + 若干数据行，行间用 1px 分隔线，
 * 表头浅色底加粗，数据行黑白灰相间便于横向阅读。
 *
 * 列宽：用 TextPaint 量出每列最宽单元格，保证跨行对齐；
 * - MODE_WRAP：整体宽度超过可用宽度时按比例压缩（每列不低于最小宽度），不足时拉伸填满；
 * - MODE_SINGLE_LINE：保持自然列宽，超出屏幕时整体横向滚动，单元格不换行。
 */
public class TableBlockRenderer {

    private static final int TEXT_SIZE_SP = 13;
    private static final int CELL_PADDING_H_DP = 8;
    private static final int CELL_PADDING_V_DP = 6;
    private static final int CELL_MIN_WIDTH_DP = 64;
    private static final int CELL_MIN_HEIGHT_DP = 34;
    /** 气泡内外留白的粗略估算，用于计算表格可用宽度。 */
    private static final int HORIZONTAL_INSET_DP = 60;

    private static final int COLOR_BORDER = 0xFFD5DCE8;
    private static final int COLOR_DIVIDER = 0xFFE3E8F0;
    private static final int COLOR_HEADER_BG = 0xFFEEF1FA;
    private static final int COLOR_ROW_EVEN = 0xFFFFFFFF;
    private static final int COLOR_ROW_ODD = 0xFFF6F8FC;
    private static final int COLOR_TEXT = 0xFF1F2430;
    private static final int COLOR_HEADER_TEXT = 0xFF3A4560;

    public static View render(Context context, MarkdownParser.TableData data, int mode) {
        float density = context.getResources().getDisplayMetrics().density;
        int columnCount = data == null ? 0 : data.columnCount();
        if (columnCount == 0) {
            return new View(context);
        }

        int cellPaddingH = dp(density, CELL_PADDING_H_DP);
        int cellPaddingV = dp(density, CELL_PADDING_V_DP);
        int minColumnWidth = dp(density, CELL_MIN_WIDTH_DP);
        int cellMinHeight = dp(density, CELL_MIN_HEIGHT_DP);

        int[] widths = measureColumns(data, density, minColumnWidth, cellPaddingH);
        int total = sum(widths);

        int available = context.getResources().getDisplayMetrics().widthPixels
                - dp(density, HORIZONTAL_INSET_DP);
        if (available <= 0) {
            available = context.getResources().getDisplayMetrics().widthPixels;
        }
        if (total < available) {
            // 表格偏窄：按比例拉伸填满可用宽度
            widths = scale(widths, available / (float) total);
            total = sum(widths);
        } else if (total > available && mode == CodeBlockRenderer.MODE_WRAP) {
            // 换行模式：压缩到可用宽度内，单列不低于最小宽度
            int[] scaled = scale(widths, available / (float) total);
            boolean feasible = true;
            for (int w : scaled) {
                if (w < minColumnWidth) {
                    feasible = false;
                    break;
                }
            }
            if (feasible) {
                widths = scaled;
                total = sum(widths);
            }
        }

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_md_table);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView scroller = new HorizontalScrollView(context);
        scroller.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroller.setHorizontalScrollBarEnabled(false);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setLayoutParams(new ViewGroup.LayoutParams(
                Math.max(total, 1), ViewGroup.LayoutParams.WRAP_CONTENT));

        container.addView(buildRow(context, data.header, widths, data.align, density,
                cellPaddingH, cellPaddingV, cellMinHeight, true, mode, COLOR_HEADER_BG));
        for (int r = 0; r < data.rows.size(); r++) {
            container.addView(divider(context, total));
            int rowBg = (r % 2 == 1) ? COLOR_ROW_ODD : COLOR_ROW_EVEN;
            container.addView(buildRow(context, data.rows.get(r), widths, data.align, density,
                    cellPaddingH, cellPaddingV, cellMinHeight, false, mode, rowBg));
        }

        scroller.addView(container);
        root.addView(scroller);
        return root;
    }

    private static View buildRow(Context context, List<String> cells, int[] widths, int[] align,
                                 float density, int cellPaddingH, int cellPaddingV,
                                 int cellMinHeight, boolean header, int mode, int rowBackground) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setBackgroundColor(rowBackground);
        for (int i = 0; i < widths.length; i++) {
            row.addView(buildCell(context, i < cells.size() ? cells.get(i) : "",
                    widths[i], i < align.length ? align[i] : MarkdownParser.ALIGN_LEFT,
                    density, cellPaddingH, cellPaddingV, cellMinHeight, header, mode));
        }
        return row;
    }

    private static TextView buildCell(Context context, String text, int width, int align,
                                      float density, int cellPaddingH, int cellPaddingV,
                                      int cellMinHeight, boolean header, int mode) {
        TextView tv = new TextView(context);
        tv.setText(text == null || text.isEmpty() ? " " : text);
        tv.setTextSize(TEXT_SIZE_SP);
        tv.setTypeface(header ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tv.setTextColor(header ? COLOR_HEADER_TEXT : COLOR_TEXT);
        tv.setGravity(cellGravity(align) | Gravity.CENTER_VERTICAL);
        tv.setPadding(cellPaddingH, cellPaddingV, cellPaddingH, cellPaddingV);
        tv.setMinHeight(cellMinHeight);
        tv.setLineSpacing(0f, 1.3f);
        if (mode == CodeBlockRenderer.MODE_SINGLE_LINE) {
            tv.setSingleLine(true);
        }
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                width, ViewGroup.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    private static View divider(Context context, int width) {
        View view = new View(context);
        view.setLayoutParams(new ViewGroup.LayoutParams(width, 1));
        view.setBackgroundColor(COLOR_DIVIDER);
        return view;
    }

    private static int cellGravity(int align) {
        switch (align) {
            case MarkdownParser.ALIGN_CENTER:
                return Gravity.CENTER_HORIZONTAL;
            case MarkdownParser.ALIGN_RIGHT:
                return Gravity.END;
            case MarkdownParser.ALIGN_LEFT:
            default:
                return Gravity.START;
        }
    }

    /** 逐列量出最宽单元格宽度（含左右内边距）。 */
    private static int[] measureColumns(MarkdownParser.TableData data, float density,
                                        int minColumnWidth, int cellPaddingH) {
        int columnCount = data.columnCount();
        int[] widths = new int[columnCount];
        TextPaint normal = new TextPaint();
        normal.setTextSize(TEXT_SIZE_SP * density);
        TextPaint bold = new TextPaint();
        bold.setTypeface(Typeface.DEFAULT_BOLD);
        bold.setTextSize(TEXT_SIZE_SP * density);

        for (int i = 0; i < columnCount; i++) {
            int max = 0;
            String head = data.header.get(i);
            max = Math.max(max, (int) (bold.measureText(head == null ? "" : head) + 0.5f));
            for (List<String> row : data.rows) {
                String cell = i < row.size() ? row.get(i) : "";
                String[] lines = cell == null ? new String[0] : cell.split("\n");
                for (String line : lines) {
                    max = Math.max(max, (int) (normal.measureText(line) + 0.5f));
                }
            }
            // 额外留 4dp 余量，避免量算误差导致单行模式下内容被截断
            widths[i] = Math.max(minColumnWidth,
                    max + cellPaddingH * 2 + (int) (density * 4 + 0.5f));
        }
        return widths;
    }

    private static int[] scale(int[] widths, float factor) {
        int[] result = new int[widths.length];
        for (int i = 0; i < widths.length; i++) {
            result[i] = Math.max(1, Math.round(widths[i] * factor));
        }
        return result;
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int v : values) {
            total += v;
        }
        return total;
    }

    private static int dp(float density, int value) {
        return (int) (value * density + 0.5f);
    }
}
