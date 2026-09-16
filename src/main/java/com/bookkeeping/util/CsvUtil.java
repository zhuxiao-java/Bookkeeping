package com.bookkeeping.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量 CSV 读写工具（无第三方依赖）：与前端 utils/csv.ts 的转义规则对齐
 * （含 BOM、CRLF 换行、逗号/引号/换行的字段加双引号并转义内部引号）。
 *
 * @author zhuxiao
 */
public final class CsvUtil {

    /** Excel 直接打开中文不乱码的 BOM 头 */
    public static final String BOM = "\uFEFF";

    private CsvUtil() {
    }

    /**
     * 转义单个单元格：含逗号/引号/换行时用双引号包裹，内部引号翻倍。
     */
    public static String escapeCell(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * 把若干行拼装为完整 CSV 文本（带 BOM、CRLF）。
     */
    public static String toCsv(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder(BOM);
        for (List<String> row : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    line.append(',');
                }
                line.append(escapeCell(row.get(i)));
            }
            sb.append(line).append("\r\n");
        }
        return sb.toString();
    }

    /**
     * 解析 CSV 文本为二维字符串表：状态机处理引号包裹、引号转义（""）、
     * 字段内换行；兼容 \r\n 与 \n；自动剥离首部 BOM；忽略末尾空行。
     */
    public static List<List<String>> parse(String content) {
        List<List<String>> rows = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return rows;
        }
        if (content.charAt(0) == '\uFEFF') {
            content = content.substring(1);
        }
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                current.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                current.add(field.toString());
                field.setLength(0);
                rows.add(current);
                current = new ArrayList<>();
            } else if (c != '\r') {
                field.append(c);
            }
        }
        // 收尾：最后一行无换行结束，或有残留字段
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            rows.add(current);
        }
        return rows;
    }
}
