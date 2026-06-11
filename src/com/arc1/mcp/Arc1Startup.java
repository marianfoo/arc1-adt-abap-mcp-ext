package com.arc1.mcp;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.IStartup;

/**
 * Workbench startup hook for the ARC-1 MCP extension.
 *
 * <p>As of ADT 3.60 the ADT MCP server is a <em>supported</em> feature with
 * its own activation surface:
 * <ul>
 *   <li><b>Preferences → ABAP Development → MCP Server</b> — enable the server,
 *       set the port and bearer token (the page starts it immediately on Apply
 *       and auto-generates a token if the field is blank); and</li>
 *   <li><b>{@code -DadtMcpServerPrefEnabled=true}</b> in {@code eclipse.ini} —
 *       makes SAP's {@code AdtMcpUIStartupHandler} auto-start the server on
 *       every boot.</li>
 * </ul>
 *
 * <p>Because SAP now owns activation, this plugin no longer starts the server
 * itself. It is a <b>pure tool-provider</b>: the {@code <mcpTool>} contributions
 * in {@code plugin.xml} are picked up by SAP's own {@code ToolRegistrationService}
 * whenever the server starts — no reflection into SAP internals, nothing to
 * kickstart. (Earlier releases reflectively woke the dormant 3.58 server; that
 * hack is gone — see docs/decisions.md D9.)
 *
 * <p>The only thing this startup hook still does is optionally pre-warm the
 * destination logon so backend-touching tools succeed on the first call. It
 * uses public ADT APIs only.
 *
 * <p>Knobs (set via eclipse.ini under -vmargs):
 * <pre>
 *   -Darc1.mcp.destination=...   prefer this destination ID for auto-login
 *   -Darc1.mcp.autologin=false   disable auto-login (default true)
 * </pre>
 */
public class Arc1Startup implements IStartup {

    @Override
    public void earlyStartup() {
        try {
            log(IStatus.INFO,
                "ARC-1 MCP extension: tool contributions registered. The ADT MCP "
                + "server is started by SAP — enable it under Preferences → ABAP "
                + "Development → MCP Server, and add -DadtMcpServerPrefEnabled=true "
                + "to eclipse.ini so it auto-starts on boot.",
                null);

            if (Boolean.parseBoolean(System.getProperty("arc1.mcp.autologin", "true"))) {
                Arc1AutoLogin.attempt(
                    msg -> log(IStatus.INFO, "ARC-1 MCP extension: " + msg, null),
                    (msg, t) -> log(IStatus.WARNING, "ARC-1 MCP extension: " + msg, t));
            }
        } catch (Throwable t) {
            log(IStatus.ERROR, "ARC-1 MCP extension: startup hook failed", t);
        }
    }

    private void log(int severity, String msg, Throwable t) {
        Arc1McpActivator p = Arc1McpActivator.getDefault();
        if (p != null) {
            p.getLog().log(new Status(severity, Arc1McpActivator.PLUGIN_ID, msg, t));
        } else {
            System.err.println("[arc1-mcp] " + msg);
            if (t != null) {
                t.printStackTrace();
            }
        }
    }
}
