package com.doudou.x.ui.chat;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简 Markdown 解析：识别 ``` 围栏代码块、GFM 表格，其余按纯文本输出。
 * 兼容流式输出场景——结尾未闭合的围栏会被当作代码块处理，
 * 表格在只收到表头+分隔行时也会先渲染出来。
 */
public class MarkdownParser {

    public static final int TYPE_TEXT = 0;
    public static final int TYPE_CODE = 1;
    public static final int TYPE_TABLE = 2;

    public static final int ALIGN_LEFT = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_RIGHT = 2;

    /** GFM 表格数据：表头 + 数据行 + 每列对齐方式。 */
    public static class TableData {
        public final List<String> header;
        public final List<List<String>> rows;
        public final int[] align;

        TableData(List<String> header, List<List<String>> rows, int[] align) {
            this.header = header;
            this.rows = rows;
            this.align = align;
        }

        public int columnCount() {
            return header.size();
        }
    }

    public static class Block {
        public final int type;
        public final String text;
        /** 代码块语言（如 java），可能为 null。 */
        public final String lang;
        /** TYPE_TABLE 时的表格数据，其余类型为 null。 */
        public final TableData table;

        Block(int type, String text, String lang, TableData table) {
            this.type = type;
            this.text = text;
            this.lang = lang;
            this.table = table;
        }

        static Block text(String text) {
            return new Block(TYPE_TEXT, text, null, null);
        }

        static Block code(String code, String lang) {
            return new Block(TYPE_CODE, code, lang, null);
        }

        static Block table(List<String> header, List<List<String>> rows, int[] align) {
            return new Block(TYPE_TABLE, null, null, new TableData(header, rows, align));
        }
    }

    public static List<Block> parse(String source) {
        List<Block> blocks = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return blocks;
        }
        String[] lines = source.split("\n", -1);
        List<String> textLines = new ArrayList<>();
        StringBuilder codeBuf = new StringBuilder();
        String lang = null;
        boolean inCode = false;

        for (String line : lines) {
            String trimmed = line.trim();
            boolean fence = trimmed.startsWith("```");
            if (!inCode && fence) {
                // 进入代码块
                flushTextLines(blocks, textLines);
                lang = trimmed.length() > 3 ? trimmed.substring(3).trim() : null;
                codeBuf.setLength(0);
                inCode = true;
            } else if (inCode && fence) {
                // 结束代码块
                flushCode(blocks, codeBuf, lang);
                lang = null;
                inCode = false;
            } else if (inCode) {
                if (codeBuf.length() > 0) {
                    codeBuf.append('\n');
                }
                codeBuf.append(line);
            } else {
                textLines.add(line);
            }
        }
        if (inCode) {
            // 流式输出中围栏还没闭合，按代码块渲染已收到的部分
            flushCode(blocks, codeBuf, lang);
        } else {
            flushTextLines(blocks, textLines);
        }
        return blocks;
    }

    /** 把连续的非代码文本行切成「纯文本段 + 表格」。 */
    private static void flushTextLines(List<Block> blocks, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        StringBuilder buf = new StringBuilder();
        int i = 0;
        int n = lines.size();
        while (i < n) {
            if (isTableStart(lines, i)) {
                flushTextBuf(blocks, buf);
                List<String> header = splitRow(lines.get(i));
                int[] align = parseAlign(lines.get(i + 1), header.size());
                i += 2;
                List<List<String>> rows = new ArrayList<>();
                while (i < n && isRowLine(lines.get(i))) {
                    rows.add(normalize(splitRow(lines.get(i)), header.size()));
                    i++;
                }
                blocks.add(Block.table(header, rows, align));
            } else {
                if (buf.length() > 0) {
                    buf.append('\n');
                }
                buf.append(lines.get(i));
                i++;
            }
        }
        flushTextBuf(blocks, buf);
        lines.clear();
    }

    private static void flushTextBuf(List<Block> blocks, StringBuilder buf) {
        String text = trimEdges(buf.toString());
        if (!text.isEmpty()) {
            blocks.add(Block.text(text));
        }
        buf.setLength(0);
    }

    private static void flushCode(List<Block> blocks, StringBuilder buf, String lang) {
        String code = buf.toString();
        if (code.isEmpty() && lang == null) {
            return;
        }
        // 去掉末尾多余空行，保留内部空行
        int end = code.length();
        while (end > 0 && (code.charAt(end - 1) == '\n' || code.charAt(end - 1) == '\r')) {
            end--;
        }
        blocks.add(Block.code(code.substring(0, end), lang));
        buf.setLength(0);
    }

    /** 当前行是表头、下一行是对齐分隔行时，认定为表格开始。 */
    private static boolean isTableStart(List<String> lines, int index) {
        if (index + 1 >= lines.size()) {
            return false;
        }
        if (!isRowLine(lines.get(index)) || !isSeparator(lines.get(index + 1))) {
            return false;
        }
        return splitRow(lines.get(index)).size() == splitRow(lines.get(index + 1)).size();
    }

    /** 形如 |---|:---:|---:| 的分隔行。 */
    private static boolean isSeparator(String line) {
        String t = line == null ? "" : line.trim();
        if (t.isEmpty() || t.indexOf('-') < 0) {
            return false;
        }
        List<String> cells = splitRow(t);
        if (cells.isEmpty()) {
            return false;
        }
        for (String cell : cells) {
            if (!isDashCell(cell)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDashCell(String cell) {
        int i = 0;
        int n = cell.length();
        if (i < n && cell.charAt(i) == ':') {
            i++;
        }
        int dashes = 0;
        while (i < n && cell.charAt(i) == '-') {
            dashes++;
            i++;
        }
        if (dashes < 1) {
            return false;
        }
        if (i < n && cell.charAt(i) == ':') {
            i++;
        }
        return i == n;
    }

    private static boolean isRowLine(String line) {
        String t = line == null ? "" : line.trim();
        return !t.isEmpty() && t.indexOf('|') >= 0;
    }

    /** 拆分一行表格单元格（去掉首尾竖线，还原 \| 转义）。 */
    private static List<String> splitRow(String line) {
        String t = line == null ? "" : line.trim();
        if (t.startsWith("|")) {
            t = t.substring(1);
        }
        if (t.endsWith("|") && !t.isEmpty()) {
            t = t.substring(0, t.length() - 1);
        }
        String[] parts = t.split("\\|", -1);
        List<String> cells = new ArrayList<>();
        for (String part : parts) {
            cells.add(part.trim().replace("\\|", "|"));
        }
        return cells;
    }

    private static int[] parseAlign(String separatorLine, int columnCount) {
        List<String> cells = splitRow(separatorLine);
        int[] align = new int[columnCount];
        for (int i = 0; i < columnCount; i++) {
            String cell = i < cells.size() ? cells.get(i) : "";
            boolean left = cell.startsWith(":");
            boolean right = cell.endsWith(":");
            if (left && right) {
                align[i] = ALIGN_CENTER;
            } else if (right) {
                align[i] = ALIGN_RIGHT;
            } else {
                align[i] = ALIGN_LEFT;
            }
        }
        return align;
    }

    /** 保证每行单元格数量与表头一致（少补空、多截断）。 */
    private static List<String> normalize(List<String> row, int columnCount) {
        List<String> result = new ArrayList<>(columnCount);
        for (int i = 0; i < columnCount; i++) {
            result.add(i < row.size() ? row.get(i) : "");
        }
        return result;
    }

    private static String trimEdges(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }
}
