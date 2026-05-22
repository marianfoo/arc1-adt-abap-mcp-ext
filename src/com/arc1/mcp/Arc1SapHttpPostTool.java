package com.arc1.mcp;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.sap.adt.mcp.core.AdtMcpToolCallResultBuilder;
import com.sap.adt.mcp.core.IAdtMCPTool;
import com.sap.adt.mcp.core.IAdtMcpToolCallResult;

/**
 * Generic POST escape hatch for any ADT REST endpoint. Mirrors arc1_sap_http_get
 * but takes a body + contentType. Auth/cookies/session reused from Eclipse.
 *
 * Body is sent as UTF-8 bytes. Binary bodies (e.g. raw blobs) are out of scope —
 * ADT POST endpoints are XML or JSON in practice.
 */
public class Arc1SapHttpPostTool implements IAdtMCPTool {

    @Override
    public String getName() {
        return "arc1_sap_http_post";
    }

    @Override
    public String getDescription() {
        return "Generic HTTP POST to any ADT endpoint. Body is sent as UTF-8 text "
             + "with the given Content-Type. Use for any POST endpoint that doesn't "
             + "yet have a typed arc1_sap_* wrapper. CSRF tokens are handled by "
             + "Eclipse's session machinery. Response body capped at 256 KB.";
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
            +     "\"description\":\"Accept header for the response\"},"
            +   "\"contentType\":{\"type\":\"string\","
            +     "\"description\":\"Content-Type of the request body, e.g. application/xml\"},"
            +   "\"body\":{\"type\":\"string\","
            +     "\"description\":\"Request body as text (UTF-8)\"}"
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
            String contentType = Json.readString(jsonInput, "contentType");
            String body = Json.readString(jsonInput, "body");
            if (destination == null || destination.isEmpty()) {
                return error("Missing required field: destination");
            }
            if (uri == null || uri.isEmpty()) {
                return error("Missing required field: uri");
            }

            byte[] bodyBytes = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
            AdtHttp.Response resp = AdtHttp.post(destination, uri, accept, contentType, bodyBytes);

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
                .isError(resp.status >= 400)
                .build();
        } catch (Throwable t) {
            return error("arc1_sap_http_post failed: " + t.getClass().getSimpleName()
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
