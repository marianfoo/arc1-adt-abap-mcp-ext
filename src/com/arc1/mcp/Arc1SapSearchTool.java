package com.arc1.mcp;

import java.util.List;

import org.eclipse.core.runtime.NullProgressMonitor;

import com.sap.adt.mcp.core.AdtMcpToolCallResultBuilder;
import com.sap.adt.mcp.core.IAdtMCPTool;
import com.sap.adt.mcp.core.IAdtMcpToolCallResult;
import com.sap.adt.ris.search.AdtRisQuickSearchFactory;
import com.sap.adt.ris.search.IAdtRisQuickSearch;
import com.sap.adt.tools.core.model.adtcore.IAdtObjectReference;

public class Arc1SapSearchTool implements IAdtMCPTool {

    @Override
    public String getName() {
        return "arc1_sap_search";
    }

    @Override
    public String getDescription() {
        return "ARC-1 SAPSearch (quick): find ABAP objects by name pattern. "
             + "Wildcards * and + are supported. Returns name, type, uri, package, description.";
    }

    @Override
    public String getInputSchema() {
        return "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            +   "\"destination\":{\"type\":\"string\","
            +     "\"description\":\"ADT destination ID (use abap_list_destinations to discover)\"},"
            +   "\"query\":{\"type\":\"string\","
            +     "\"description\":\"Search pattern, e.g. CL_ABAP* or ZARC1*\"},"
            +   "\"maxResults\":{\"type\":\"integer\","
            +     "\"description\":\"Maximum number of hits (default 50, capped at 200)\"}"
            + "},"
            + "\"required\":[\"destination\",\"query\"]"
            + "}";
    }

    @Override
    public IAdtMcpToolCallResult execute(String jsonInput) {
        try {
            String destination = Json.readString(jsonInput, "destination");
            String query = Json.readString(jsonInput, "query");
            Integer maxResults = Json.readInt(jsonInput, "maxResults");

            if (destination == null || destination.isEmpty()) {
                return error("Missing required field: destination");
            }
            if (query == null || query.isEmpty()) {
                return error("Missing required field: query");
            }
            int max = (maxResults == null || maxResults.intValue() <= 0)
                ? 50
                : Math.min(maxResults.intValue(), 200);

            IAdtRisQuickSearch search = AdtRisQuickSearchFactory.createQuickSearch(
                destination, new NullProgressMonitor());
            List<IAdtObjectReference> hits = search.execute(query, max);

            StringBuilder sb = new StringBuilder(256 + hits.size() * 96);
            sb.append("{\"destination\":").append(Json.str(destination));
            sb.append(",\"query\":").append(Json.str(query));
            sb.append(",\"maxResults\":").append(max);
            sb.append(",\"count\":").append(hits.size());
            sb.append(",\"hits\":[");
            for (int i = 0; i < hits.size(); i++) {
                IAdtObjectReference r = hits.get(i);
                if (i > 0) {
                    sb.append(",");
                }
                sb.append("{");
                sb.append("\"name\":").append(Json.str(r.getName())).append(",");
                sb.append("\"type\":").append(Json.str(r.getType())).append(",");
                sb.append("\"uri\":").append(Json.str(r.getUri())).append(",");
                sb.append("\"package\":").append(Json.str(r.getPackageName())).append(",");
                sb.append("\"description\":").append(Json.str(r.getDescription()));
                sb.append("}");
            }
            sb.append("]}");

            String json = sb.toString();
            return AdtMcpToolCallResultBuilder.builder()
                .withStructuredContent(json)
                .withContent(json)
                .isError(false)
                .build();
        } catch (Throwable t) {
            return error("arc1_sap_search failed: " + t.getClass().getSimpleName()
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
