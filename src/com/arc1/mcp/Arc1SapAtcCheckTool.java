package com.arc1.mcp;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.sap.adt.mcp.core.AdtMcpToolCallResultBuilder;
import com.sap.adt.mcp.core.IAdtMCPTool;
import com.sap.adt.mcp.core.IAdtMcpToolCallResult;

/**
 * Run ABAP Test Cockpit (ATC) static analysis on an object and return findings.
 *
 * Three steps in one call:
 *   1. POST /sap/bc/adt/atc/worklists?checkVariant=        (empty = system default)
 *   2. POST /sap/bc/adt/atc/runs?worklistId=<id>           (object set = the target)
 *   3. GET  /sap/bc/adt/atc/worklists/<id>                 (read findings)
 *
 * EXECUTE-CLASS: ATC runs checks server-side; it does not modify the repository.
 * Best-effort parse; raw bodies are surfaced when a step fails or parsing is empty.
 */
public class Arc1SapAtcCheckTool implements IAdtMCPTool {

    @Override
    public String getName() {
        return "arc1_sap_atc_check";
    }

    @Override
    public String getDescription() {
        return "Run ABAP Test Cockpit (ATC) static analysis on an object and return findings "
             + "(priority, check title, message, uri). Uses the system default check variant "
             + "unless checkVariant is given. EXECUTES checks server-side; changes nothing. "
             + "Best-effort parse; raw response surfaced on step failure.";
    }

    @Override
    public String getInputSchema() {
        return "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            +   "\"destination\":{\"type\":\"string\",\"description\":\"ADT destination ID\"},"
            +   "\"objectUri\":{\"type\":\"string\","
            +     "\"description\":\"Object URI to check, e.g. /sap/bc/adt/oo/classes/ZCL_FOO\"},"
            +   "\"checkVariant\":{\"type\":\"string\","
            +     "\"description\":\"Optional ATC check variant. Omit for the system default.\"},"
            +   "\"maxResults\":{\"type\":\"integer\",\"description\":\"Max findings (default 100, cap 500).\"}"
            + "},"
            + "\"required\":[\"destination\",\"objectUri\"]"
            + "}";
    }

    @Override
    public IAdtMcpToolCallResult execute(String jsonInput) {
        try {
            String destination = Json.readString(jsonInput, "destination");
            String objectUri = Json.readString(jsonInput, "objectUri");
            String checkVariant = Json.readString(jsonInput, "checkVariant");
            Integer maxResults = Json.readInt(jsonInput, "maxResults");

            if (destination == null || destination.isEmpty()) {
                return error("Missing required field: destination");
            }
            if (objectUri == null || objectUri.isEmpty()) {
                return error("Missing required field: objectUri");
            }
            int max = (maxResults == null || maxResults.intValue() <= 0)
                ? 100 : Math.min(maxResults.intValue(), 500);
            String variant = checkVariant == null ? "" : checkVariant;

            // Step 1: create worklist (no request body — the id comes back as plain text).
            AdtHttp.Response w = AdtHttp.post(destination,
                "/sap/bc/adt/atc/worklists?checkVariant=" + enc(variant),
                "text/plain", null, null);
            if (w.status >= 400) {
                return step("create worklist", w.status, w.bodyAsString());
            }
            String worklistId = worklistId(w);
            if (worklistId == null || worklistId.isEmpty()) {
                return step("create worklist (no id in body/Location)", w.status, w.bodyAsString());
            }

            // Step 2: run checks against the object.
            String runBody =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<atc:run xmlns:atc=\"http://www.sap.com/adt/atc\" maximumVerdicts=\"" + max + "\">"
                + "<objectSets xmlns:adtcore=\"http://www.sap.com/adt/core\">"
                + "<objectSet kind=\"inclusive\">"
                + "<adtcore:objectReferences>"
                + "<adtcore:objectReference adtcore:uri=\"" + Xml.escape(objectUri) + "\"/>"
                + "</adtcore:objectReferences>"
                + "</objectSet></objectSets></atc:run>";
            AdtHttp.Response r = AdtHttp.post(destination,
                "/sap/bc/adt/atc/runs?worklistId=" + enc(worklistId),
                "application/xml", "application/xml",
                runBody.getBytes(StandardCharsets.UTF_8));
            if (r.status >= 400) {
                return step("run checks", r.status, r.bodyAsString());
            }

            // Step 3: fetch findings.
            AdtHttp.Response f = AdtHttp.get(destination,
                "/sap/bc/adt/atc/worklists/" + enc(worklistId) + "?includeExemptedFindings=false",
                "application/vnd.sap.atc.worklist.v1+xml");
            String body = f.bodyAsString();
            if (f.status >= 400) {
                return step("read worklist", f.status, body);
            }
            if (f.truncated) {
                return step("read worklist (truncated; lower maxResults)", f.status, body);
            }

            StringBuilder sb = new StringBuilder(body.length() + 256);
            sb.append("{\"status\":").append(f.status);
            sb.append(",\"worklistId\":").append(Json.str(worklistId));
            try {
                Document doc = Xml.parse(f.body);
                List<Element> findings = Xml.elements(doc, "finding");
                int shown = Math.min(findings.size(), max);
                sb.append(",\"count\":").append(findings.size());
                sb.append(",\"truncatedList\":").append(findings.size() > shown);
                sb.append(",\"findings\":[");
                for (int i = 0; i < shown; i++) {
                    Element e = findings.get(i);
                    if (i > 0) sb.append(",");
                    sb.append("{");
                    sb.append("\"priority\":").append(Json.str(Xml.attr(e, "priority"))).append(",");
                    sb.append("\"checkTitle\":").append(Json.str(Xml.attr(e, "checkTitle"))).append(",");
                    sb.append("\"messageTitle\":").append(Json.str(Xml.attr(e, "messageTitle"))).append(",");
                    sb.append("\"uri\":").append(Json.str(Xml.attr(e, "uri")));
                    sb.append("}");
                }
                sb.append("]");
                if (findings.isEmpty()) {
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
            return error("arc1_sap_atc_check failed: " + t.getClass().getSimpleName()
                + ": " + String.valueOf(t.getMessage()));
        }
    }

    /** Worklist id is the trimmed plain-text body, or the last segment of a Location header. */
    private static String worklistId(AdtHttp.Response resp) {
        String body = resp.bodyAsString();
        if (body != null) {
            String t = body.trim();
            if (!t.isEmpty() && t.indexOf('<') < 0 && t.length() < 200) {
                return t;
            }
        }
        if (resp.headers != null) {
            for (Map.Entry<String, String> h : resp.headers.entrySet()) {
                if ("location".equalsIgnoreCase(h.getKey()) && h.getValue() != null) {
                    String loc = h.getValue();
                    int slash = loc.lastIndexOf('/');
                    return slash >= 0 ? loc.substring(slash + 1) : loc;
                }
            }
        }
        return null;
    }

    private static IAdtMcpToolCallResult step(String stepName, int status, String body) {
        String json = "{\"status\":" + status
            + ",\"failedStep\":" + Json.str(stepName)
            + ",\"rawBody\":" + Json.str(body) + "}";
        return AdtMcpToolCallResultBuilder.builder()
            .withStructuredContent(json).withContent(json).isError(status >= 400).build();
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return s;
        }
    }

    private static IAdtMcpToolCallResult error(String msg) {
        return AdtMcpToolCallResultBuilder.builder().withContent(msg).isError(true).build();
    }
}
