package com.bookkeeping.bill;

import com.bookkeeping.util.CsvUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 把上传字节读成二维表。xlsx 走表格读取；csv 先判断 UTF-8，读不出表头再试 GBK。
 * 支付宝个人对账 csv 通常是 GBK。
 */
public final class BillFiles {

    private static final Charset GBK = Charset.forName("GBK");

    private BillFiles() {
    }

    public static boolean isSpreadsheet(byte[] bytes, String filename) {
        if (XlsxGrid.isZip(bytes)) {
            return true;
        }
        return filename != null && filename.toLowerCase().endsWith(".xlsx");
    }

    public static List<List<String>> read(byte[] bytes, String filename) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return List.of();
        }
        if (isSpreadsheet(bytes, filename)) {
            if (!XlsxGrid.isZip(bytes)) {
                throw new IOException("不是有效的 xlsx");
            }
            return XlsxGrid.read(bytes);
        }
        List<List<String>> grid = toGrid(decode(bytes));
        if (grid.size() > XlsxGrid.MAX_ROWS) {
            throw new IOException("账单行数过多");
        }
        return grid;
    }

    /** 供本应用 CSV 路径使用：得到可直接交给 {@link CsvUtil#parse} 的文本。 */
    public static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (validUtf8(bytes)) {
            String utf8 = new String(bytes, StandardCharsets.UTF_8);
            if (utf8.contains("交易时间") || utf8.contains("日期")) {
                return utf8;
            }
            String gbk = new String(bytes, GBK);
            if (gbk.contains("交易时间") && !utf8.contains("交易时间")) {
                return gbk;
            }
            return utf8;
        }
        return new String(bytes, GBK);
    }

    private static boolean validUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static List<List<String>> toGrid(String text) {
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        if (prefersTab(text)) {
            return parseTsv(text);
        }
        return CsvUtil.parse(text);
    }

    /** 表头行里制表符多于逗号时按 TSV 读，避免整行被当成一列。 */
    private static boolean prefersTab(String text) {
        int seen = 0;
        for (String line : text.split("\r?\n", 80)) {
            if (line.contains("交易时间")) {
                return count(line, '\t') > count(line, ',');
            }
            if (++seen >= 80) {
                break;
            }
        }
        return false;
    }

    private static int count(String text, char c) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static List<List<String>> parseTsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        for (String line : text.split("\r?\n", -1)) {
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            String[] parts = line.split("\t", -1);
            List<String> row = new ArrayList<>(parts.length);
            for (String part : parts) {
                String value = part;
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1).replace("\"\"", "\"");
                }
                row.add(value);
            }
            rows.add(row);
        }
        while (!rows.isEmpty() && isBlank(rows.get(rows.size() - 1))) {
            rows.remove(rows.size() - 1);
        }
        return rows;
    }

    private static boolean isBlank(List<String> row) {
        for (String cell : row) {
            if (cell != null && !cell.isBlank()) {
                return false;
            }
        }
        return true;
    }
}
