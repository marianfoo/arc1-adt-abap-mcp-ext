package com.arc1.mcp;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;

import com.sap.adt.destinations.logon.AdtLogonServiceFactory;
import com.sap.adt.destinations.logon.IAdtLogonService;
import com.sap.adt.destinations.model.IDestinationData;
import com.sap.adt.project.IAdtCoreProject;
import com.sap.adt.tools.core.project.AdtProjectServiceFactory;
import com.sap.adt.tools.core.project.IAbapProjectService;

/**
 * Auto-logs into an ABAP project after Eclipse startup so the destination
 * registry is populated by the time MCP clients call backend-dependent tools.
 *
 * Runs on a background Eclipse Job (not the workbench startup thread) so
 * we don't block Eclipse boot if a logon dialog pops up.
 *
 * Picks the destination in priority order:
 *   1. -Darc1.mcp.destination=<exact-id>  (e.g. A4H_001_marian_en_1)
 *   2. First available ABAP project in the workspace
 */
final class Arc1AutoLogin {

    private Arc1AutoLogin() {
    }

    static void attempt(Consumer<String> info, BiConsumer<String, Throwable> warn) {
        Job job = Job.create("ARC-1 MCP auto-login", monitor -> {
            try {
                String result = doAttempt(info, warn);
                if (result != null) {
                    info.accept("Auto-login succeeded for destination: " + result);
                }
            } catch (Throwable t) {
                warn.accept("Auto-login threw", t);
            }
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        job.setSystem(false);
        job.schedule(2000L); // 2s delay lets ADT finish restoring projects
    }

    private static String doAttempt(Consumer<String> info, BiConsumer<String, Throwable> warn) throws Exception {
        IAbapProjectService projSvc = AdtProjectServiceFactory.createProjectService();
        IProject[] projects = projSvc.getAvailableAbapProjects();
        if (projects == null || projects.length == 0) {
            info.accept("No ABAP projects in workspace; skipping auto-login.");
            return null;
        }

        String preferredDestId = System.getProperty("arc1.mcp.destination");
        IProject target = null;
        String targetDestId = null;
        for (IProject p : projects) {
            String destId = projSvc.getDestinationId(p);
            if (preferredDestId != null && preferredDestId.equals(destId)) {
                target = p;
                targetDestId = destId;
                break;
            }
        }
        if (target == null) {
            target = projects[0];
            targetDestId = projSvc.getDestinationId(target);
        }
        if (targetDestId == null) {
            info.accept("Picked project " + target.getName() + " but it has no destination id; skipping.");
            return null;
        }

        IAdtLogonService logon = AdtLogonServiceFactory.createLogonService();
        if (logon.isLoggedOn(targetDestId)) {
            info.accept("Destination already logged in: " + targetDestId);
            return targetDestId;
        }

        IAdtCoreProject corePrj = target.getAdapter(IAdtCoreProject.class);
        if (corePrj == null) {
            info.accept("Project " + target.getName() + " has no IAdtCoreProject adapter; skipping.");
            return null;
        }
        IDestinationData destData = corePrj.getDestinationData();
        if (destData == null) {
            info.accept("Project " + target.getName() + " has no IDestinationData; skipping.");
            return null;
        }

        info.accept("Attempting auto-login: " + targetDestId);
        IStatus status = logon.ensureLoggedOn(destData, null, new NullProgressMonitor());
        if (status == null || status.isOK()) {
            return targetDestId;
        }
        warn.accept("ensureLoggedOn returned non-OK status: " + status.getMessage(), null);
        return null;
    }
}
