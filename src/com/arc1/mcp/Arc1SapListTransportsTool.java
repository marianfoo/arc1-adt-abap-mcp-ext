package com.arc1.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sap.adt.mcp.core.AdtMcpToolCallResultBuilder;
import com.sap.adt.mcp.core.IAdtMCPTool;
import com.sap.adt.mcp.core.IAdtMcpToolCallResult;

/**
 * List ABAP transport requests via the transport organizer endpoint.
 *
 *   GET /sap/bc/adt/cts/transportrequests?username=&requestStatus=&requestType=
 *   Accept: application/vnd.sap.adt.transportorganizer.v1+xml
 *
 * Raw XML returned by default. parse=true does a minimal regex extraction
 * of request blocks for clients that prefer structured data.
 */
public class Arc1SapListTransportsTool implements IAdtMCPTool {

    @Override
    public String getName() {
        return "arc1_sap_list_transports";
    }

    @Override
    public String getDescription() {
        return "List ABAP transport requests via the transport organizer. "
             + "Filters: username (default = current user), status "
             + "(Modifiable | Released), requestType (Task | etc.). "
             + "Distinct from SAP's abap_transport-get which only returns transports "
             + "for one specific ABAP object. Set parse=true for a minimal structured "
             + "extraction; default returns the raw XML for the LLM to read directly.";
    }

    @Override
    public String getInputSchema() {
        return "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            +   "\"destination\":{\"type\":\"string\","
            +     "\"description\":\"ADT destination ID\"},"
            +   "\"username\":{\"type\":\"string\","
            +     "\"description\":\"Filter by user (e.g. DEVELOPER). Omit for current user.\"},"
            +   "\"status\":{\"type\":\"string\",\"enum\":[\"Modifiable\",\"Released\"],"
            +     "\"description\":\"Optional status filter.\"},"
            +   "\"requestType\":{\"type\":\"string\","
            +     "\"description\":\"Optional type filter, e.g. 'Task'.\"},"
            +   "\"parse\":{\"type\":\"boolean\","
            +     "\"description\":\"If true, return a structured list of request blocks. Default false.\"}"
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
            String username = Json.readString(jsonInput, "username");
            String status = Json.readString(jsonInput, "status");
            String requestType = Json.readString(jsonInput, "requestType");
            Boolean parse = Json.readBoolean(jsonInput, "parse");

            StringBuilder uri = new StringBuilder("/sap/bc/adt/cts/transportrequests");
            char sep = '?';
            if (username != null && !username.isEmpty()) {
                uri.append(sep).append("user=").append(urlEncode(username));
                sep = '&';
            }
            if (status != null && !status.isEmpty()) {
                uri.append(sep).append("requestStatus=").append(urlEncode(status));
                sep = '&';
            }
            if (requestType != null && !requestType.isEmpty()) {
                uri.append(sep).append("requestType=").append(urlEncode(requestType));
                sep = '&';
            }

            AdtHttp.Response resp = AdtHttp.get(destination, uri.toString(),
                "application/vnd.sap.adt.transportorganizer.v1+xml");

            String body = resp.bodyAsString();

            StringBuilder sb = new StringBuilder(body.length() + 512);
            sb.append("{");
            sb.append("\"status\":").append(resp.status).append(",");
            sb.append("\"contentType\":").append(Json.str(resp.contentType)).append(",");
            sb.append("\"truncated\":").append(resp.truncated).append(",");
            sb.append("\"uri\":").append(Json.str(uri.toString()));

            if (parse != null && parse.booleanValue()) {
                List<TransportRequest> reqs = parseTransports(body);
                sb.append(",\"count\":").append(reqs.size());
                sb.append(",\"requests\":[");
                for (int i = 0; i < reqs.size(); i++) {
                    if (i > 0) sb.append(",");
                    TransportRequest r = reqs.get(i);
                    sb.append("{");
                    sb.append("\"number\":").append(Json.str(r.number)).append(",");
                    sb.append("\"description\":").append(Json.str(r.description)).append(",");
                    sb.append("\"owner\":").append(Json.str(r.owner)).append(",");
                    sb.append("\"status\":").append(Json.str(r.status)).append(",");
                    sb.append("\"type\":").append(Json.str(r.type));
                    sb.append("}");
                }
                sb.append("]");
            } else {
                sb.append(",\"rawXml\":").append(Json.str(body));
            }
            sb.append("}");

            String json = sb.toString();
            return AdtMcpToolCallResultBuilder.builder()
                .withStructuredContent(json)
                .withContent(json)
                .isError(resp.status >= 400)
                .build();
        } catch (Throwable t) {
            return error("arc1_sap_list_transports failed: " + t.getClass().getSimpleName()
                + ": " + String.valueOf(t.getMessage()));
        }
    }

    private static final Pattern REQ_BLOCK = Pattern.compile(
        "<tm:request\\b([^>]*)>(.*?)</tm:request>", Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile("(\\w+:?\\w+)=\"([^\"]*)\"");

    private static List<TransportRequest> parseTransports(String xml) {
        List<TransportRequest> out = new ArrayList<>();
        Matcher m = REQ_BLOCK.matcher(xml);
        while (m.find()) {
            String attrs = m.group(1);
            TransportRequest r = new TransportRequest();
            Matcher am = ATTR.matcher(attrs);
            while (am.find()) {
                String name = am.group(1);
                String value = am.group(2);
                if (name.endsWith("number")) r.number = value;
                else if (name.endsWith("description")) r.description = value;
                else if (name.endsWith("owner")) r.owner = value;
                else if (name.endsWith("status")) r.status = value;
                else if (name.endsWith("type")) r.type = value;
            }
            out.add(r);
        }
        return out;
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return s;
        }
    }

    private static final class TransportRequest {
        String number;
        String description;
        String owner;
        String status;
        String type;
    }

    private static IAdtMcpToolCallResult error(String msg) {
        return AdtMcpToolCallResultBuilder.builder()
            .withContent(msg)
            .isError(true)
            .build();
    }
}
