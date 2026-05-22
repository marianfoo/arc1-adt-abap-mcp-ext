package com.arc1.mcp;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.IStartup;
import org.osgi.framework.Bundle;

/**
 * Kick-starts SAP's dormant ADT MCP server during Eclipse workbench startup
 * and auto-logs into the configured (or first available) ABAP project so
 * the destination registry is populated by the time tool calls arrive.
 *
 * Forward-compat: if the MCP server is already running (e.g. once SAP ships
 * an activation switch in a future ADT release), we detect that and skip
 * the reflective kickstart entirely, leaving SAP's token/state alone.
 *
 * Knobs (set via eclipse.ini under -vmargs):
 *   -Darc1.mcp.port=NNNNN        override default port (54322)
 *   -Darc1.mcp.token=mysecret    pin a static bearer token across restarts
 *   -Darc1.mcp.destination=...   prefer this destination ID for auto-login
 *   -Darc1.mcp.autologin=false   disable auto-login (default true)
 *   -Darc1.mcp.kickstart=false   disable kickstart (default true)
 */
public class Arc1Startup implements IStartup {

    private static final String MCP_BUNDLE = "com.sap.adt.mcp.core";
    private static final String MCP_PLUGIN_CLASS = "com.sap.adt.mcp.core.internal.AdtMCPCorePlugin";
    private static final int DEFAULT_PORT = 54322;

    @Override
    public void earlyStartup() {
        try {
            Bundle bundle = Platform.getBundle(MCP_BUNDLE);
            if (bundle == null) {
                throw new IllegalStateException("Bundle " + MCP_BUNDLE + " not found");
            }
            bundle.start();
            Class<?> pluginCls = bundle.loadClass(MCP_PLUGIN_CLASS);
            Object plugin = pluginCls.getMethod("getInstance").invoke(null);
            if (plugin == null) {
                throw new IllegalStateException("AdtMCPCorePlugin.getInstance() returned null");
            }

            Integer existingPort = peekRunningPort(pluginCls, plugin);
            if (existingPort != null) {
                writeTokenFileForExternal(existingPort.intValue());
                log(IStatus.INFO,
                    "ARC-1 MCP extension: MCP server already running on port " + existingPort
                    + " (token managed externally). arc1_sap_search is still registered via the extension point.",
                    null);
            } else if (!Boolean.parseBoolean(System.getProperty("arc1.mcp.kickstart", "true"))) {
                log(IStatus.INFO,
                    "ARC-1 MCP extension: kickstart disabled (-Darc1.mcp.kickstart=false). Waiting for SAP to start the MCP server.",
                    null);
            } else {
                String token = resolveToken();
                int port = kickstartMcpServer(pluginCls, plugin, token);
                writeTokenFile(port, token);
                log(IStatus.INFO,
                    "ARC-1 MCP extension: MCP server started on http://localhost:" + port + "/mcp",
                    null);
            }

            if (Boolean.parseBoolean(System.getProperty("arc1.mcp.autologin", "true"))) {
                Arc1AutoLogin.attempt(msg -> log(IStatus.INFO, "ARC-1 MCP extension: " + msg, null),
                    (msg, t) -> log(IStatus.WARNING, "ARC-1 MCP extension: " + msg, t));
            }
        } catch (Throwable t) {
            log(IStatus.ERROR, "ARC-1 MCP extension: failed to start MCP server", t);
        }
    }

    private Integer peekRunningPort(Class<?> pluginCls, Object plugin) {
        try {
            Field mcpServerField = pluginCls.getDeclaredField("mcpServer");
            mcpServerField.setAccessible(true);
            Object mcpServer = mcpServerField.get(plugin);
            if (mcpServer == null) {
                return null;
            }
            Field httpServerField = mcpServer.getClass().getDeclaredField("httpServer");
            httpServerField.setAccessible(true);
            Object httpServer = httpServerField.get(mcpServer);
            if (httpServer == null) {
                return null;
            }
            Method getLocalPort = httpServer.getClass().getMethod("getLocalPort");
            Object port = getLocalPort.invoke(httpServer);
            return port instanceof Integer ? (Integer) port : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private int kickstartMcpServer(Class<?> pluginCls, Object plugin, String token) throws Exception {
        // Equinox's legacy servlet registration stores the current thread's
        // context classloader in a Hashtable, which NPEs if it is null (Codex
        // hit this from an attach-agent thread). IStartup.earlyStartup() runs
        // with a non-null CL, but pinning it to the MCP bundle's CL is free
        // insurance against future regressions.
        ClassLoader previousCl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(pluginCls.getClassLoader());
        try {
            int port = Integer.getInteger("arc1.mcp.port", DEFAULT_PORT).intValue();
            Method start = pluginCls.getMethod("startMCPServer", int.class, String.class);
            Object result = start.invoke(plugin, port, token);
            return ((Integer) result).intValue();
        } finally {
            Thread.currentThread().setContextClassLoader(previousCl);
        }
    }

    private String resolveToken() {
        String configured = System.getProperty("arc1.mcp.token");
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void writeTokenFile(int port, String token) throws IOException {
        Path file = tokenFilePath();
        String payload =
              "PORT=" + port + "\n"
            + "TOKEN=" + token + "\n"
            + "URL=http://localhost:" + port + "/mcp\n";
        Files.writeString(file, payload);
    }

    private void writeTokenFileForExternal(int port) throws IOException {
        Path file = tokenFilePath();
        String payload =
              "PORT=" + port + "\n"
            + "URL=http://localhost:" + port + "/mcp\n"
            + "SAP_CONTROLLED=true\n"
            + "NOTE=Token managed by SAP. arc1_sap_search is registered via the extension point.\n";
        Files.writeString(file, payload);
    }

    private Path tokenFilePath() throws IOException {
        Path dir = Path.of(System.getProperty("user.home"), ".config", "arc1");
        Files.createDirectories(dir);
        return dir.resolve("mcp-token.txt");
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
