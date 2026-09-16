package com.bookkeeping.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CsvUtil 解析/转义单测：引号包裹、引号转义、字段内换行、\r\n 与 \n 兼容、BOM 剥离、末尾空行忽略。
 */
class CsvUtilTest {

    @Test
    void parse_simpleRows() {
        List<List<String>> rows = CsvUtil.parse("a,b,c\n1,2,3\n");
        assertEquals(2, rows.size());
        assertEquals(List.of("a", "b", "c"), rows.get(0));
        assertEquals(List.of("1", "2", "3"), rows.get(1));
    }

    @Test
    void parse_quotedAndEscapedAndEmbeddedNewline() {
        // 第二字段含逗号与转义引号；第三字段含换行
        String csv = "x,\"a,b\"\"c\",\"line1\nline2\"\r\n";
        List<List<String>> rows = CsvUtil.parse(csv);
        assertEquals(1, rows.size());
        assertEquals(List.of("x", "a,b\"c", "line1\nline2"), rows.get(0));
    }

    @Test
    void parse_stripsBomAndIgnoresTrailingEmptyLine() {
        List<List<String>> rows = CsvUtil.parse("\uFEFFh1,h2\r\nv1,v2\r\n");
        assertEquals(2, rows.size());
        assertEquals("h1", rows.get(0).get(0));
    }

    @Test
    void toCsv_escapesAndPrependsBom() {
        String csv = CsvUtil.toCsv(List.of(List.of("日期", "备注"), List.of("2026-08-15", "含,逗号\"引号\"")));
        assertTrue(csv.startsWith(CsvUtil.BOM));
        assertTrue(csv.contains("\"含,逗号\"\"引号\"\"\""));
        assertTrue(csv.endsWith("\r\n"));
    }

    @Test
    void roundTrip_preservesCells() {
        List<List<String>> original = List.of(
                List.of("日期", "备注"),
                List.of("2026-08-15", "午餐, 加了\"辣椒\"\n第二行")
        );
        List<List<String>> parsed = CsvUtil.parse(CsvUtil.toCsv(original));
        assertEquals(original, parsed);
    }
}
