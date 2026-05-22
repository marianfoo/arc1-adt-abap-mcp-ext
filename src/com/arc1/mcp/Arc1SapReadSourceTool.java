package com.arc1.mcp;

import com.sap.adt.mcp.core.AdtMcpToolCallResultBuilder;
import com.sap.adt.mcp.core.IAdtMCPTool;
import com.sap.adt.mcp.core.IAdtMcpToolCallResult;

/**
 * Fetch ABAP source code for an object URI.
 *
 * Either pass sourceUri (used as-is) or objectUri (we append /source/main, or
 * for CLAS, /includes/{include} when include is set).
 */
public class Arc1SapReadSourceTool implements IAdtMCPTool {

    @Override
    public String getName() {
        return "arc1_sap_read_source";
    }

    @Override
    public String getDescription() {
        return "Fetch the source code of an ABAP object as plain text. "
             + "Either pass sourceUri (e.g. /sap/bc/adt/programs/programs/Z_FOO/source/main) "
             + "or objectUri (e.g. /sap/bc/adt/oo/classes/CL_FOO) and we'll add /source/main. "
             + "For CLAS includes, set include=definitions|implementations|testclasses|macros. "
             + "Body capped at 256 KB.";
    }

    @Override
    public String getInputSchema() {
        return "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            +   "\"destination\":{\"type\":\"string\","
            +     "\"description\":\"ADT destination ID\"},"
            +   "\"sourceUri\":{\"type\":\"string\","
            +     "\"description\":\"Exact source URI (preferred when the object's source segment isn't /source/main).\"},"
            +   "\"objectUri\":{\"type\":\"string\","
            +     "\"description\":\"Object URI; tool appends /source/main (or /includes/{include} for CLAS).\"},"
            +   "\"include\":{\"type\":\"string\","
            +     "\"enum\":[\"main\",\"definitions\",\"implementations\",\"testclasses\",\"macros\"],"
            +     "\"description\":\"CLAS include segment when objectUri is used. Default 'main'.\"}"
            + "},"
            + "\"required\":[\"destination\"]"
            + "}";
    }

    @Override
    public IAdtMcpToolCallResult execute(String jsonInput) {
        try {
            String destination = Json.readString(jsonInput, "destination");
            String sourceUri = Json.readString(jsonInput, "sourceUri");
            String objectUri = Json.readString(jsonInput, "objectUri");
            String include = Json.readString(jsonInput, "include");

            if (destination == null || destination.isEmpty()) {
                return error("Missing required field: destination");
            }

            String uri = resolveUri(sourceUri, objectUri, include);
            if (uri == null) {
                return error("Provide either sourceUri or objectUri");
            }

            AdtHttp.Response resp = AdtHttp.get(destination, uri, "text/plain");

            StringBuilder sb = new StringBuilder(resp.body.length + 256);
            sb.append("{");
            sb.append("\"status\":").append(resp.status).append(",");
            sb.append("\"uri\":").append(Json.str(uri)).append(",");
            sb.append("\"contentType\":").append(Json.str(resp.contentType)).append(",");
            sb.append("\"truncated\":").append(resp.truncated).append(",");
            sb.append("\"byteCount\":").append(resp.body.length).append(",");
            sb.append("\"source\":").append(Json.str(resp.bodyAsString()));
            sb.append("}");

            String json = sb.toString();
            return AdtMcpToolCallResultBuilder.builder()
                .withStructuredContent(json)
                .withContent(json)
                .isError(resp.status >= 400)
                .build();
        } catch (Throwable t) {
            return error("arc1_sap_read_source failed: " + t.getClass().getSimpleName()
                + ": " + String.valueOf(t.getMessage()));
        }
    }

    private static String resolveUri(String sourceUri, String objectUri, String include) {
        if (sourceUri != null && !sourceUri.isEmpty()) {
            return sourceUri;
        }
        if (objectUri == null || objectUri.isEmpty()) {
            return null;
        }
        String base = objectUri.endsWith("/") ? objectUri.substring(0, objectUri.length() - 1) : objectUri;
        if (include == null || include.isEmpty() || "main".equalsIgnoreCase(include)) {
            return base + "/source/main";
        }
        return base + "/includes/" + include.toLowerCase();
    }

    private static IAdtMcpToolCallResult error(String msg) {
        return AdtMcpToolCallResultBuilder.builder()
            .withContent(msg)
            .isError(true)
            .build();
    }
}
