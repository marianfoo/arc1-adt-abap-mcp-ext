package com.arc1.mcp;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.sap.adt.mcp.core.AdtMcpToolCallResultBuilder;
import com.sap.adt.mcp.core.IAdtMCPTool;
import com.sap.adt.mcp.core.IAdtMcpToolCallResult;

/**
 * Where-used / find references — "what calls or references this object?"
 *
 *   POST /sap/bc/adt/repository/informationsystem/usageReferences?uri=<objectUri>
 *   Content-Type: application/vnd.sap.adt.repository.usagereferences.request.v1+xml
 *   Accept:       application/vnd.sap.adt.repository.usagereferences.result.v1+xml
 *
 * Optional line/column scope the query to a member (member-level where-used) by
 * appending #start=line,column to the uri.
 *
 * Read-only. Best-effort structured parse; rawXml is returned when the parse is
 * empty (the exact vendor result shape can vary across ADT releases — confirm
 * with arc1_sap_http_post if a backend returns an unexpected layout).
 */
public class Arc1SapWhereUsedTool implements IAdtMCPTool {

    private static final String BASE = "/sap/bc/adt/repository/informationsystem/usageReferences";
    private static final String CONTENT_TYPE =
        "application/vnd.sap.adt.repository.usagereferences.request.v1+xml";
    private static final String ACCEPT =
        "application/vnd.sap.adt.repository.usagereferences.result.v1+xml";
    private static final String REQUEST_BODY =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<usagereferences:usageReferenceRequest"
        + " xmlns:usagereferences=\"http://www.sap.com/adt/ris/usageReferences\">"
        + "<usagereferences:affectedObjects/>"
        + "</usagereferences:usageReferenceRequest>";

    @Override
    public String getName() {
        return "arc1_sap_where_used";
    }

    @Override
    public String getDescription() {
        return "Where-used list for an ABAP object (find references / impact analysis): "
             + "what references the given object. Pass objectUri; optionally line+column "
             + "to scope to a member. Returns referencing objects (name, type, uri, parentUri). "
             + "Read-only. Capped by maxResults; rawXml returned when the structured parse is empty.";
    }

    @Override
    public String getInputSchema() {
        return "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            +   "\"destination\":{\"type\":\"string\",\"description\":\"ADT destination ID\"},"
            +   "\"objectUri\":{\"type\":\"string\","
            +     "\"description\":\"ADT URI of the object, e.g. /sap/bc/adt/oo/classes/CL_FOO\"},"
            +   "\"line\":{\"type\":\"integer\",\"description\":\"Optional 1-based line to scope to a member.\"},"
            +   "\"column\":{\"type\":\"integer\",\"description\":\"Optional 0-based column (used with line).\"},"
            +   "\"maxResults\":{\"type\":\"integer\",\"description\":\"Max references (default 100, cap 500).\"}"
            + "},"
            + "\"required\":[\"destination\",\"objectUri\"]"
            + "}";
    }

    @Override
    public IAdtMcpToolCallResult execute(String jsonInput) {
        try {
            String destination = Json.readString(jsonInput, "destination");
            String objectUri = Json.readString(jsonInput, "objectUri");
            Integer line = Json.readInt(jsonInput, "line");
            Integer column = Json.readInt(jsonInput, "column");
            Integer maxResults = Json.readInt(jsonInput, "maxResults");

            if (destination == null || destination.isEmpty()) {
                return error("Missing required field: destination");
            }
            if (objectUri == null || objectUri.isEmpty()) {
                return error("Missing required field: objectUri");
            }
            int max = (maxResults == null || maxResults.intValue() <= 0)
                ? 100 : Math.min(maxResults.intValue(), 500);

            String targetUri = objectUri;
            if (line != null) {
                targetUri = targetUri + "#start=" + line + "," + (column == null ? 0 : column.intValue());
            }
            String uri = BASE + "?uri=" + enc(targetUri);

            AdtHttp.Response resp = AdtHttp.post(destination, uri, ACCEPT, CONTENT_TYPE,
                REQUEST_BODY.getBytes(StandardCharsets.UTF_8));
            String body = resp.bodyAsString();

            if (resp.status >= 400) {
                return raw(resp.status, uri, body, "HTTP " + resp.status);
            }
            if (resp.truncated) {
                return raw(resp.status, uri, body,
                    "response exceeded 256 KB and was truncated; lower maxResults or scope to a member");
            }

            StringBuilder sb = new StringBuilder(body.length() + 256);
            sb.append("{\"status\":").append(resp.status);
            sb.append(",\"uri\":").append(Json.str(uri));
            try {
                Document doc = Xml.parse(resp.body);
                List<Element> refs = Xml.elements(doc, "referencedObject");
                int total = refs.size();
                int shown = Math.min(total, max);
                sb.append(",\"count\":").append(total);
                sb.append(",\"truncatedList\":").append(total > shown);
                sb.append(",\"references\":[");
                for (int i = 0; i < shown; i++) {
                    Element ref = refs.get(i);
                    Element inner = Xml.first(ref, "objectReference"); // adtcore:objectReference, if present
                    if (i > 0) sb.append(",");
                    sb.append("{");
                    sb.append("\"name\":").append(Json.str(pick(ref, inner, "name"))).append(",");
                    sb.append("\"type\":").append(Json.str(pick(ref, inner, "type"))).append(",");
                    sb.append("\"uri\":").append(Json.str(pick(ref, inner, "uri"))).append(",");
                    sb.append("\"parentUri\":").append(Json.str(Xml.attr(ref, "parentUri"))).append(",");
                    String usage = Xml.attr(ref, "usageInformation");
                    if (usage == null) {
                        usage = Xml.text(Xml.first(ref, "usageInformation"));
                    }
                    sb.append("\"usageInformation\":").append(Json.str(usage));
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
            return error("arc1_sap_where_used failed: " + t.getClass().getSimpleName()
                + ": " + String.valueOf(t.getMessage()));
        }
    }

    /** Prefer an attribute on the inner objectReference, fall back to the outer element. */
    private static String pick(Element outer, Element inner, String localName) {
        String v = inner == null ? null : Xml.attr(inner, localName);
        return v != null ? v : Xml.attr(outer, localName);
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
