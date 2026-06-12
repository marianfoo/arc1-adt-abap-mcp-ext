package com.arc1.mcp;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.sap.adt.mcp.core.AdtMcpToolCallResultBuilder;
import com.sap.adt.mcp.core.IAdtMCPTool;
import com.sap.adt.mcp.core.IAdtMcpToolCallResult;

/**
 * List the contents of an ABAP package (the Project-Explorer node feed).
 *
 *   POST /sap/bc/adt/repository/nodestructure
 *        ?parent_type=DEVC/K&parent_name=<PKG>&withShortDescriptions=true
 *   Content-Type/Accept: application/vnd.sap.as+xml; ...; dataname=com.sap.adt.RepositoryObjectTreeContent
 *   Body: an ASX root-node request.
 *
 * The response is ASX-serialized ABAP (asx:abap rows), not adtcore atom XML; the
 * Xml helper still reads it (it's XML). Read-only. Lets an AI walk a codebase
 * top-down instead of only fuzzy-searching by name.
 */
public class Arc1SapPackageContentsTool implements IAdtMCPTool {

    private static final String BASE = "/sap/bc/adt/repository/nodestructure";
    private static final String CT =
        "application/vnd.sap.as+xml; charset=UTF-8; dataname=com.sap.adt.RepositoryObjectTreeContent";
    private static final String REQUEST_BODY =
        "<asx:abap xmlns:asx=\"http://www.sap.com/abapxml\" version=\"1.0\">"
        + "<asx:values><DATA><TV_NODEKEY>000000</TV_NODEKEY></DATA></asx:values></asx:abap>";

    @Override
    public String getName() {
        return "arc1_sap_package_contents";
    }

    @Override
    public String getDescription() {
        return "List the objects contained in an ABAP package (top-level nodes). "
             + "Pass the package name; returns name, type, uri, description and whether "
             + "each node is expandable. Read-only. Best-effort structured parse of the "
             + "ASX node feed; rawXml returned when parsing yields nothing.";
    }

    @Override
    public String getInputSchema() {
        return "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            +   "\"destination\":{\"type\":\"string\",\"description\":\"ADT destination ID\"},"
            +   "\"package\":{\"type\":\"string\",\"description\":\"Package name, e.g. $TMP or ZARC1_PKG\"},"
            +   "\"maxResults\":{\"type\":\"integer\",\"description\":\"Max nodes (default 200, cap 1000).\"}"
            + "},"
            + "\"required\":[\"destination\",\"package\"]"
            + "}";
    }

    @Override
    public IAdtMcpToolCallResult execute(String jsonInput) {
        try {
            String destination = Json.readString(jsonInput, "destination");
            String pkg = Json.readString(jsonInput, "package");
            Integer maxResults = Json.readInt(jsonInput, "maxResults");

            if (destination == null || destination.isEmpty()) {
                return error("Missing required field: destination");
            }
            if (pkg == null || pkg.isEmpty()) {
                return error("Missing required field: package");
            }
            int max = (maxResults == null || maxResults.intValue() <= 0)
                ? 200 : Math.min(maxResults.intValue(), 1000);

            String uri = BASE + "?parent_type=" + enc("DEVC/K")
                + "&parent_name=" + enc(pkg.toUpperCase())
                + "&withShortDescriptions=true";

            AdtHttp.Response resp = AdtHttp.post(destination, uri, CT, CT,
                REQUEST_BODY.getBytes(StandardCharsets.UTF_8));
            String body = resp.bodyAsString();

            if (resp.status >= 400) {
                return raw(resp.status, uri, body, "HTTP " + resp.status);
            }
            if (resp.truncated) {
                return raw(resp.status, uri, body,
                    "response exceeded 256 KB and was truncated; package too large to list whole");
            }

            StringBuilder sb = new StringBuilder(body.length() + 256);
            sb.append("{\"status\":").append(resp.status);
            sb.append(",\"package\":").append(Json.str(pkg));
            try {
                Document doc = Xml.parse(body);
                // Each node row contains an OBJECT_NAME element; use its parent as the row.
                List<Element> anchors = Xml.elements(doc, "OBJECT_NAME");
                int total = anchors.size();
                int shown = Math.min(total, max);
                sb.append(",\"count\":").append(total);
                sb.append(",\"truncatedList\":").append(total > shown);
                sb.append(",\"nodes\":[");
                for (int i = 0; i < shown; i++) {
                    Node parent = anchors.get(i).getParentNode();
                    Element row = parent instanceof Element ? (Element) parent : anchors.get(i);
                    if (i > 0) sb.append(",");
                    sb.append("{");
                    sb.append("\"name\":").append(Json.str(rowText(row, "OBJECT_NAME"))).append(",");
                    sb.append("\"type\":").append(Json.str(rowText(row, "OBJECT_TYPE"))).append(",");
                    sb.append("\"uri\":").append(Json.str(rowText(row, "OBJECT_URI"))).append(",");
                    sb.append("\"description\":").append(Json.str(rowText(row, "DESCRIPTION"))).append(",");
                    sb.append("\"expandable\":").append(Json.str(rowText(row, "EXPANDABLE")));
                    sb.append("}");
                }
                sb.append("]");
                if (total == 0) {
                    sb.append(",\"rawXml\":").append(Json.str(body));
                }
            } catch (Throwable parseEx) {
                sb.append(",\"parseError\":").append(Json.str(String.valueOf(parseEx.getMessage())));
                sb.append(",\"rawXml\":").append(Json.str(body));
            }
            sb.append("}");

            String json = sb.toString();
            return AdtMcpToolCallResultBuilder.builder()
                .withStructuredContent(json).withContent(json).isError(false).build();
        } catch (Throwable t) {
            return error("arc1_sap_package_contents failed: " + t.getClass().getSimpleName()
                + ": " + String.valueOf(t.getMessage()));
        }
    }

    private static String rowText(Element row, String localName) {
        return Xml.text(Xml.first(row, localName));
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return s;
        }
    }

    private static IAdtMcpToolCallResult raw(int status, String uri, String body, String note) {
        String json = "{\"status\":" + status
            + ",\"uri\":" + Json.str(uri)
            + ",\"note\":" + Json.str(note)
            + ",\"rawXml\":" + Json.str(body) + "}";
        return AdtMcpToolCallResultBuilder.builder()
            .withStructuredContent(json).withContent(json).isError(status >= 400).build();
    }

    private static IAdtMcpToolCallResult error(String msg) {
        return AdtMcpToolCallResultBuilder.builder().withContent(msg).isError(true).build();
    }
}
