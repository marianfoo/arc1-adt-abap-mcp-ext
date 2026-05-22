# Plan 02: feature-parity push

**Status**: ready to implement
**Phase**: 2 (after Plan 01's quick wins)
**Goal**: Add two more read-only tools that close meaningful gaps without
hitting the workspace-`IFile` complexity wall.

## Why this is scoped the way it is

After implementing Plan 01 we found that **most remaining ARC-1-equivalent
tools need an Eclipse `IFile`** that's been synchronized with the backend:

| ARC-1 tool | Eclipse Java API | Blocker |
|---|---|---|
| `SAPRead` (source) | `IAdtObjectLoader.getAdtObject(IFile)`, etc. | Needs IFile in workspace |
| `SAPLint` (syntax check) | `ISyntaxCheckService.check(IFile, ...)` | Needs IFile |
| `SAPDiagnose` (revisions) | `IAdtObjectHistoryService.getRevisions(monitor, IAdtObject, IFile)` | Needs IFile |
| `SAPActivate` | `IAdtActivationService` | Needs IFile |
| `SAPWrite` | source modification API | Needs IFile + transport plumbing |

These all need a **workspace-sync helper** (~60 lines) that, given a destination
+ object URI, creates the corresponding `IFile` in the workspace project, triggers
the ADT sync, and returns the synced file. That's a substantial enough piece of
infrastructure that it deserves its own plan (Plan 03 or later).

For Plan 02 we focus on **API surfaces that take a URI or destination directly**
and do not need an `IFile`:

## Tools to add

### T5. `arc1_sap_system_info`

**API**: `com.sap.adt.tools.core.AbapCore.getInstance().getAbapSystemInfo(destinationId)`
→ `IAbapSystemInfo`
**Bundle**: `com.sap.adt.tools.core` (already on classpath)
**Public**: yes (`com.sap.adt.tools.core.system.IAbapSystemInfo`)

```java
IAbapSystemInfo info = AbapCore.getInstance().getAbapSystemInfo(destinationId);
List<ISoftwareComponent> components = info.getSoftwareComponents(monitor);
List<IServer> servers = info.getServers(false, monitor);
List<ISystemStatusElement> statusElements = info.getSystemStatusElements(monitor);
```

**MCP input**:
```json
{
  "destination": "A4H_001_marian_en_1",
  "include": ["softwareComponents", "servers", "status"]
}
```

`include` defaults to `["softwareComponents"]` if omitted, to keep responses small.

**MCP output**:
```json
{
  "destination": "A4H_001_marian_en_1",
  "softwareComponents": [
    { "name": "SAP_BASIS", "release": "758", "supportPackage": "0001" },
    { "name": "S4CORE", "release": "108", "supportPackage": "0001" }
  ],
  "servers": [...],
  "status": [...]
}
```

**Use case**: LLM can adapt suggestions based on system release. "You're on
SAP_BASIS 758 (S/4 2023) so you can use RAP managed scenarios."

### T6. `arc1_sap_find_occurrences`

**API**: `com.sap.adt.tools.abapsource.occurrence.IOccurrenceFinderServicesFactory`
**Bundle**: `com.sap.adt.tools.abapsource` (NEW — needs adding to Require-Bundle)
**Public**: yes (`com.sap.adt.tools.abapsource.occurrence.*`)

```java
IOccurrenceFinderServices svc = ...factory.createOccurrenceFinderServices(monitor, destinationId);
IOccurrenceInfo info = svc.getOccurrenceInfoByOffset(monitor, sourceUri, contentType, offset);
List<IOccurrence> occurrences = info.getOccurrences();
// each occurrence: getObjectReference(), getKind(), getStartOffset(), getEndOffset()
```

**Where do we get the factory instance?** `IOccurrenceFinderServicesFactory` is
itself an interface. Concrete instance likely accessed via `AbapSource.getInstance()`
or OSGi service tracker. Quick verification needed before implementation.

**MCP input**:
```json
{
  "destination": "A4H_001_marian_en_1",
  "sourceUri": "/sap/bc/adt/oo/classes/ZCL_FOO/source/main",
  "contentType": "text/x-abap",
  "offset": 1234
}
```

**MCP output**:
```json
{
  "occurrences": [
    {
      "kind": "definition",
      "objectName": "M_BAR",
      "objectType": "METH/I",
      "objectUri": "/sap/bc/adt/oo/classes/ZCL_FOO/includes/instance",
      "startOffset": 1234,
      "endOffset": 1239
    },
    { "kind": "reference", ... }
  ]
}
```

**Use case**: "Where is this method used inside this class?" — finer-grained
than full where-used, scoped to a single source.

## What's deliberately NOT in this plan

| Tool | Why deferred |
|---|---|
| `arc1_sap_read_source` | Needs workspace-IFile sync helper. Substantial enough to be its own plan. |
| `arc1_sap_check_syntax` | Same. |
| `arc1_sap_object_revisions` | Same. |
| `arc1_sap_run_unit_tests` | `IAbapUnitService` is async-polling-heavy. Wait for source read first. |
| `arc1_sap_where_used` (global) | No public Java factory. Needs Eclipse HTTP service call. |
| `arc1_sap_list_transports` | Same. |
| `arc1_sap_atc_run` | Same. |
| Write/activate/delete tools | Need destructive-action safety gates. Plan 04+. |

## Implementation steps

1. **Verify `IOccurrenceFinderServicesFactory` instantiation path**. Two options:
   a. `AbapSource.getInstance().getOccurrenceFinderServicesFactory()` if such method exists.
   b. OSGi service tracker on `IOccurrenceFinderServicesFactory.class`.
   Need to grep the SAP UI bundles for how they instantiate this.
2. **Add `com.sap.adt.tools.abapsource` to `Require-Bundle`** in `MANIFEST.MF`
   and to `BUNDLES` array in `build.sh`.
3. **Create `Arc1SapSystemInfoTool.java`** (~80 lines).
4. **Create `Arc1SapFindOccurrencesTool.java`** (~80 lines).
5. **Add both classes to `plugin.xml`**.
6. **Build + verify all classes still implement `IAdtMCPTool`**.

## Risks + mitigations

- **`IOccurrenceFinderServicesFactory` may not have a clean factory pattern.**
  If it's only accessible via OSGi service tracker, that's still doable (one
  `BundleContext.getServiceReference(...)` call) but adds 10 lines.
- **`getOccurrenceInfoByOffset` may require the source to already be loaded
  on the backend side.** First call may return empty until the user has
  opened the class in Eclipse. Document this in the tool description.
- **`getSoftwareComponents(monitor)` may be slow on first call** (cold cache).
  Acceptable — single call per session typically.

## Self-review

Re-reading:

- **Why not bundle T5+T6 into a generic `arc1_sap_diagnostics` umbrella tool?**
  Because LLMs reason better about narrow tools. SAP's own built-ins follow
  this — `abap_transport-get` vs `abap_transport-create` are split for the
  same reason. Match the convention.
- **Why include all three of softwareComponents/servers/status in T5?**
  Each is a separate backend call. We let the LLM pick what it wants via
  the `include` array to avoid wasted round-trips.
- **Is T6 the right next thing vs. a workspace-IFile-sync helper?**
  T6 is lower risk because it doesn't touch the workspace. We learn one more
  Eclipse Java service before tackling the bigger sync investment.
- **Are these still "feature parity with ARC-1"?**
  Partially. T5 has no direct ARC-1 equivalent — it's a new capability that
  exists *because* we run inside Eclipse. T6 is close to part of ARC-1's
  `SAPContext` / where-used.

Verdict: scope is reasonable. Implement.
