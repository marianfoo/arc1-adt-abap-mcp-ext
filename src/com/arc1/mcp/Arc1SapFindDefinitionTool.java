package com.arc1.mcp;

import java.net.URI;

import org.eclipse.core.runtime.NullProgressMonitor;

import com.sap.adt.mcp.core.AdtMcpToolCallResultBuilder;
import com.sap.adt.mcp.core.IAdtMCPTool;
import com.sap.adt.mcp.core.IAdtMcpToolCallResult;
import com.sap.adt.tools.core.AbapCore;
import com.sap.adt.tools.core.IAdtObjectReference;
import com.sap.adt.tools.core.navigation.IAbapNavigationServices;

public class Arc1SapFindDefinitionTool implements IAdtMCPTool {

    @Override
    public String getName() {
        return "arc1_sap_find_definition";
    }

    @Override
    public String getDescription() {
        return "Go-to-definition for an identifier appearing in an ABAP source. "
             + "Pass the URI of the source file you are reading and an identifier inside it "
             + "(class name, method, type, function, etc.). Returns the URI of the definition.";
    }

    @Override
    public String getInputSchema() {
        return "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            +   "\"destination\":{\"type\":\"string\","
            +     "\"description\":\"ADT destination ID\"},"
            +   "\"sourceUri\":{\"type\":\"string\","
            +     "\"description\":\"ADT URI of the source file containing the identifier, e.g. /sap/bc/adt/oo/classes/ZCL_FOO/source/main\"},"
            +   "\"identifier\":{\"type\":\"string\","
            +     "\"description\":\"The token to resolve, e.g. CL_ABAP_TYPEDESCR or LIF_FOO=>METHOD_NAME\"}"
            + "},"
            + "\"required\":[\"destination\",\"sourceUri\",\"identifier\"]"
            + "}";
    }

    @Override
    public IAdtMcpToolCallResult execute(String jsonInput) {
        try {
            String destination = Json.readString(jsonInput, "destination");
            String sourceUri = Json.readString(jsonInput, "sourceUri");
            String identifier = Json.readString(jsonInput, "identifier");

            if (destination == null || destination.isEmpty()) {
                return error("Missing required field: destination");
            }
            if (sourceUri == null || sourceUri.isEmpty()) {
                return error("Missing required field: sourceUri");
            }
            if (identifier == null || identifier.isEmpty()) {
                return error("Missing required field: identifier");
            }

            IAbapNavigationServices nav = AbapCore.getInstance()
                .getAbapNavigationServiceFactory()
                .createNavigationService(destination);

            IAdtObjectReference target = nav.getNavigationTarget(
                URI.create(sourceUri), identifier, new NullProgressMonitor());

            if (target == null) {
                String j = "{\"found\":false,\"reason\":\"no navigation target returned\"}";
                return AdtMcpToolCallResultBuilder.builder()
                    .withStructuredContent(j).withContent(j).isError(false).build();
            }

            StringBuilder sb = new StringBuilder(256);
            sb.append("{\"found\":true,");
            sb.append("\"name\":").append(Json.str(target.getName())).append(",");
            sb.append("\"type\":").append(Json.str(target.getType())).append(",");
            sb.append("\"uri\":").append(Json.str(target.getUri() == null ? null : target.getUri().toString())).append(",");
            sb.append("\"packageName\":").append(Json.str(target.getPackageName())).append(",");
            sb.append("\"description\":").append(Json.str(target.getDescription()));
            sb.append("}");

            String json = sb.toString();
            return AdtMcpToolCallResultBuilder.builder()
                .withStructuredContent(json)
                .withContent(json)
                .isError(false)
                .build();
        } catch (Throwable t) {
            return error("arc1_sap_find_definition failed: " + t.getClass().getSimpleName()
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
