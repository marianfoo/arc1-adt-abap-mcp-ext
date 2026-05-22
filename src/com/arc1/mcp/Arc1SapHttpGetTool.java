package com.arc1.mcp;

import java.util.Map;

import com.sap.adt.mcp.core.AdtMcpToolCallResultBuilder;
import com.sap.adt.mcp.core.IAdtMCPTool;
import com.sap.adt.mcp.core.IAdtMcpToolCallResult;

/**
 * Generic GET escape hatch for any ADT REST endpoint. Authentication, cookies,
 * and session reuse Eclipse's existing destination machinery.
 *
 * Body is capped at 256 KB; large responses are flagged with truncated=true.
 */
public class Arc1SapHttpGetTool implements IAdtMCPTool {

    @Override
    public String getName() {
        return "arc1_sap_http_get";
    }

    @Override
    public String getDescription() {
        return "Generic HTTP GET to any ADT endpoint, e.g. /sap/bc/adt/discovery, "
             + "/sap/bc/adt/oo/classes/CL_FOO, /sap/bc/adt/repository/informationsystem/search?... "
             + "Reuses Eclipse's destination auth so no credentials are needed. "
             + "Use for endpoints that don't yet have a typed arc1_sap_* wrapper. "
             + "Response body is capped at 256 KB; oversized responses set truncated=true.";
    }

    @Override
    public String getInputSchema() {
        return "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            +   "\"destination\":{\"type\":\"string\","
            +     "\"description\":\"ADT destination ID\"},"
            +   "\"uri\":{\"type\":\"string\","
            +     "\"description\":\"Path + query starting with /sap/bc/adt/...\"},"
            +   "\"accept\":{\"type\":\"string\","
            +     "\"description\":\"Optional Accept header. Defaults to */* if omitted.\"}"
            + "},"
            + "\"required\":[\"destination\",\"uri\"]"
            + "}";
    }

    @Override
    public IAdtMcpToolCallResult execute(String jsonInput) {
        try {
            String destination = Json.readString(jsonInput, "destination");
            String uri = Json.readString(jsonInput, "uri");
            String accept = Json.readString(jsonInput, "accept");
            if (destination == null || destination.isEmpty()) {
                return error("Missing required field: destination");
            }
            if (uri == null || uri.isEmpty()) {
                return error("Missing required field: uri");
            }

            AdtHttp.Response resp = AdtHttp.get(destination, uri, accept);

            StringBuilder sb = new StringBuilder(resp.body.length + 512);
            sb.append("{");
            sb.append("\"status\":").append(resp.status).append(",");
            sb.append("\"contentType\":").append(Json.str(resp.contentType)).append(",");
            sb.append("\"truncated\":").append(resp.truncated).append(",");
            sb.append("\"byteCount\":").append(resp.body.length).append(",");
            sb.append("\"headers\":{");
            boolean first = true;
            for (Map.Entry<String, String> h : resp.headers.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(Json.str(h.getKey())).append(":").append(Json.str(h.getValue()));
            }
            sb.append("},");
            sb.append("\"body\":").append(Json.str(resp.bodyAsString()));
            sb.append("}");

            String json = sb.toString();
            return AdtMcpToolCallResultBuilder.builder()
                .withStructuredContent(json)
                .withContent(json)
                .isError(false)
                .build();
        } catch (Throwable t) {
            return error("arc1_sap_http_get failed: " + t.getClass().getSimpleName()
                + ": " + String.valueOf(t.getMessage()));
        }
    }

    private static IAdtMcpToolCallResult error(String msg) {
        return AdtMcpToolCallResultBuilder.builder()
            .withContent(msg)
            .isError(true)
            .build();
    }
}
