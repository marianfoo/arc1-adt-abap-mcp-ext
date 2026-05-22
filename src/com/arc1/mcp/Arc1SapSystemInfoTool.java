package com.arc1.mcp;

import java.util.List;

import org.eclipse.core.runtime.NullProgressMonitor;

import com.sap.adt.mcp.core.AdtMcpToolCallResultBuilder;
import com.sap.adt.mcp.core.IAdtMCPTool;
import com.sap.adt.mcp.core.IAdtMcpToolCallResult;
import com.sap.adt.tools.core.AbapCore;
import com.sap.adt.tools.core.system.IAbapSystemInfo;
import com.sap.adt.tools.core.system.IClient;
import com.sap.adt.tools.core.system.IServer;
import com.sap.adt.tools.core.system.ISoftwareComponent;
import com.sap.adt.tools.core.system.ISystemStatusElement;

public class Arc1SapSystemInfoTool implements IAdtMCPTool {

    @Override
    public String getName() {
        return "arc1_sap_system_info";
    }

    @Override
    public String getDescription() {
        return "Read SAP system metadata: installed software components (with release+SP), "
             + "application servers, system status elements, and SAP clients. "
             + "Use to ground LLM suggestions in the actual system release (e.g. RAP managed "
             + "scenarios require SAP_BASIS >= 754). 'include' selects which sections to fetch "
             + "to keep responses small. Default: ['softwareComponents'].";
    }

    @Override
    public String getInputSchema() {
        return "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            +   "\"destination\":{\"type\":\"string\","
            +     "\"description\":\"ADT destination ID\"},"
            +   "\"include\":{\"type\":\"array\",\"items\":{\"type\":\"string\","
            +     "\"enum\":[\"softwareComponents\",\"servers\",\"status\",\"clients\"]},"
            +     "\"description\":\"Sections to fetch. Default ['softwareComponents'].\"}"
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
            List<String> include = Json.readStringArray(jsonInput, "include");
            if (include.isEmpty()) {
                include = List.of("softwareComponents");
            }

            IAbapSystemInfo info = AbapCore.getInstance().getAbapSystemInfo(destination);
            if (info == null) {
                return error("AbapSystemInfo not available for destination: " + destination);
            }

            NullProgressMonitor mon = new NullProgressMonitor();
            StringBuilder sb = new StringBuilder(512);
            sb.append("{\"destination\":").append(Json.str(destination));

            if (include.contains("softwareComponents")) {
                List<ISoftwareComponent> sw = info.getSoftwareComponents(mon);
                sb.append(",\"softwareComponents\":[");
                if (sw != null) {
                    for (int i = 0; i < sw.size(); i++) {
                        ISoftwareComponent c = sw.get(i);
                        if (i > 0) sb.append(",");
                        sb.append("{");
                        sb.append("\"id\":").append(Json.str(c.getId())).append(",");
                        sb.append("\"release\":").append(Json.str(c.getRelease())).append(",");
                        sb.append("\"supportPackage\":").append(Json.str(c.getSupportPackage())).append(",");
                        sb.append("\"supportPackageLevel\":").append(Json.str(c.getSupportPackageLevel())).append(",");
                        sb.append("\"description\":").append(Json.str(c.getDescription())).append(",");
                        sb.append("\"componentType\":").append(Json.str(c.getComponentType()));
                        sb.append("}");
                    }
                }
                sb.append("]");
            }

            if (include.contains("servers")) {
                List<IServer> servers = info.getServers(false, mon);
                sb.append(",\"servers\":[");
                if (servers != null) {
                    for (int i = 0; i < servers.size(); i++) {
                        IServer s = servers.get(i);
                        if (i > 0) sb.append(",");
                        sb.append("{\"name\":").append(Json.str(s.getName()))
                          .append(",\"host\":").append(Json.str(s.getHost())).append("}");
                    }
                }
                sb.append("]");
            }

            if (include.contains("status")) {
                List<ISystemStatusElement> status = info.getSystemStatusElements(mon);
                sb.append(",\"status\":[");
                if (status != null) {
                    for (int i = 0; i < status.size(); i++) {
                        ISystemStatusElement s = status.get(i);
                        if (i > 0) sb.append(",");
                        sb.append("{\"id\":").append(Json.str(s.getId()))
                          .append(",\"content\":").append(Json.str(s.getContent())).append("}");
                    }
                }
                sb.append("]");
            }

            if (include.contains("clients")) {
                List<IClient> clients = info.getClients(mon);
                sb.append(",\"clients\":[");
                if (clients != null) {
                    for (int i = 0; i < clients.size(); i++) {
                        IClient c = clients.get(i);
                        if (i > 0) sb.append(",");
                        sb.append("{\"id\":").append(Json.str(c.getId()))
                          .append(",\"description\":").append(Json.str(c.getDescription())).append("}");
                    }
                }
                sb.append("]");
            }

            sb.append("}");
            String json = sb.toString();
            return AdtMcpToolCallResultBuilder.builder()
                .withStructuredContent(json)
                .withContent(json)
                .isError(false)
                .build();
        } catch (Throwable t) {
            return error("arc1_sap_system_info failed: " + t.getClass().getSimpleName()
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
