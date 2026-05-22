package com.arc1.mcp;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IStatus;

import com.sap.adt.destinations.logon.AdtLogonServiceFactory;
import com.sap.adt.destinations.logon.IAdtLogonService;
import com.sap.adt.mcp.core.AdtMcpToolCallResultBuilder;
import com.sap.adt.mcp.core.IAdtMCPTool;
import com.sap.adt.mcp.core.IAdtMcpToolCallResult;
import com.sap.adt.tools.core.project.AdtProjectServiceFactory;
import com.sap.adt.tools.core.project.IAbapProjectService;

public class Arc1SapListProjectsTool implements IAdtMCPTool {

    @Override
    public String getName() {
        return "arc1_sap_list_projects";
    }

    @Override
    public String getDescription() {
        return "List Eclipse ABAP projects in the current workspace with login state. "
             + "Returns project name, destination ID, system ID, accessibility, and whether "
             + "the destination is currently logged in. Use this to decide whether to "
             + "prompt the user to log on before running backend-dependent tools.";
    }

    @Override
    public String getInputSchema() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    @Override
    public IAdtMcpToolCallResult execute(String jsonInput) {
        try {
            IAbapProjectService projSvc = AdtProjectServiceFactory.createProjectService();
            IAdtLogonService logon = AdtLogonServiceFactory.createLogonService();
            IProject[] projects = projSvc.getAvailableAbapProjects();
            int n = projects == null ? 0 : projects.length;

            StringBuilder sb = new StringBuilder(64 + n * 128);
            sb.append("{\"count\":").append(n).append(",\"projects\":[");
            for (int i = 0; i < n; i++) {
                IProject p = projects[i];
                if (i > 0) {
                    sb.append(",");
                }
                String destId = safe(() -> projSvc.getDestinationId(p));
                String systemId = safeSystemId(p);
                boolean open = safeBool(() -> p.isOpen(), false);
                IStatus accessible = safeStatus(() -> projSvc.isProjectAccessible(p));
                boolean loggedOn = destId != null && safeBool(() -> logon.isLoggedOn(destId), false);

                sb.append("{");
                sb.append("\"name\":").append(Json.str(p.getName())).append(",");
                sb.append("\"destinationId\":").append(Json.str(destId)).append(",");
                sb.append("\"systemId\":").append(Json.str(systemId)).append(",");
                sb.append("\"open\":").append(open).append(",");
                sb.append("\"accessible\":").append(accessible != null && accessible.isOK()).append(",");
                sb.append("\"accessibleMessage\":").append(Json.str(accessible == null ? null : accessible.getMessage())).append(",");
                sb.append("\"loggedOn\":").append(loggedOn);
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
            return error("arc1_sap_list_projects failed: " + t.getClass().getSimpleName()
                + ": " + String.valueOf(t.getMessage()));
        }
    }

    private static String safeSystemId(IProject p) {
        try {
            // IAbapProjectService doesn't expose getSystemId directly,
            // but we can read it from the project's IAdtCoreProject adapter when available.
            Object adt = p.getAdapter(com.sap.adt.project.IAdtCoreProject.class);
            if (adt instanceof com.sap.adt.project.IAdtCoreProject) {
                return ((com.sap.adt.project.IAdtCoreProject) adt).getSystemId();
            }
        } catch (Throwable t) {
            // ignored — best effort
        }
        return null;
    }

    private interface SupplierE<T> { T get() throws Throwable; }

    private static <T> T safe(SupplierE<T> s) {
        try { return s.get(); } catch (Throwable t) { return null; }
    }

    private static boolean safeBool(SupplierE<Boolean> s, boolean fallback) {
        try { Boolean b = s.get(); return b == null ? fallback : b.booleanValue(); }
        catch (Throwable t) { return fallback; }
    }

    private static IStatus safeStatus(SupplierE<IStatus> s) {
        try { return s.get(); } catch (Throwable t) { return null; }
    }

    private static IAdtMcpToolCallResult error(String msg) {
        return AdtMcpToolCallResultBuilder.builder()
            .withContent(msg)
            .isError(true)
            .build();
    }
}
