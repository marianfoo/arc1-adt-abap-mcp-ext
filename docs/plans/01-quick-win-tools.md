# Plan 01: Quick-win tools

**Status**: ready to implement
**Phase**: 1 (first batch beyond `arc1_sap_search`)
**Goal**: Add 4 read-only tools that wrap clean public ADT Java APIs

## Tools to add

### T1. `arc1_sap_object_info`

**API**: `com.sap.adt.ris.search.objectproperties.AdtRisVfsObjectPropertiesServiceFactory`
**Bundle**: `com.sap.adt.ris.search` (already on our classpath)
**Public**: yes

```java
IAdtRisVfsObjectPropertiesService svc =
    AdtRisVfsObjectPropertiesServiceFactory.createVfsObjectPropertiesService(destinationId);
IAdtRisVfsObjectProperties props =
    svc.readObjectProperties(URI.create(objectUri), monitor);
```

**MCP input**:
```json
{
  "destination": "A4H_001_marian_en_1",
  "objectUri": "/sap/bc/adt/oo/classes/CL_ABAP_TYPEDESCR"
}
```

**MCP output**:
```json
{
  "name": "CL_ABAP_TYPEDESCR",
  "type": "CLAS/OC",
  "package": "SABP_TYPES",
  "description": "Type description ...",
  "uri": "/sap/bc/adt/oo/classes/CL_ABAP_TYPEDESCR",
  "version": "active",
  "isExpandable": true,
  "representsPackage": false
}
```

**Use case**: After `arc1_sap_search` returns a list of URIs, the LLM can hydrate any of them with full metadata.

---

### T2. `arc1_sap_repository_search`

**API**: `com.sap.adt.ris.search.AdtRepositorySearchFactory` (full, parameterized search)
**Bundle**: `com.sap.adt.ris.search` (already on classpath)
**Public**: yes

```java
IAdtRepositorySearchService svc =
    AdtRepositorySearchFactory.createAdtRepositorySearchService();
IAdtRepositorySearchParameters params =
    AdtRepositorySearchFactory.createAdtRepositorySearchParameters();
params.setObjectTypes(List.of("CLAS", "INTF"));
params.setPackages(List.of("$TMP", "Z_*"));
params.setMaxResults(50);
params.setUseTrailingWildcard(true);
List<IAdtObjectReference> hits =
    svc.search(destinationId, monitor, query, params);
```

**MCP input**:
```json
{
  "destination": "A4H_001_marian_en_1",
  "query": "ZARC1",
  "objectTypes": ["CLAS", "INTF"],
  "packages": ["$TMP"],
  "users": [],
  "releaseStates": [],
  "maxResults": 50,
  "useTrailingWildcard": true
}
```

**MCP output**: same shape as `arc1_sap_search` (hits array + count).

**Distinction from `arc1_sap_search`**: this one supports type/package/user/release filters. Use `arc1_sap_search` for fuzzy name-only, this for precise scoped queries.

---

### T3. `arc1_sap_find_definition`

**API**: `com.sap.adt.tools.core.AbapCore` + `IAbapNavigationServicesFactory` + `IAbapNavigationServices`
**Bundle**: `com.sap.adt.tools.core` (already on classpath)
**Public**: yes

```java
IAbapNavigationServices nav =
    AbapCore.getInstance().getAbapNavigationServiceFactory()
        .createNavigationService(destinationId);
IAdtObjectReference target =
    nav.getNavigationTarget(URI.create(sourceUri), identifier, monitor);
```

**MCP input**:
```json
{
  "destination": "A4H_001_marian_en_1",
  "sourceUri": "/sap/bc/adt/oo/classes/ZCL_FOO/source/main",
  "identifier": "CL_ABAP_TYPEDESCR"
}
```

**MCP output**:
```json
{
  "found": true,
  "name": "CL_ABAP_TYPEDESCR",
  "type": "CLAS/OC",
  "uri": "/sap/bc/adt/oo/classes/CL_ABAP_TYPEDESCR",
  "packageName": "SABP_TYPES",
  "description": "..."
}
```

Handles `AbapNavigationException` and `MultipleNavigationTargetsException` cleanly (return error with `isError: true`).

**Use case**: classic IDE "go to definition" — accept a class/interface/function name appearing in source and resolve the actual ABAP object.

---

### T4. `arc1_sap_list_projects`

**API**: `com.sap.adt.tools.core.project.AdtProjectServiceFactory` (we already use this for autologin)
**Bundle**: `com.sap.adt.tools.core.base` (already on classpath)
**Public**: yes

```java
IAbapProjectService svc = AdtProjectServiceFactory.createProjectService();
IProject[] projects = svc.getAvailableAbapProjects();
for (IProject p : projects) {
    String destId = svc.getDestinationId(p);
    IStatus accessible = svc.isProjectAccessible(p);
    boolean loggedOn = AdtLogonServiceFactory.createLogonService().isLoggedOn(destId);
    // ...
}
```

**MCP input**: `{}` (no parameters)

**MCP output**:
```json
{
  "projects": [
    {
      "name": "A4H_001_marian_en_1",
      "destinationId": "A4H_001_marian_en_1",
      "systemId": "A4H",
      "open": true,
      "accessible": true,
      "loggedOn": true
    }
  ]
}
```

**Distinction from built-in `abap_list_destinations`**: that one returns just a flat list of destination keys. This returns workspace project info incl. login state, so the LLM can decide whether to nudge the user to log in.

---

## What I deliberately deferred to Plan 02

- **`arc1_sap_read_source`** — Eclipse's `IAbapSourceSfsUtil` synchronizes files into the workspace then expects you to read via Eclipse resource APIs. More complex than the rest. Alternative path: call the ADT HTTP endpoint `/sap/bc/adt/<type>/<name>/source/main` directly via Eclipse's HTTP service. Either way, ~100+ lines. Worth a dedicated plan iteration.
- **`arc1_sap_check_syntax`** — `IAdtCheckService.check(IFile, monitor, ...)` needs an `IFile`. Requires synchronizing the object into the workspace first, same complexity as source reader.
- **`arc1_sap_text_search`** — needs `IServiceParameterProvider` / `IServiceUriProvider` implementations. Worth it but not "quick".
- **`arc1_sap_list_package_contents`** — `IPackageTreeService` only available via OSGi service tracker, no public factory.
- **`arc1_sap_run_unit_tests`** — `IAbapUnitService` is real but the surface is large (RunRequest, RunStatus, async polling). Defer.
- **`arc1_sap_where_used`** — no public Java factory. Needs HTTP call via Eclipse's HTTP client.
- **`arc1_sap_list_transports`** — only HTTP-side API. Plan 02.

## Implementation plan

1. **One new tool class per tool** (T1–T4), parallel to `Arc1SapSearchTool`. Total ~250 lines.
2. **Refactor JSON helpers** if needed (probably add `Json.optArray` / `Json.objStart`) — keep it minimal.
3. **Update `plugin.xml`** — 4 new `<mcpTool class="..."/>` lines.
4. **Update `MANIFEST.MF`** — no new bundles needed (everything we need is already on the Require-Bundle list except for the `org.eclipse.emf.ecore` for IAdtObjectReference, already there).
5. **Update `build.sh`** — no bundle list change needed.
6. **Build** — should compile cleanly. JAR grows from 12 KB to ~20 KB.

## Smoke test

After install + `-clean` restart, expect `tools/list` to return 9 tools (5 ours + 4 SAP). Tests:

1. **arc1_sap_object_info** with a known URI:
   ```json
   {"destination":"A4H_001_marian_en_1","objectUri":"/sap/bc/adt/oo/classes/CL_ABAP_TYPEDESCR"}
   ```
   Expect: name/type/package fields populated.

2. **arc1_sap_repository_search** with filters:
   ```json
   {"destination":"A4H_001_marian_en_1","query":"CL_","objectTypes":["CLAS"],"packages":["SABP*"],"maxResults":10}
   ```
   Expect: hits filtered to CLAS only inside SABP* packages.

3. **arc1_sap_find_definition** — pick a class with a known reference inside it:
   ```json
   {"destination":"A4H_001_marian_en_1","sourceUri":"<from search>","identifier":"CL_ABAP_TYPEDESCR"}
   ```
   Expect: target URI back.

4. **arc1_sap_list_projects** — `{}`. Expect at least `A4H_001_marian_en_1` with `loggedOn: true`.

## Risk + rollback

- All four tools follow the exact pattern of `Arc1SapSearchTool` — minimal blast radius.
- Compile errors caught by `build.sh` before any install.
- If a tool's class fails the extension-point validator (bad name/schema), the validator drops it silently with an Error Log entry — the rest still load.
- Rollback: remove the JAR from `dropins/`, `-clean` restart.

## Self-review

Re-reading the plan after writing it. Things I considered but rejected:

- **Add a `mcpTool` for "list logged-in destinations" only** — already covered by SAP's `abap_list_destinations`. Don't duplicate; my `arc1_sap_list_projects` returns the richer project-level data that SAP's doesn't.
- **Combine T1+T2 into one tool with `action` discriminator** — that's the ARC-1 intent-tool pattern, but it makes each tool's input schema harder for LLMs to reason about. SAP's built-in tools are one-purpose-per-tool — match that convention.
- **Skip `arc1_sap_find_definition` because of the URI prerequisite** — the URI comes from `arc1_sap_search` output naturally. Workflow: search to find candidates → find_definition to navigate. Clean.
- **Skip `arc1_sap_list_projects` because `abap_list_destinations` exists** — the loggedOn/accessible state is valuable enough on its own; LLMs can use it to decide whether to ask the user to log in.

Verdict: plan is sized correctly. ~250 lines of Java for ~4 tools, all backed by confirmed public APIs. Implement.
