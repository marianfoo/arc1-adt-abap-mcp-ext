package com.arc1.mcp;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.sap.adt.mcp.core.AdtMcpToolCallResultBuilder;
import com.sap.adt.mcp.core.IAdtMCPTool;
import com.sap.adt.mcp.core.IAdtMcpToolCallResult;

/**
 * ABAP syntax check without activation.
 *
 *   POST /sap/bc/adt/checkruns?reporters=abapCheckRun
 *   Content-Type: application/vnd.sap.adt.checkobjects+xml
 *   Accept:       application/vnd.sap.adt.checkmessages+xml
 *
 * Two modes:
 *  - no source: checks the saved (active/inactive) object.
 *  - source provided: a transient server-side check of PROPOSED code — nothing
 *    is written. Exactly what an AI editing loop needs to validate a suggestion.
 *
 * The check runs server-side and changes nothing in the repository.
 */
public class Arc1SapCheckSyntaxTool implements IAdtMCPTool {

    private static final String URI = "/sap/bc/adt/checkruns?reporters=abapCheckRun";
    private static final String CT = "application/vnd.sap.adt.checkobjects+xml";
    private static final String ACCEPT = "application/vnd.sap.adt.checkmessages+xml";

    @Override
    public String getName() {
        return "arc1_sap_check_syntax";
    }

    @Override
    public String getDescription() {
        return "Run an ABAP syntax check (no activation, changes nothing). Pass objectUri "
             + "to check the saved object, or also pass source to syntax-check PROPOSED code "
             + "server-side (transient — nothing is written). Returns messages with line, "
             + "column, severity and text. Best-effort parse; rawXml when parsing is empty.";
    }

    @Override
    public String getInputSchema() {
        return "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            +   "\"destination\":{\"type\":\"string\",\"description\":\"ADT destination ID\"},"
            +   "\"objectUri\":{\"type\":\"string\","
            +     "\"description\":\"Object URI, e.g. /sap/bc/adt/oo/classes/ZCL_FOO\"},"
            +   "\"source\":{\"type\":\"string\","
            +     "\"description\":\"Optional proposed source to check transiently instead of the saved version.\"},"
            +   "\"sourceUri\":{\"type\":\"string\","
            +     "\"description\":\"Artifact URI for the source (default objectUri + /source/main).\"},"
            +   "\"version\":{\"type\":\"string\",\"enum\":[\"active\",\"inactive\"],"
            +     "\"description\":\"Version to check when no source is given. Default active.\"}"
            + "},"
            + "\"required\":[\"destination\",\"objectUri\"]"
            + "}";
    }

    @Override
    public IAdtMcpToolCallResult execute(String jsonInput) {
        try {
            String destination = Json.readString(jsonInput, "destination");
            String objectUri = Json.readString(jsonInput, "objectUri");
            String source = Json.readString(jsonInput, "source");
            String sourceUri = Json.readString(jsonInput, "sourceUri");
            String version = Json.readString(jsonInput, "version");

            if (destination == null || destination.isEmpty()) {
                return error("Missing required field: destination");
            }
            if (objectUri == null || objectUri.isEmpty()) {
                return error("Missing required field: objectUri");
            }
            String ver = "inactive".equalsIgnoreCase(version) ? "inactive" : "active";
            if (sourceUri == null || sourceUri.isEmpty()) {
                String base = objectUri.endsWith("/")
                    ? objectUri.substring(0, objectUri.length() - 1) : objectUri;
                sourceUri = base + "/source/main";
            }

            StringBuilder b = new StringBuilder(512);
            b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            b.append("<chkrun:checkObjectList xmlns:adtcore=\"http://www.sap.com/adt/core\"")
             .append(" xmlns:chkrun=\"http://www.sap.com/adt/checkrun\">");
            b.append("<chkrun:checkObject adtcore:uri=\"").append(xml(objectUri))
             .append("\" chkrun:version=\"").append(ver).append("\">");
            if (source != null && !source.isEmpty()) {
                String b64 = Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.UTF_8));
                b.append("<chkrun:artifacts>");
                b.append("<chkrun:artifact chkrun:contentType=\"text/plain; charset=utf-8\"")
                 .append(" chkrun:uri=\"").append(xml(sourceUri)).append("\">");
                b.append("<chkrun:content>").append(b64).append("</chkrun:content>");
                b.append("</chkrun:artifact>");
                b.append("</chkrun:artifacts>");
            }
            b.append("</chkrun:checkObject>");
            b.append("</chkrun:checkObjectList>");

            AdtHttp.Response resp = AdtHttp.post(destination, URI, ACCEPT, CT,
                b.toString().getBytes(StandardCharsets.UTF_8));
            String body = resp.bodyAsString();

            if (resp.status >= 400) {
                return raw(resp.status, body, "HTTP " + resp.status);
            }
            if (resp.truncated) {
                return raw(resp.status, body, "response exceeded 256 KB and was truncated");
            }

            StringBuilder sb = new StringBuilder(body.length() + 256);
            sb.append("{\"status\":").append(resp.status);
            sb.append(",\"checkedProposedSource\":").append(source != null && !source.isEmpty());
            try {
                Document doc = Xml.parse(body);
                List<Element> msgs = Xml.elements(doc, "checkMessage");
                int errors = 0;
                for (Element m : msgs) {
                    String t = Xml.attr(m, "type");
                    if (t != null && t.toUpperCase().startsWith("E")) errors++;
                }
                sb.append(",\"count\":").append(msgs.size());
                sb.append(",\"errorCount\":").append(errors);
                sb.append(",\"messages\":[");
                for (int i = 0; i < msgs.size(); i++) {
                    Element m = msgs.get(i);
                    String mu = Xml.attr(m, "uri");
                    int[] lc = lineCol(mu);
                    if (i > 0) sb.append(",");
                    sb.append("{");
                    sb.append("\"type\":").append(Json.str(Xml.attr(m, "type"))).append(",");
                    sb.append("\"line\":").append(lc[0]).append(",");
                    sb.append("\"column\":").append(lc[1]).append(",");
                    sb.append("\"text\":").append(Json.str(attrAny(m, "shortText", "text"))).append(",");
                    sb.append("\"uri\":").append(Json.str(mu));
                    sb.append("}");
                }
                sb.append("]");
                if (msgs.isEmpty()) {
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
            return error("arc1_sap_check_syntax failed: " + t.getClass().getSimpleName()
                + ": " + String.valueOf(t.getMessage()));
        }
    }

    /** Extract [line, column] from a ...#start=LINE,COL fragment; -1,-1 if absent. */
    private static int[] lineCol(String uri) {
        int[] out = {-1, -1};
        if (uri == null) {
            return out;
        }
        int i = uri.indexOf("start=");
        if (i < 0) {
            return out;
        }
        String frag = uri.substring(i + 6);
        int amp = frag.indexOf('&');
        if (amp >= 0) frag = frag.substring(0, amp);
        String[] parts = frag.split(",");
        try {
            if (parts.length >= 1) out[0] = Integer.parseInt(parts[0].trim());
            if (parts.length >= 2) out[1] = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException ignore) {
            // leave -1
        }
        return out;
    }

    private static String attrAny(Element e, String a, String b) {
        String v = Xml.attr(e, a);
        return v != null ? v : Xml.attr(e, b);
    }

    private static String xml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
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
