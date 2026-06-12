package com.arc1.mcp;

import java.util.List;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.sap.adt.mcp.core.AdtMcpToolCallResultBuilder;
import com.sap.adt.mcp.core.IAdtMCPTool;
import com.sap.adt.mcp.core.IAdtMcpToolCallResult;

/**
 * List the ABAP objects the user currently has in an inactive state.
 *
 *   GET /sap/bc/adt/activation/inactiveobjects
 *   Accept: application/vnd.sap.adt.inactivectsobjects.v1+xml
 *
 * Raw XML is returned by default. parse=true does a structured DOM extraction
 * (via {@link Xml}) of each inactive object and its assigned transport. This is
 * the read-only counterpart to SAP's own activation tooling — it tells an AI
 * client what is unactivated without touching the activation lifecycle.
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
        return "List ABAP objects currently in an inactive (not yet activated) state for "
             + "the logged-on user. Set parse=true for a structured list of objects and "
             + "their transports; default returns the raw XML for the LLM to read directly. "
             + "Read-only — does not activate anything.";
    }

    @Override
    public String getInputSchema() {
        return "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            +   "\"destination\":{\"type\":\"string\","
            +     "\"description\":\"ADT destination ID\"},"
            +   "\"parse\":{\"type\":\"boolean\","
            +     "\"description\":\"If true, return a structured list. Default false (raw XML).\"}"
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
            Boolean parse = Json.readBoolean(jsonInput, "parse");

            AdtHttp.Response resp = AdtHttp.get(destination, URI, ACCEPT);
            String body = resp.bodyAsString();

            StringBuilder sb = new StringBuilder(body.length() + 512);
            sb.append("{");
            sb.append("\"status\":").append(resp.status).append(",");
            sb.append("\"contentType\":").append(Json.str(resp.contentType)).append(",");
            sb.append("\"truncated\":").append(resp.truncated).append(",");
            sb.append("\"uri\":").append(Json.str(URI));

            if (parse != null && parse.booleanValue() && resp.status < 400) {
                List<InactiveObject> objs = parseInactive(body);
                sb.append(",\"count\":").append(objs.size());
                sb.append(",\"inactiveObjects\":[");
                for (int i = 0; i < objs.size(); i++) {
                    if (i > 0) sb.append(",");
                    InactiveObject o = objs.get(i);
                    sb.append("{");
                    sb.append("\"name\":").append(Json.str(o.name)).append(",");
                    sb.append("\"type\":").append(Json.str(o.type)).append(",");
                    sb.append("\"uri\":").append(Json.str(o.uri)).append(",");
                    sb.append("\"parentUri\":").append(Json.str(o.parentUri)).append(",");
                    sb.append("\"description\":").append(Json.str(o.description)).append(",");
                    sb.append("\"user\":").append(Json.str(o.user)).append(",");
                    sb.append("\"transport\":").append(Json.str(o.transport));
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
            return error("arc1_sap_list_inactive failed: " + t.getClass().getSimpleName()
                + ": " + String.valueOf(t.getMessage()));
        }
    }

    /**
     * Each {@code <entry>} carries an {@code <object>} (and optionally a
     * {@code <transport>}), each wrapping an adtcore {@code <ref>}. Local-name
     * matching keeps this prefix-agnostic.
     */
    private static List<InactiveObject> parseInactive(String xml) throws Exception {
        List<InactiveObject> out = new java.util.ArrayList<>();
        Document doc = Xml.parse(xml);
        for (Element entry : Xml.elementsByLocalName(doc, "entry")) {
            Element object = Xml.firstByLocalName(entry, "object");
            if (object == null) {
                continue;
            }
            Element ref = Xml.firstByLocalName(object, "ref");
            InactiveObject o = new InactiveObject();
            o.uri = Xml.attr(ref, "uri");
            o.type = Xml.attr(ref, "type");
            o.name = Xml.attr(ref, "name");
            o.parentUri = Xml.attr(ref, "parentUri");
            o.description = Xml.attr(ref, "description");
            o.user = Xml.text(Xml.firstByLocalName(object, "user"));
            Element transport = Xml.firstByLocalName(entry, "transport");
            if (transport != null) {
                Element tref = Xml.firstByLocalName(transport, "ref");
                o.transport = Xml.attr(tref, "name");
            }
            out.add(o);
        }
        return out;
    }

    private static final class InactiveObject {
        String name;
        String type;
        String uri;
        String parentUri;
        String description;
        String user;
        String transport;
    }

    private static IAdtMcpToolCallResult error(String msg) {
        return AdtMcpToolCallResultBuilder.builder()
            .withContent(msg)
            .isError(true)
            .build();
    }
}
