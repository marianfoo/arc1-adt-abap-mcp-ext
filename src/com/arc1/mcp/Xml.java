package com.arc1.mcp;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Minimal, dependency-free XML reader over the JDK's {@code javax.xml} DOM.
 *
 * <p>ADT REST responses are XML; this lets tools pull fields out <em>by local
 * name</em> without hard-coding namespace prefixes (which differ across ADT
 * releases, e.g. {@code adtcore:} vs {@code chkrun:}). There is no third-party
 * dependency — {@code javax.xml} ships in the JDK, so decision D8 (no third-party
 * deps) still holds.
 *
 * <p>Parsing is hardened (secure processing on, DOCTYPE/external entities off)
 * even though responses come from the authenticated SAP backend — it's one line
 * of defence and costs nothing.
 */
final class Xml {

    private Xml() {
    }

    /** Parse a UTF-8 XML document. Throws on malformed input — callers fall back. */
    static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setExpandEntityReferences(false);
        try {
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Throwable ignore) {
            // older parsers may not support every feature; secure processing is the key one
        }
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** All descendant elements (any depth) whose local name equals {@code localName}. */
    static List<Element> elements(Node ctx, String localName) {
        List<Element> out = new ArrayList<>();
        if (ctx != null) {
            collect(ctx, localName, out);
        }
        return out;
    }

    private static void collect(Node node, String localName, List<Element> out) {
        NodeList kids = node.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element e = (Element) n;
                if (localName.equals(localName(e))) {
                    out.add(e);
                }
                collect(e, localName, out);
            }
        }
    }

    /** Attribute value by local name, ignoring namespace prefix. {@code null} if absent. */
    static String attr(Element e, String localName) {
        if (e == null) {
            return null;
        }
        NamedNodeMap attrs = e.getAttributes();
        if (attrs == null) {
            return null;
        }
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            if (localName.equals(localName(a))) {
                return a.getNodeValue();
            }
        }
        return null;
    }

    /** Trimmed text content of an element ({@code null}-safe). */
    static String text(Element e) {
        if (e == null) {
            return null;
        }
        String t = e.getTextContent();
        return t == null ? null : t.trim();
    }

    /** First descendant element by local name, or {@code null}. */
    static Element first(Node ctx, String localName) {
        List<Element> all = elements(ctx, localName);
        return all.isEmpty() ? null : all.get(0);
    }

    private static String localName(Node n) {
        String ln = n.getLocalName();
        if (ln != null) {
            return ln;
        }
        String name = n.getNodeName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }
}
