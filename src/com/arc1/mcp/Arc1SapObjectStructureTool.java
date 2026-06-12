package com.arc1.mcp;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.sap.adt.mcp.core.AdtMcpToolCallResultBuilder;
import com.sap.adt.mcp.core.IAdtMCPTool;
import com.sap.adt.mcp.core.IAdtMcpToolCallResult;

/**
 * Outline / object structure of one ABAP object (methods, attributes, events,
 * includes, …) with their URIs — the data behind the ADT editor outline.
 *
 *   GET <objectUri>/objectstructure?version=active&withShortDescriptions=true
 *
 * Read-only. Pairs with arc1_sap_find_definition: gives an AI the member names +
 * URIs to target. Best-effort structured parse; rawXml when parsing is empty.
 * Non-OO objects may return 404 — the backend status is surfaced as-is.
 */
public class Arc1SapObjectStructureTool implements IAdtMCPTool {

    @Override
    public String getName() {
        return "arc1_sap_object_structure";
    }

    @Override
    public String getDescription() {
        return "Outline of an ABAP object: its components (methods, attributes, events, "
             + "includes) with name, type and URI. Pass objectUri (e.g. an OO class). "
             + "Read-only. Best-effort structured parse; rawXml returned when parsing is empty.";
    }

    @Override
    public String getInputSchema() {
        return "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            +   "\"destination\":{\"type\":\"string\",\"description\":\"ADT destination ID\"},"
            +   "\"objectUri\":{\"type\":\"string\","
            +     "\"description\":\"ADT URI of the object, e.g. /sap/bc/adt/oo/classes/CL_FOO\"},"
            +   "\"version\":{\"type\":\"string\",\"enum\":[\"active\",\"inactive\"],"
            +     "\"description\":\"Source version. Default active.\"}"
            + "},"
            + "\"required\":[\"destination\",\"objectUri\"]"
            + "}";
    }

    @Override
    public IAdtMcpToolCallResult execute(String jsonInput) {
        try {
            String destination = Json.readString(jsonInput, "destination");
            String objectUri = Json.readString(jsonInput, "objectUri");
            String version = Json.readString(jsonInput, "version");

            if (destination == null || destination.isEmpty()) {
                return error("Missing required field: destination");
            }
            if (objectUri == null || objectUri.isEmpty()) {
                return error("Missing required field: objectUri");
            }
            String ver = "inactive".equalsIgnoreCase(version) ? "inactive" : "active";
            String base = objectUri.endsWith("/")
                ? objectUri.substring(0, objectUri.length() - 1) : objectUri;
            String uri = base + "/objectstructure?version=" + ver + "&withShortDescriptions=true";

            AdtHttp.Response resp = AdtHttp.get(destination, uri, "application/xml");
            String body = resp.bodyAsString();

            if (resp.status >= 400) {
                return raw(resp.status, uri, body, "HTTP " + resp.status
                    + " (non-OO objects may not expose an object structure)");
            }
            if (resp.truncated) {
                return raw(resp.status, uri, body, "response exceeded 256 KB and was truncated");
            }

            StringBuilder sb = new StringBuilder(body.length() + 256);
            sb.append("{\"status\":").append(resp.status);
            sb.append(",\"uri\":").append(Json.str(uri));
            try {
                Document doc = Xml.parse(body);
                List<Element> els = Xml.elements(doc, "objectStructureElement");
                sb.append(",\"count\":").append(els.size());
                sb.append(",\"components\":[");
                int n = 0;
                for (Element e : els) {
                    if (n++ > 0) sb.append(",");
                    sb.append("{");
                    sb.append("\"name\":").append(Json.str(Xml.attr(e, "name"))).append(",");
                    sb.append("\"type\":").append(Json.str(Xml.attr(e, "type"))).append(",");
                    sb.append("\"uri\":").append(Json.str(attrAny(e, "uri", "objectUri"))).append(",");
                    sb.append("\"visibility\":").append(Json.str(Xml.attr(e, "visibility")));
                    sb.append("}");
                }
                sb.append("]");
                if (els.isEmpty()) {
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
            return error("arc1_sap_object_structure failed: " + t.getClass().getSimpleName()
                + ": " + String.valueOf(t.getMessage()));
        }
    }

    private static String attrAny(Element e, String a, String b) {
        String v = Xml.attr(e, a);
        return v != null ? v : Xml.attr(e, b);
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
