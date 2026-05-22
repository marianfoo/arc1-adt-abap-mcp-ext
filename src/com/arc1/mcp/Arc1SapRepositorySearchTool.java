package com.arc1.mcp;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.NullProgressMonitor;

import com.sap.adt.mcp.core.AdtMcpToolCallResultBuilder;
import com.sap.adt.mcp.core.IAdtMCPTool;
import com.sap.adt.mcp.core.IAdtMcpToolCallResult;
import com.sap.adt.ris.search.AdtRepositorySearchFactory;
import com.sap.adt.ris.search.IAdtRepositorySearchParameters;
import com.sap.adt.ris.search.IAdtRepositorySearchService;
import com.sap.adt.tools.core.model.adtcore.IAdtObjectReference;

public class Arc1SapRepositorySearchTool implements IAdtMCPTool {

    @Override
    public String getName() {
        return "arc1_sap_repository_search";
    }

    @Override
    public String getDescription() {
        return "Parameterized ABAP repository search with type/package/user/release filters. "
             + "More powerful than arc1_sap_search. Use when you need to scope hits to specific "
             + "object types (e.g. only CLAS+INTF) or specific packages.";
    }

    @Override
    public String getInputSchema() {
        return "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            +   "\"destination\":{\"type\":\"string\","
            +     "\"description\":\"ADT destination ID (use abap_list_destinations to discover)\"},"
            +   "\"query\":{\"type\":\"string\","
            +     "\"description\":\"Name pattern, e.g. ZARC1 or CL_ABAP. Use useTrailingWildcard for prefix match.\"},"
            +   "\"objectTypes\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
            +     "\"description\":\"e.g. ['CLAS','INTF','PROG','DDLS','BDEF','SRVB']\"},"
            +   "\"packages\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
            +     "\"description\":\"Package names, supports wildcards e.g. ['Z*','SAP*']\"},"
            +   "\"users\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
            +     "\"description\":\"Owner usernames\"},"
            +   "\"releaseStates\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
            +     "\"description\":\"e.g. ['RELEASED','NOT_RELEASED']\"},"
            +   "\"contextPackage\":{\"type\":\"string\","
            +     "\"description\":\"Optional context package for scoped search\"},"
            +   "\"maxResults\":{\"type\":\"integer\","
            +     "\"description\":\"Default 50, capped at 500\"},"
            +   "\"useTrailingWildcard\":{\"type\":\"boolean\","
            +     "\"description\":\"Default true. Set false for exact name match.\"}"
            + "},"
            + "\"required\":[\"destination\",\"query\"]"
            + "}";
    }

    @Override
    public IAdtMcpToolCallResult execute(String jsonInput) {
        try {
            String destination = Json.readString(jsonInput, "destination");
            String query = Json.readString(jsonInput, "query");
            if (destination == null || destination.isEmpty()) {
                return error("Missing required field: destination");
            }
            if (query == null || query.isEmpty()) {
                return error("Missing required field: query");
            }

            IAdtRepositorySearchService svc = AdtRepositorySearchFactory.createAdtRepositorySearchService();
            IAdtRepositorySearchParameters params = AdtRepositorySearchFactory.createAdtRepositorySearchParameters();

            List<String> objectTypes = Json.readStringArray(jsonInput, "objectTypes");
            if (!objectTypes.isEmpty()) {
                params.setObjectTypes(objectTypes);
            }
            List<String> packages = Json.readStringArray(jsonInput, "packages");
            if (!packages.isEmpty()) {
                params.setPackages(packages);
            }
            List<String> users = Json.readStringArray(jsonInput, "users");
            if (!users.isEmpty()) {
                params.setUsers(users);
            }
            List<String> releaseStates = Json.readStringArray(jsonInput, "releaseStates");
            if (!releaseStates.isEmpty()) {
                params.setReleaseStates(releaseStates);
            }
            String contextPackage = Json.readString(jsonInput, "contextPackage");
            if (contextPackage != null && !contextPackage.isEmpty()) {
                params.setContextPackage(contextPackage);
            }
            Integer maxResults = Json.readInt(jsonInput, "maxResults");
            int max = (maxResults == null || maxResults.intValue() <= 0)
                ? 50 : Math.min(maxResults.intValue(), 500);
            params.setMaxResults(max);

            Boolean wildcard = Json.readBoolean(jsonInput, "useTrailingWildcard");
            params.setUseTrailingWildcard(wildcard == null ? true : wildcard.booleanValue());

            List<IAdtObjectReference> hits = svc.search(destination, new NullProgressMonitor(), query, params);
            if (hits == null) {
                hits = new ArrayList<>();
            }

            StringBuilder sb = new StringBuilder(256 + hits.size() * 96);
            sb.append("{");
            sb.append("\"destination\":").append(Json.str(destination)).append(",");
            sb.append("\"query\":").append(Json.str(query)).append(",");
            sb.append("\"maxResults\":").append(max).append(",");
            sb.append("\"count\":").append(hits.size()).append(",");
            sb.append("\"hits\":[");
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
            return error("arc1_sap_repository_search failed: " + t.getClass().getSimpleName()
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
