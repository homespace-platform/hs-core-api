package com.hs.contract.service.engine;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.xwpf.usermodel.XWPFRelation;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/** Preserve normal word spacing on newline-delimited data in justified Word paragraphs. */
final class WordLineBreakCompatibility {
    private static final String WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private WordLineBreakCompatibility() {}

    static byte[] normalize(byte[] rendered) throws Exception {
        // Edit the final OPC package: XWPFSettings caches its XML and would overwrite
        // a PackagePart edit made before XWPFDocument.write(). Source templates stay intact.
        try (var pkg = OPCPackage.open(new ByteArrayInputStream(rendered))) {
            var parts = pkg.getPartsByContentType(XWPFRelation.SETTINGS.getContentType());
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var parser = factory.newDocumentBuilder();
            org.w3c.dom.Document xml;
            org.apache.poi.openxml4j.opc.PackagePart part;
            if (parts.isEmpty()) {
                var name = PackagingURIHelper.createPartName("/word/settings.xml");
                part = pkg.createPart(name, XWPFRelation.SETTINGS.getContentType());
                var document = pkg.getPartsByContentType(XWPFRelation.DOCUMENT.getContentType()).getFirst();
                document.addRelationship(name, TargetMode.INTERNAL, XWPFRelation.SETTINGS.getRelation());
                xml = parser.newDocument();
                xml.appendChild(xml.createElementNS(WORD_NS, "w:settings"));
            } else {
                part = parts.getFirst();
                try (var input = part.getInputStream()) {
                    xml = parser.parse(input);
                }
            }
            var settings = xml.getDocumentElement();
            var compatNodes = settings.getElementsByTagNameNS(WORD_NS,"compat");
            var compat = compatNodes.getLength() == 0 ? xml.createElementNS(WORD_NS,"w:compat")
                    : (org.w3c.dom.Element) compatNodes.item(0);
            if (compat.getParentNode() == null) settings.appendChild(compat);
            var flags = compat.getElementsByTagNameNS(WORD_NS,"doNotExpandShiftReturn");
            var flag = flags.getLength() == 0 ? xml.createElementNS(WORD_NS,"w:doNotExpandShiftReturn")
                    : (org.w3c.dom.Element) flags.item(0);
            if (flag.getParentNode() == null) compat.appendChild(flag);
            flag.setAttributeNS(WORD_NS,"w:val","true");
            try (var output = part.getOutputStream()) {
                var transforms = TransformerFactory.newInstance();
                transforms.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                transforms.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
                transforms.newTransformer().transform(new DOMSource(xml), new StreamResult(output));
            }
            var output = new ByteArrayOutputStream();
            pkg.save(output);
            return output.toByteArray();
        }
    }
}
