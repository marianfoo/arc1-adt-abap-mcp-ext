package com.arc1.mcp;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Minimal namespace-agnostic DOM helper over the JDK's built-in
 * {@code javax.xml} parser. Replaces the per-tool regex parsing that crept in
 * with {@code arc1_sap_list_transports}: ADT responses are namespaced XML, so
 * lookups here match on <em>local</em> names and ignore the {@code adtcore:},
 * {@code ioc:} … prefixes the backend happens to use.
 *
 * <p>This adds no third-party dependency (D8) — {@code javax.xml} ships in the
 * JRE. The parser is hardened against XXE: DOCTYPE declarations and external
 * entities are refused, which is appropriate since we only ever parse trusted
 * ADT responses but should never be coerced into fetching anything.
 */
final class Xml {

    private Xml() {
    }

    /** Parse XML text into a namespace-aware DOM, or throw on malformed input. */
    static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        // XXE hardening — refuse DOCTYPE and any external resolution.
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        return b.parse(new ByteArrayInputStream(bytes));
    }

    /** All descendant elements (any depth) whose local name equals {@code localName}. */
    static List<Element> elementsByLocalName(Node root, String localName) {
        List<Element> out = new ArrayList<>();
        collect(root, localName, out);
        return out;
    }

    private static void collect(Node node, String localName, List<Element> out) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c.getNodeType() == Node.ELEMENT_NODE) {
                if (localName.equals(localNameOf(c))) {
                    out.add((Element) c);
                }
                collect(c, localName, out);
            }
        }
    }

    /** First direct or nested child element with the given local name, or null. */
    static Element firstByLocalName(Node root, String localName) {
        List<Element> all = elementsByLocalName(root, localName);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Attribute value matched by local name, ignoring namespace prefix
     * (so {@code adtcore:uri} is found by {@code attr(e, "uri")}). Returns null
     * if absent.
     */
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
            if (localName.equals(localNameOf(a))) {
                return a.getNodeValue();
            }
        }
        return null;
    }

    /** Trimmed text content of an element, or null. */
    static String text(Element e) {
        if (e == null) {
            return null;
        }
        String t = e.getTextContent();
        return t == null ? null : t.trim();
    }

    private static String localNameOf(Node n) {
        String ln = n.getLocalName();
        if (ln != null) {
            return ln;
        }
        // Fallback for non-namespace-aware nodes: strip any prefix.
        String name = n.getNodeName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }
}
