package com.vedicmeet.appserver.admin;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Minimal dependency-free XLSX reader for the payout template. It reads only the first worksheet,
 * shared strings, inline strings and primitive values. Macros, formulas and external entities are
 * intentionally ignored.
 */
final class SimpleXlsxReader {

    private SimpleXlsxReader() {}

    static List<List<String>> firstSheet(byte[] bytes) {
        try {
            Map<String, byte[]> entries = unzip(bytes);
            byte[] sheet = entries.get("xl/worksheets/sheet1.xml");
            if (sheet == null) throw new IllegalArgumentException("The file has no first worksheet.");
            List<String> shared = sharedStrings(entries.get("xl/sharedStrings.xml"));
            org.w3c.dom.Document xml = parse(sheet);
            List<List<String>> rows = new ArrayList<>();
            NodeList rowNodes = xml.getElementsByTagNameNS("*", "row");
            for (int i = 0; i < rowNodes.getLength(); i++) {
                Element row = (Element) rowNodes.item(i);
                Map<Integer, String> values = new HashMap<>();
                int max = -1;
                NodeList cells = row.getElementsByTagNameNS("*", "c");
                for (int j = 0; j < cells.getLength(); j++) {
                    Element cell = (Element) cells.item(j);
                    int column = column(cell.getAttribute("r"));
                    max = Math.max(max, column);
                    String type = cell.getAttribute("t");
                    String raw = firstText(cell, "v");
                    if ("inlineStr".equals(type)) raw = firstText(cell, "t");
                    else if ("s".equals(type) && !raw.isBlank()) raw = shared.get(Integer.parseInt(raw));
                    values.put(column, raw);
                }
                if (max >= 0) {
                    List<String> output = new ArrayList<>();
                    for (int col = 0; col <= max; col++) output.add(values.getOrDefault(col, ""));
                    if (output.stream().anyMatch(v -> !v.isBlank())) rows.add(output);
                }
            }
            return rows;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid Excel file", error);
        }
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            long total = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    total += read;
                    if (total > 30L * 1024 * 1024) throw new IllegalArgumentException("Expanded Excel file is too large.");
                    out.write(buffer, 0, read);
                }
                entries.put(entry.getName(), out.toByteArray());
            }
        }
        return entries;
    }

    private static List<String> sharedStrings(byte[] bytes) throws Exception {
        if (bytes == null) return List.of();
        org.w3c.dom.Document xml = parse(bytes);
        NodeList items = xml.getElementsByTagNameNS("*", "si");
        List<String> result = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) result.add(items.item(i).getTextContent());
        return result;
    }

    private static org.w3c.dom.Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private static String firstText(Element parent, String localName) {
        NodeList list = parent.getElementsByTagNameNS("*", localName);
        return list.getLength() == 0 ? "" : list.item(0).getTextContent();
    }

    private static int column(String reference) {
        int value = 0;
        for (int i = 0; i < reference.length() && Character.isLetter(reference.charAt(i)); i++) {
            value = value * 26 + Character.toUpperCase(reference.charAt(i)) - 'A' + 1;
        }
        return Math.max(0, value - 1);
    }
}
