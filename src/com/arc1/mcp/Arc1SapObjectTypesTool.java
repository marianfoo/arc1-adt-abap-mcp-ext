package com.arc1.mcp;

import java.util.Collection;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.sap.adt.mcp.core.AdtMcpToolCallResultBuilder;
import com.sap.adt.mcp.core.IAdtMCPTool;
import com.sap.adt.mcp.core.IAdtMcpToolCallResult;
import com.sap.adt.tools.core.AbapCore;
import com.sap.adt.tools.core.project.AdtProjectServiceFactory;
import com.sap.adt.tools.core.project.IAbapProjectService;
import com.sap.adt.tools.core.wbtyperegistry.IWbObjectType;
import com.sap.adt.tools.core.wbtyperegistry.IWbTypeRegistry;

public class Arc1SapObjectTypesTool implements IAdtMCPTool {

    @Override
    public String getName() {
        return "arc1_sap_object_types";
    }

    @Override
    public String getDescription() {
        return "Enumerate ABAP workbench object types available in the system (CLAS, INTF, "
             + "PROG, DDLS, BDEF, SRVB, ...) with their URI templates and capabilities. "
             + "Use to discover which types exist on this release and what the LLM can ask "
             + "about. Optional 'typeFilter' substring narrows results (case-insensitive).";
    }

    @Override
    public String getInputSchema() {
        return "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            +   "\"destination\":{\"type\":\"string\","
            +     "\"description\":\"ADT destination ID\"},"
            +   "\"typeFilter\":{\"type\":\"string\","
            +     "\"description\":\"Optional case-insensitive substring filter on type code or label\"}"
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
            String filter = Json.readString(jsonInput, "typeFilter");
            String filterLc = filter == null ? null : filter.toLowerCase(java.util.Locale.ROOT);

            IAbapProjectService projSvc = AdtProjectServiceFactory.createProjectService();
            IProject project = projSvc.findProject(destination);
            if (project == null) {
                return error("No Eclipse ABAP project found for destination: " + destination);
            }

            IWbTypeRegistry registry = AbapCore.getInstance().getWbTypeRegistry(project);
            if (registry == null || !registry.isAvailable()) {
                return error("Workbench type registry not available for: " + destination);
            }

            Collection<IWbObjectType> all = registry.getAllWbObjectTypes(new NullProgressMonitor());
            if (all == null) {
                all = java.util.Collections.emptyList();
            }

            StringBuilder sb = new StringBuilder(1024);
            sb.append("{");
            sb.append("\"destination\":").append(Json.str(destination)).append(",");
            sb.append("\"typeFilter\":").append(Json.str(filter)).append(",");
            sb.append("\"types\":[");
            int written = 0;
            for (IWbObjectType t : all) {
                if (!matches(t, filterLc)) {
                    continue;
                }
                if (written > 0) sb.append(",");
                sb.append("{");
                sb.append("\"type\":").append(Json.str(t.getType())).append(",");
                sb.append("\"label\":").append(Json.str(t.getTypeLabel())).append(",");
                sb.append("\"labelPlural\":").append(Json.str(t.getTypeLabelPlural())).append(",");
                sb.append("\"category\":").append(Json.str(t.getCategory())).append(",");
                sb.append("\"categoryLabel\":").append(Json.str(t.getCategoryLabel())).append(",");
                sb.append("\"parentType\":").append(Json.str(t.getParentType())).append(",");
                sb.append("\"uriTemplate\":").append(Json.str(t.getUriTemplate())).append(",");
                sb.append("\"maxNameLength\":").append(t.getMaxNameLength()).append(",");
                appendStringArray(sb, "capabilities", t.getCapabilities());
                sb.append(",");
                appendStringArray(sb, "userAuthorizations", t.getUserAuthorizations());
                sb.append("}");
                written++;
            }
            sb.append("],\"count\":").append(written).append("}");

            String json = sb.toString();
            return AdtMcpToolCallResultBuilder.builder()
                .withStructuredContent(json)
                .withContent(json)
                .isError(false)
                .build();
        } catch (Throwable t) {
            return error("arc1_sap_object_types failed: " + t.getClass().getSimpleName()
                + ": " + String.valueOf(t.getMessage()));
        }
    }

    private static boolean matches(IWbObjectType t, String filterLc) {
        if (filterLc == null || filterLc.isEmpty()) {
            return true;
        }
        String type = t.getType();
        String label = t.getTypeLabel();
        return (type != null && type.toLowerCase(java.util.Locale.ROOT).contains(filterLc))
            || (label != null && label.toLowerCase(java.util.Locale.ROOT).contains(filterLc));
    }

    private static void appendStringArray(StringBuilder sb, String key, List<String> values) {
        sb.append(Json.str(key)).append(":[");
        if (values != null) {
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(Json.str(values.get(i)));
            }
        }
        sb.append("]");
    }

    private static IAdtMcpToolCallResult error(String msg) {
        return AdtMcpToolCallResultBuilder.builder()
            .withContent(msg)
            .isError(true)
            .build();
    }
}
