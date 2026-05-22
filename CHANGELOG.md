# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-05-22

Initial release. Wakes the dormant SAP ADT MCP server in Eclipse 2025-09 / ADT 3.58
and contributes 7 read-only tools via the `com.sap.adt.mcp.core.adtMcpTools`
extension point.

### Added
- `Arc1Startup` (IStartup) that reflectively calls `AdtMCPCorePlugin.startMCPServer`
  on workbench startup, with forward-compat detection if SAP later enables it natively.
- `Arc1AutoLogin` background Job that calls `IAdtLogonService.ensureLoggedOn` on the
  configured or first-available ABAP project, populating the destination registry.
- Tools:
  - `arc1_sap_search` — quick repository search (RIS)
  - `arc1_sap_repository_search` — parameterized search with type/package/user filters
  - `arc1_sap_object_info` — object metadata via `IAdtRisVfsObjectPropertiesService`
  - `arc1_sap_find_definition` — go-to-definition via `IAbapNavigationServices`
  - `arc1_sap_list_projects` — workspace ABAP projects with login state
  - `arc1_sap_system_info` — software components, servers, status, clients
  - `arc1_sap_object_types` — workbench type registry catalog
- Configuration via JVM system properties in `eclipse.ini`.
- Token file at `~/.config/arc1/mcp-token.txt` for client config convenience.
- Smoke test script.
