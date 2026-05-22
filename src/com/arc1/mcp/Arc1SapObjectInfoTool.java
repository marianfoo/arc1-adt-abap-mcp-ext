package com.arc1.mcp;

import java.net.URI;

import org.eclipse.core.runtime.NullProgressMonitor;

import com.sap.adt.mcp.core.AdtMcpToolCallResultBuilder;
import com.sap.adt.mcp.core.IAdtMCPTool;
import com.sap.adt.mcp.core.IAdtMcpToolCallResult;
import com.sap.adt.ris.search.objectproperties.AdtRisVfsObjectPropertiesServiceFactory;
import com.sap.adt.ris.search.objectproperties.IAdtRisVfsObjectProperties;
import com.sap.adt.ris.search.objectproperties.IAdtRisVfsObjectPropertiesService;

public class Arc1SapObjectInfoTool implements IAdtMCPTool {

    @Override
    public String getName() {
        return "arc1_sap_object_info";
    }

    @Override
    public String getDescription() {
        return "Fetch metadata for one ABAP object identified by its ADT URI. "
             + "Returns name, type, package, description, and version. "
             + "Pair with arc1_sap_search to hydrate hits, or use after find_definition.";
    }

    @Override
    public String getInputSchema() {
        return "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            +   "\"destination\":{\"type\":\"string\","
            +     "\"description\":\"ADT destination ID (use abap_list_destinations to discover)\"},"
            +   "\"objectUri\":{\"type\":\"string\","
            +     "\"description\":\"ADT URI of the object, e.g. /sap/bc/adt/oo/classes/CL_ABAP_TYPEDESCR\"}"
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
            if (!AdtRisVfsObjectPropertiesServiceFactory.isObjectPropertiesServiceAvailable(destination)) {
                return error("Object properties service not available for destination: " + destination);
            }

            IAdtRisVfsObjectPropertiesService svc =
                AdtRisVfsObjectPropertiesServiceFactory.createVfsObjectPropertiesService(destination);
            IAdtRisVfsObjectProperties props =
                svc.readObjectProperties(URI.create(objectUri), new NullProgressMonitor());

            if (props == null) {
                return error("No properties returned for: " + objectUri);
            }

            StringBuilder sb = new StringBuilder(256);
            sb.append("{");
            sb.append("\"name\":").append(Json.str(props.getObjectName())).append(",");
            sb.append("\"type\":").append(Json.str(props.getObjectType())).append(",");
            sb.append("\"package\":").append(Json.str(props.getObjectPackage())).append(",");
            sb.append("\"description\":").append(Json.str(props.getObjectDescription())).append(",");
            sb.append("\"uri\":").append(Json.str(props.getObjectUri() == null ? null : props.getObjectUri().toString())).append(",");
            sb.append("\"version\":").append(Json.str(props.getVersion())).append(",");
            sb.append("\"isExpandable\":").append(props.getObjectIsExpandable()).append(",");
            sb.append("\"representsPackage\":").append(props.getObjectRepresentsPackage());
            sb.append("}");

            String json = sb.toString();
            return AdtMcpToolCallResultBuilder.builder()
                .withStructuredContent(json)
                .withContent(json)
                .isError(false)
                .build();
        } catch (Throwable t) {
            return error("arc1_sap_object_info failed: " + t.getClass().getSimpleName()
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
