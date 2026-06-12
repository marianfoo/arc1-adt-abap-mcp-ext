package com.arc1.mcp;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.sap.adt.mcp.core.AdtMcpToolCallResultBuilder;
import com.sap.adt.mcp.core.IAdtMCPTool;
import com.sap.adt.mcp.core.IAdtMcpToolCallResult;

/**
 * Run the ABAP Unit tests of an object.
 *
 *   POST /sap/bc/adt/abapunit/testruns
 *   Content-Type: application/vnd.sap.adt.abapunit.testruns.config.v4+xml
 *
 * EXECUTE-CLASS: this runs the object's unit tests on the backend (the same as
 * clicking "Run As → ABAP Unit Test" in ADT). It does not modify the repository,
 * but ABAP Unit executes test code — isolation is the test author's concern.
 * The call is synchronous (blocks until the run finishes); large suites can take
 * a while.
 */
public class Arc1SapRunUnitTestsTool implements IAdtMCPTool {

    private static final String URI = "/sap/bc/adt/abapunit/testruns";
    private static final String CT = "application/vnd.sap.adt.abapunit.testruns.config.v4+xml";

    @Override
    public String getName() {
        return "arc1_sap_run_unit_tests";
    }

    @Override
    public String getDescription() {
        return "Run the ABAP Unit tests of an object on the backend and return the results "
             + "(test classes, methods, and alerts with severity/title/detail). EXECUTES test "
             + "code server-side; does not modify the repository. Synchronous — large suites "
             + "can take minutes. Best-effort parse; rawXml when parsing is empty.";
    }

    @Override
    public String getInputSchema() {
        return "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            +   "\"destination\":{\"type\":\"string\",\"description\":\"ADT destination ID\"},"
            +   "\"objectUri\":{\"type\":\"string\","
            +     "\"description\":\"Object URI whose tests to run, e.g. /sap/bc/adt/oo/classes/ZCL_FOO\"}"
            + "},"
            + "\"required\":[\"destination\",\"objectUri\"]"
            + "}";
    }

    @Override
    public IAdtMcpToolCallResult execute(String jsonInput) {
        try {
            String destination = Json.readString(jsonInput, "destination");
            String objectUri = Json.readString(jsonInput, "objectUri");

            if (destination == null || destination.isEmpty()) {
                return error("Missing required field: destination");
            }
            if (objectUri == null || objectUri.isEmpty()) {
                return error("Missing required field: objectUri");
            }

            String reqBody =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<aunit:runConfiguration xmlns:aunit=\"http://www.sap.com/adt/aunit\""
                + " xmlns:adtcore=\"http://www.sap.com/adt/core\">"
                + "<external><coverage active=\"false\"/></external>"
                + "<adtcore:objectSets>"
                + "<objectSet kind=\"inclusive\">"
                + "<adtcore:objectReferences>"
                + "<adtcore:objectReference adtcore:uri=\"" + Xml.escape(objectUri) + "\"/>"
                + "</adtcore:objectReferences>"
                + "</objectSet>"
                + "</adtcore:objectSets>"
                + "</aunit:runConfiguration>";

            AdtHttp.Response resp = AdtHttp.post(destination, URI,
                "application/vnd.sap.adt.abapunit.testruns.result.v1+xml", CT,
                reqBody.getBytes(StandardCharsets.UTF_8));
            String body = resp.bodyAsString();

            if (resp.status >= 400) {
                return raw(resp.status, body, "HTTP " + resp.status);
            }
            if (resp.truncated) {
                return raw(resp.status, body, "response exceeded 256 KB and was truncated");
            }

            StringBuilder sb = new StringBuilder(body.length() + 256);
            sb.append("{\"status\":").append(resp.status);
            try {
                Document doc = Xml.parse(resp.body);
                List<Element> methods = Xml.elements(doc, "testMethod");
                List<Element> alerts = Xml.elements(doc, "alert");
                int failed = 0;
                for (Element a : alerts) {
                    String sev = Xml.attr(a, "severity");
                    String sevLc = sev == null ? "" : sev.toLowerCase(Locale.ROOT);
                    if (sevLc.contains("critical") || sevLc.contains("fatal")
                            || sevLc.contains("error")) {
                        failed++;
                    }
                }
                sb.append(",\"methodCount\":").append(methods.size());
                sb.append(",\"alertCount\":").append(alerts.size());
                sb.append(",\"failureAlertCount\":").append(failed);
                sb.append(",\"methods\":[");
                for (int i = 0; i < methods.size(); i++) {
                    Element m = methods.get(i);
                    if (i > 0) sb.append(",");
                    sb.append("{");
                    sb.append("\"name\":").append(Json.str(Xml.attr(m, "name"))).append(",");
                    sb.append("\"executionTime\":").append(Json.str(Xml.attr(m, "executionTime"))).append(",");
                    sb.append("\"alerts\":[");
                    List<Element> mAlerts = Xml.elements(m, "alert");
                    for (int j = 0; j < mAlerts.size(); j++) {
                        Element a = mAlerts.get(j);
                        if (j > 0) sb.append(",");
                        sb.append("{");
                        sb.append("\"kind\":").append(Json.str(Xml.attr(a, "kind"))).append(",");
                        sb.append("\"severity\":").append(Json.str(Xml.attr(a, "severity"))).append(",");
                        sb.append("\"title\":").append(Json.str(Xml.text(Xml.first(a, "title"))));
                        sb.append("}");
                    }
                    sb.append("]}");
                }
                sb.append("]");
                if (methods.isEmpty() && alerts.isEmpty()) {
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
            return error("arc1_sap_run_unit_tests failed: " + t.getClass().getSimpleName()
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
