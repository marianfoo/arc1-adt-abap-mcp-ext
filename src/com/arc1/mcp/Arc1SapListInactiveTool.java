package com.arc1.mcp;

import java.util.List;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.sap.adt.mcp.core.AdtMcpToolCallResultBuilder;
import com.sap.adt.mcp.core.IAdtMCPTool;
import com.sap.adt.mcp.core.IAdtMcpToolCallResult;

/**
 * List inactive (un-activated) repository objects on the system.
 *
 *   GET /sap/bc/adt/activation/inactiveobjects
 *   Accept: application/vnd.sap.adt.inactivectsobjects.v1+xml
 *
 * Read-only. Useful before reading source: an inactive object's active version
 * may differ from what's half-finished in the workbench.
 */
public class Arc1SapListInactiveTool implements IAdtMCPTool {

    private static final String URI = "/sap/bc/adt/activation/inactiveobjects";
    private static final String ACCEPT = "application/vnd.sap.adt.inactivectsobjects.v1+xml";

    @Override
    public String getName() {
        return "arc1_sap_list_inactive";
    }

    @Override
    public String getDescription() {
        return "List inactive (not-yet-activated) ABAP objects on the system. "
             + "Returns name, type and URI per object. Read-only. Best-effort "
             + "structured parse; rawXml is included when parsing yields nothing.";
    }

    @Override
    public String getInputSchema() {
        return "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            +   "\"destination\":{\"type\":\"string\",\"description\":\"ADT destination ID\"},"
            +   "\"includeRaw\":{\"type\":\"boolean\",\"description\":\"Also return the raw XML. Default false.\"}"
            + "},"
            + "\"required\":[\"destination\"]"
            + "}";
    }

    @Override
    public IAdtMcpToolCallResult execute(String jsonInput) {
        try {
            String destination = Json.readString(jsonInput, "destination");
            if (destination == null || destination.isEmpty()) {
                return error("Missing required field: destination");
            }
            Boolean includeRaw = Json.readBoolean(jsonInput, "includeRaw");

            AdtHttp.Response resp = AdtHttp.get(destination, URI, ACCEPT);
            String body = resp.bodyAsString();

            if (resp.status >= 400) {
                return raw(resp.status, body, "HTTP " + resp.status);
            }
            if (resp.truncated) {
                return raw(resp.status, body,
                    "response exceeded 256 KB and was truncated; too many inactive objects to list safely");
            }

            StringBuilder sb = new StringBuilder(body.length() + 256);
            sb.append("{\"status\":").append(resp.status);
            try {
                Document doc = Xml.parse(resp.body);
                List<Element> refs = Xml.elements(doc, "ref");
                sb.append(",\"count\":").append(refs.size());
                sb.append(",\"objects\":[");
                int n = 0;
                for (Element ref : refs) {
                    String name = Xml.attr(ref, "name");
                    if (name == null || name.isEmpty()) {
                        continue; // skip transport-only refs
                    }
                    if (n++ > 0) sb.append(",");
                    sb.append("{");
                    sb.append("\"name\":").append(Json.str(name)).append(",");
                    sb.append("\"type\":").append(Json.str(Xml.attr(ref, "type"))).append(",");
                    sb.append("\"uri\":").append(Json.str(Xml.attr(ref, "uri"))).append(",");
                    sb.append("\"parentUri\":").append(Json.str(Xml.attr(ref, "parentUri"))).append(",");
                    String user = Xml.attr(ref, "user");
                    if (user == null && ref.getParentNode() instanceof Element) {
                        user = Xml.attr((Element) ref.getParentNode(), "user");
                    }
                    sb.append("\"user\":").append(Json.str(user));
                    sb.append("}");
                }
                sb.append("]");
                if (n == 0 || (includeRaw != null && includeRaw.booleanValue())) {
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
            return error("arc1_sap_list_inactive failed: " + t.getClass().getSimpleName()
                + ": " + String.valueOf(t.getMessage()));
        }
    }

    private static IAdtMcpToolCallResult raw(int status, String body, String note) {
        String json = "{\"status\":" + status
            + ",\"note\":" + Json.str(note)
            + ",\"rawXml\":" + Json.str(body) + "}";
        return AdtMcpToolCallResultBuilder.builder()
            .withStructuredContent(json).withContent(json).isError(status >= 400).build();
    }

    private static IAdtMcpToolCallResult error(String msg) {
        return AdtMcpToolCallResultBuilder.builder().withContent(msg).isError(true).build();
    }
}
