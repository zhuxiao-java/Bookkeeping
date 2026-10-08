package com.bookkeeping.bill;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 只读 xlsx 第一个工作表。微信个人对账从 2025 年 7 月起常导出为 xlsx，
 * 单元格大多是共享字符串或内联字符串，不引入 POI。
 */
final class XlsxGrid {

    static final int MAX_ROWS = 20000;

    private XlsxGrid() {
    }

    static boolean isZip(byte[] bytes) {
        return bytes != null && bytes.length >= 4
                && bytes[0] == 'P' && bytes[1] == 'K'
                && (bytes[2] == 3 || bytes[2] == 5 || bytes[2] == 7);
    }

    static List<List<String>> read(byte[] bytes) throws IOException {
        Map<String, byte[]> entries = unzip(bytes);
        byte[] sharedXml = find(entries, "sharedstrings.xml");
        Map<Integer, String> shared = sharedXml == null ? Map.of() : sharedStrings(sharedXml);
        byte[] sheetXml = sheet(entries);
        if (sheetXml == null) {
            throw new IOException("不是有效的 xlsx");
        }
        return sheetRows(sheetXml, shared);
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                while (name.startsWith("/")) {
                    name = name.substring(1);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                zin.transferTo(out);
                entries.put(name.toLowerCase(Locale.ROOT), out.toByteArray());
            }
        }
        return entries;
    }

    private static byte[] find(Map<String, byte[]> entries, String suffix) {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (entry.getKey().endsWith(suffix)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static byte[] sheet(Map<String, byte[]> entries) {
        byte[] preferred = entries.get("xl/worksheets/sheet1.xml");
        if (preferred != null) {
            return preferred;
        }
        String best = null;
        for (String name : entries.keySet()) {
            if (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml")
                    && (best == null || name.compareTo(best) < 0)) {
                best = name;
            }
        }
        return best == null ? null : entries.get(best);
    }

    private static Map<Integer, String> sharedStrings(byte[] xml) throws IOException {
        Map<Integer, String> map = new HashMap<>();
        int index = 0;
        for (Element si : descendants(parse(xml), "si")) {
            map.put(index++, textOf(si));
        }
        return map;
    }

    private static List<List<String>> sheetRows(byte[] xml, Map<Integer, String> shared) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        for (Element row : descendants(parse(xml), "row")) {
            if (rows.size() >= MAX_ROWS) {
                throw new IOException("账单行数过多");
            }
            int rowNum = intAttr(row, "r");
            List<String> cells = readCells(row, shared);
            if (rowNum > 0) {
                while (rows.size() < rowNum - 1 && rows.size() < MAX_ROWS) {
                    rows.add(new ArrayList<>());
                }
                if (rows.size() == rowNum - 1) {
                    rows.add(cells);
                } else if (rowNum - 1 < rows.size()) {
                    rows.set(rowNum - 1, cells);
                }
            } else {
                rows.add(cells);
            }
        }
        return rows;
    }

    private static List<String> readCells(Element row, Map<Integer, String> shared) {
        List<String> cells = new ArrayList<>();
        int sequential = 0;
        for (Element cell : childElements(row, "c")) {
            int col = colIndex(cell.getAttribute("r"));
            if (col < 0) {
                col = sequential;
            }
            sequential = col + 1;
            while (cells.size() <= col) {
                cells.add("");
            }
            cells.set(col, cellValue(cell, shared));
        }
        return cells;
    }

    private static String cellValue(Element cell, Map<Integer, String> shared) {
        String type = cell.getAttribute("t");
        if ("inlineStr".equals(type)) {
            return textOf(cell);
        }
        String raw = directText(cell, "v");
        if ("s".equals(type)) {
            try {
                return shared.getOrDefault(Integer.parseInt(raw.trim()), "");
            } catch (NumberFormatException e) {
                return "";
            }
        }
        return raw == null ? "" : raw;
    }

    private static String directText(Element parent, String name) {
        for (Element child : childElements(parent, name)) {
            return child.getTextContent() == null ? "" : child.getTextContent();
        }
        return "";
    }

    private static String textOf(Element parent) {
        StringBuilder sb = new StringBuilder();
        appendText(parent, sb);
        return sb.toString();
    }

    private static void appendText(Node node, StringBuilder sb) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element element)) {
                continue;
            }
            if ("t".equals(local(element))) {
                if (element.getTextContent() != null) {
                    sb.append(element.getTextContent());
                }
            } else {
                appendText(element, sb);
            }
        }
    }

    private static List<Element> descendants(Document doc, String name) {
        List<Element> found = new ArrayList<>();
        collect(doc.getDocumentElement(), name, found);
        return found;
    }

    private static void collect(Element element, String name, List<Element> found) {
        if (element == null) {
            return;
        }
        if (name.equals(local(element))) {
            found.add(element);
        }
        for (Element child : childElements(element)) {
            collect(child, name, found);
        }
    }

    private static List<Element> childElements(Element parent, String name) {
        List<Element> list = new ArrayList<>();
        for (Element child : childElements(parent)) {
            if (name.equals(local(child))) {
                list.add(child);
            }
        }
        return list;
    }

    private static List<Element> childElements(Node parent) {
        List<Element> list = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) {
                list.add(element);
            }
        }
        return list;
    }

    private static String local(Node node) {
        String name = node.getLocalName();
        if (name == null || name.isEmpty()) {
            name = node.getNodeName();
        }
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static int intAttr(Element element, String name) {
        String raw = element.getAttribute(name);
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A -> 0，B -> 1，AA -> 26。没有列字母时返回 -1。 */
    private static int colIndex(String ref) {
        if (ref == null || ref.isEmpty()) {
            return -1;
        }
        int n = 0;
        int i = 0;
        for (; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                n = n * 26 + (c - 'A' + 1);
            } else if (c >= 'a' && c <= 'z') {
                n = n * 26 + (c - 'a' + 1);
            } else {
                break;
            }
        }
        return i == 0 ? -1 : n - 1;
    }

    private static Document parse(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new IOException("无法解析表格", e);
        }
    }
}
