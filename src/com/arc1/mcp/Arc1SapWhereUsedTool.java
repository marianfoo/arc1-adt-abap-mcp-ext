package com.arc1.mcp;

import java.net.URI;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.sap.adt.mcp.core.AdtMcpToolCallResultBuilder;
import com.sap.adt.mcp.core.IAdtMCPTool;
import com.sap.adt.mcp.core.IAdtMcpToolCallResult;
import com.sap.adt.ris.model.usagereferences.IUsageReferenceRequest;
import com.sap.adt.ris.model.usagereferences.IUsageReferenceResult;
import com.sap.adt.ris.model.usagereferences.IUsageReferencedObject;
import com.sap.adt.ris.model.usagereferences.IUsageReferencedObjects;
import com.sap.adt.ris.model.usagereferences.IUsageReferencesFactory;
import com.sap.adt.ris.search.usagereferences.AdtRisUsageReferencesSearchServiceFactory;
import com.sap.adt.ris.search.usagereferences.IAdtRisUsageReferencesSearchService;
import com.sap.adt.tools.core.project.AdtProjectServiceFactory;
import com.sap.adt.tools.core.project.IAbapProjectService;

/**
 * Where-used / impact analysis: list the objects that reference a given ABAP
 * object. Wraps SAP's public {@code IAdtRisUsageReferencesSearchService} (the
 * same Repository Information System backing the "Where-Used List" in Eclipse),
 * so no REST body shapes are hard-coded.
 *
 *   AdtRisUsageReferencesSearchServiceFactory.createUsageReferencesSearchService(project, …)
 *     .search(objectUri, request, …) -> IUsageReferenceResult
 *
 * Read-only. The destination must map to an ABAP project that is logged on
 * (Arc1AutoLogin pre-warms this on startup).
 */
public class Arc1SapWhereUsedTool implements IAdtMCPTool {

    @Override
    public String getName() {
        return "arc1_sap_where_used";
    }

    @Override
    public String getDescription() {
        return "Where-used list for an ABAP object: which objects reference the one at "
             + "objectUri (impact analysis). Pass the ADT URI of a class, interface, "
             + "method, function module, data element, table, etc. Returns referencing "
             + "objects with their URI and a short usage note. Read-only.";
    }

    @Override
    public String getInputSchema() {
        return "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            +   "\"destination\":{\"type\":\"string\","
            +     "\"description\":\"ADT destination ID (use abap_list_destinations to discover)\"},"
            +   "\"objectUri\":{\"type\":\"string\","
            +     "\"description\":\"ADT URI to find usages of, e.g. /sap/bc/adt/oo/classes/ZCL_FOO or a sub-object like /sap/bc/adt/oo/classes/ZCL_FOO/source/main#start=12,0\"},"
            +   "\"maxResults\":{\"type\":\"integer\","
            +     "\"description\":\"Maximum references to return (default 100, capped at 500)\"}"
            + "},"
            + "\"required\":[\"destination\",\"objectUri\"]"
            + "}";
    }

    @Override
    public IAdtMcpToolCallResult execute(String jsonInput) {
        try {
            String destination = Json.readString(jsonInput, "destination");
            String objectUri = Json.readString(jsonInput, "objectUri");
            Integer maxResults = Json.readInt(jsonInput, "maxResults");

            if (destination == null || destination.isEmpty()) {
                return error("Missing required field: destination");
            }
            if (objectUri == null || objectUri.isEmpty()) {
                return error("Missing required field: objectUri");
            }
            int max = (maxResults == null || maxResults.intValue() <= 0)
                ? 100
                : Math.min(maxResults.intValue(), 500);

            IProject project = findProject(destination);
            if (project == null) {
                return error("No ABAP project is mapped to destination '" + destination
                    + "'. Open the ABAP project and log on in Eclipse first.");
            }

            IAdtRisUsageReferencesSearchService svc =
                AdtRisUsageReferencesSearchServiceFactory.createUsageReferencesSearchService(
                    project, new NullProgressMonitor());

            IUsageReferenceRequest request =
                IUsageReferencesFactory.eINSTANCE.createUsageReferenceRequest();
            request.setMaximumNumberOfResults(max);

            IUsageReferenceResult result =
                svc.search(URI.create(objectUri), request, new NullProgressMonitor());

            List<IUsageReferencedObject> refs = null;
            IUsageReferencedObjects container = result == null ? null : result.getReferencedObjects();
            if (container != null) {
                // EList extends java.util.List — keep EMF out of our imports.
                refs = container.getReferencedObject();
            }
            int total = refs == null ? 0 : refs.size();
            int shown = Math.min(total, max);

            StringBuilder sb = new StringBuilder(256 + shown * 96);
            sb.append("{\"destination\":").append(Json.str(destination));
            sb.append(",\"objectUri\":").append(Json.str(objectUri));
            sb.append(",\"maxResults\":").append(max);
            sb.append(",\"numberOfResults\":").append(result == null ? 0 : result.getNumberOfResults());
            sb.append(",\"resultDescription\":")
              .append(Json.str(result == null ? null : result.getResultDescription()));
            sb.append(",\"count\":").append(shown);
            sb.append(",\"truncated\":").append(total > shown);
            sb.append(",\"references\":[");
            for (int i = 0; i < shown; i++) {
                IUsageReferencedObject ro = refs.get(i);
                if (i > 0) {
                    sb.append(",");
                }
                sb.append("{");
                sb.append("\"uri\":").append(Json.str(ro.getUri())).append(",");
                sb.append("\"parentUri\":").append(Json.str(ro.getParentUri())).append(",");
                sb.append("\"objectIdentifier\":").append(Json.str(ro.getObjectIdentifier())).append(",");
                sb.append("\"usageInformation\":").append(Json.str(ro.getUsageInformation())).append(",");
                sb.append("\"isResult\":").append(ro.isIsResult()).append(",");
                sb.append("\"canHaveChildren\":").append(ro.isCanHaveChildren());
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
            return error("arc1_sap_where_used failed: " + t.getClass().getSimpleName()
                + ": " + String.valueOf(t.getMessage()));
        }
    }

    /** Resolve the ABAP project whose destination ID matches {@code destination}. */
    private static IProject findProject(String destination) {
        IAbapProjectService projSvc = AdtProjectServiceFactory.createProjectService();
        IProject[] projects = projSvc.getAvailableAbapProjects();
        if (projects == null) {
            return null;
        }
        for (IProject p : projects) {
            try {
                if (destination.equals(projSvc.getDestinationId(p))) {
                    return p;
                }
            } catch (Throwable ignore) {
                // best effort — skip projects that can't report a destination
            }
        }
        return null;
    }

    private static IAdtMcpToolCallResult error(String msg) {
        return AdtMcpToolCallResultBuilder.builder()
            .withContent(msg)
            .isError(true)
            .build();
    }
}
