package com.hs.contract.service.engine;

import org.apache.poi.xwpf.usermodel.*;
import org.w3c.dom.Node;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.regex.Pattern;

/** Normalizes only the duplicated marker suffix in a working copy, never handwritten rendered text. */
final class LeaseDurationRenderCleanup {
    private LeaseDurationRenderCleanup() {}
    private static final String SPACE = "[\\s\\u00a0]";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern PAIR = Pattern.compile(
        "\\{\\{\\s*lease\\.durationText\\s*}}(" + SPACE + "*\\(" + SPACE
        + "*\\{\\{\\s*lease\\.durationMonths\\s*}}" + SPACE + "+tháng" + SPACE + "*\\))", FLAGS);

    static byte[] normalize(byte[] source, Map<String, Object> model) throws Exception {
        String text = Objects.toString(ContractRenderService.resolvePath(model, "lease.durationText"), "");
        String months = Objects.toString(ContractRenderService.resolvePath(model, "lease.durationMonths"), "").trim();
        if (!months.matches("[1-9]\\d*") || !Pattern.compile("(?<!\\d)" + Pattern.quote(months)
                + SPACE + "+tháng\\b", FLAGS).matcher(text).find())
            return source;

        try (var document = new XWPFDocument(new ByteArrayInputStream(source))) {
            var targets = new ArrayList<XWPFParagraph>();
            collect(document, targets);
            document.getHeaderList().forEach(header -> collect(header, targets));
            document.getFooterList().forEach(footer -> collect(footer, targets));
            if (targets.isEmpty()) return source;
            targets.forEach(LeaseDurationRenderCleanup::normalize);
            var output = new ByteArrayOutputStream();
            document.write(output);
            return output.toByteArray();
        }
    }

    private static void collect(IBody body, List<XWPFParagraph> targets) {
        for (var element : body.getBodyElements()) {
            if (element instanceof XWPFParagraph paragraph && PAIR.matcher(paragraph.getText()).find())
                targets.add(paragraph);
            else if (element instanceof XWPFTable table)
                for (var row : table.getRows()) for (var cell : row.getTableCells()) collect(cell, targets);
        }
    }

    private static void normalize(XWPFParagraph paragraph) {
        var nodes = new ArrayList<Node>();
        collectText(paragraph.getCTP().getDomNode(), nodes);
        var text = new StringBuilder();
        nodes.forEach(node -> text.append(value(node)));
        var matcher = PAIR.matcher(text);
        var spans = new ArrayList<int[]>();
        while (matcher.find()) spans.add(new int[]{matcher.start(1), matcher.end(1)});
        // Right to left keeps earlier character offsets stable, even for several pairs in one paragraph.
        for (int i = spans.size() - 1; i >= 0; i--) {
            int offset = 0;
            for (var node : nodes) {
                String current = value(node);
                int end = offset + current.length();
                int from = Math.max(spans.get(i)[0], offset), to = Math.min(spans.get(i)[1], end);
                if (from < to) node.getFirstChild().setNodeValue(
                    current.substring(0, from - offset) + current.substring(to - offset));
                offset = end;
            }
        }
    }

    private static void collectText(Node node, List<Node> text) {
        if ("http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(node.getNamespaceURI())
                && "t".equals(node.getLocalName())) text.add(node);
        else for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) collectText(child, text);
    }

    private static String value(Node node) {
        return node.getFirstChild() == null ? "" : Objects.toString(node.getFirstChild().getNodeValue(), "");
    }
}
