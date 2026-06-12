# Research: expanding the tool surface (informed by `adt-ls` + `arc-1-lsp`)

**Date**: 2026-06-12
**Status**: research / proposal (no code yet)
**References**:
- [`marianfoo/adt-ls`](https://github.com/marianfoo/adt-ls) — TypeScript SDK over
  SAP's headless `adt-ls` language server (LSP + experimental MCP). Its
  `docs/capability-matrix.md` is the cleanest enumeration of what the ADT engine
  can do.
- [`marianfoo/arc-1-lsp`](https://github.com/marianfoo/arc-1-lsp) — a Node MCP
  server that delegates to that same headless `adt-ls`. Ships **39 tools** and,
  crucially, a CFR-decompiled capability map
  (`docs/research/adt-ls-capability-map.md`: 23 `adtLs/*` segments, ~92 methods).

> These two projects do **not** become dependencies of this plugin — see
> [decisions.md D10](../decisions.md#d10-adt-ls-is-a-sibling-project-not-a-dependency).
> They are used here purely as a **capability map**: they enumerate, from a
> live-verified headless ADT, exactly which operations exist and are valuable.
> We then reach the same operations our own way — Eclipse Java services or
> `AdtHttp` REST, in-process.

---

## 1. TL;DR — the recommendation

1. **The biggest unlock is a reframing, not a feature.** The repo's earlier plans
   (01/02) hit an "`IFile` wall": many capabilities (syntax check, unit tests,
   revisions, where-used) were deferred because the *Eclipse Java* service for
   them wants a workspace `IFile`. Those plans **predate the `AdtHttp` REST layer**
   (added v0.2). For a **read-only** plugin, almost all of those capabilities are
   reachable **right now** via REST against the object's **active version** — no
   `IFile`, no workspace-sync helper. The sync helper drops from "blocking
   prerequisite" to "optional, only if we ever want to check *unsaved* edits"
   (which a read-only MCP client doesn't have).
2. **Ship one infra helper first: `Xml`** (JDK `javax.xml`, no new dependency).
   Every high-value remaining capability returns ADT XML. Today `list_transports`
   parses it with hand-rolled regex. A ~40-line DOM helper makes every future
   XML tool robust and keeps them at ~80 lines each.
3. **Use the escape hatch as a prototyper.** `arc1_sap_http_get` / `http_post`
   already reach *any* `/sap/bc/adt/...` endpoint live. Every "needs an HTTP
   trace to confirm the body shape" blocker in the roadmap can be retired by
   confirming the request/response with the escape hatch first, then promoting
   it to a first-class, schema'd tool. No external capture tooling needed.
4. **Then add tools in value order** (Section 6): `where_used`,
   `package_contents`, `object_structure`, `find_occurrences`, `list_inactive`,
   `check_syntax`, `run_unit_tests`, `atc_check`.

---

## 2. Where we are today

**11 plugin tools**, in two access styles:

| Access style | Tools | How |
|---|---|---|
| **Eclipse Java service** (D1) | `search`, `repository_search`, `object_info`, `find_definition`, `list_projects`, `system_info`, `object_types` | Public ADT factories (`AdtRisQuickSearchFactory`, `AdtRisVfsObjectPropertiesServiceFactory`, `IAbapNavigationServices`, …) called in-process |
| **`AdtHttp` REST** | `read_source`, `list_transports`, `http_get`, `http_post` | `AdtHttp.get/post(destination, "/sap/bc/adt/…", accept)` over Eclipse's authenticated `IStatelessSystemSession` |

Both styles are first-class. The Java path is cleaner when a public factory
exists; the REST path is the universal fallback and the only path for endpoints
with no public Java factory (where-used, transports, ATC, node structure).

**Coverage vs. the reference surfaces** (read-only capabilities only — mutating
is out of scope, Section 8):

| Capability (adt-ls / arc-1-lsp name) | Have it? | Notes |
|---|---|---|
| repository search | ✅ `search` + `repository_search` | |
| read source | ✅ `read_source` | |
| object metadata | ✅ `object_info` | |
| go-to-definition | ✅ `find_definition` | |
| object type catalog | ✅ `object_types` | |
| system / software components | ✅ `system_info` | no direct reference equivalent — exists *because* we're in Eclipse |
| list transports | ✅ `list_transports` | |
| **where-used / find references** | ❌ | high value; on roadmap; REST |
| **document symbols / object structure** | ❌ | high value; REST |
| **package / node structure** | ❌ | high value for code exploration; REST |
| **local occurrences** (in-source where-used) | ❌ | **already researched as Plan 02 T6, never shipped**; Java API |
| **list inactive objects** | ❌ | simple GET |
| **syntax check** (no activation) | ❌ | REST `checkruns` against active version |
| **unit tests** (+ coverage) | ❌ | REST `abapunit/testruns` |
| **ATC** (static analysis) | ❌ | REST, multi-step |
| type hierarchy | ❌ | niche |
| hover / element info | ❌ | niche; see gotcha §5.4 |
| pretty-printer / format | ❌ | read-only transform |
| object revisions / versions | ❌ | REST |

---

## 3. The three access paths and the decision tree

For any new capability, choose the path in this order:

```
1. Is there a PUBLIC Eclipse Java factory that takes (destinationId, uri/identifier)
   and returns a model object?           → wrap it (D1 style). Cleanest.
        e.g. find_occurrences: IOccurrenceFinderServicesFactory
2. Else, is there a stable ADT REST endpoint reachable by GET/POST with a URI
   (and maybe a small XML body)?          → AdtHttp + Xml helper.
        e.g. where_used, package_contents, check_syntax, unit tests, ATC
3. Does the only Java path require a workspace IFile?  → prefer path 2 (REST,
   active version). Only build the IFile-sync helper if we genuinely need to
   operate on UNSAVED source (a read-only MCP client never does).
```

**Why path 3 rarely bites a read-only plugin:** the `IFile` requirement exists so
Eclipse can check/activate/diff *editor buffer* content that isn't on the backend
yet. Our clients don't edit — they read what's already in the system. The backend
already has the active (and inactive) versions; REST endpoints accept a URI +
version and operate server-side. So "needs an `IFile`" (the wall in Plan 02)
collapses to "POST the URI" for our use cases.

---

## 4. The reference capability surface (condensed)

From `adt-ls`'s capability matrix and `arc-1-lsp`'s 39 tools, the **read-only**
operations worth mirroring, with the underlying ADT operation:

| Reference capability | adt-ls method | arc-1-lsp tool | Underlying ADT (REST) |
|---|---|---|---|
| where-used | `navigation.findReferences` | `find_references` | `POST /sap/bc/adt/repository/informationsystem/usageReferences` |
| object outline | `navigation.documentSymbols` | `document_symbols` | `GET …/objectstructure` |
| package/node tree | `repository.*` | (search-side) | `POST /sap/bc/adt/repository/nodestructure` |
| inactive objects | `repository.listInactive` | `list_inactive_objects` | `GET /sap/bc/adt/activation/inactiveobjects` |
| syntax check | `navigation.checkSyntax` | `check_syntax` | `POST /sap/bc/adt/checkruns?reporters=abapCheckRun` |
| unit tests (+coverage) | `quality.runUnitTestsWithCoverage` | `run_unit_tests_with_coverage` | `POST /sap/bc/adt/abapunit/testruns` |
| ATC | `quality.runAtc` | `run_atc` / `list_atc_variants` | `POST /sap/bc/adt/atc/worklists` + `…/runs` |
| type hierarchy | `navigation.typeHierarchy` | `type_hierarchy` | `GET …/objectstructure` / typehierarchy feed |
| hover / element info | `navigation.hover` | `hover` | `POST /sap/bc/adt/abapsource/elementinfo` |
| format | `navigation.format` | (n/a) | `POST /sap/bc/adt/abapsource/prettyprinter` |
| local occurrences | — | (subset of `find_references`) | Java: `IOccurrenceFinderServices` |

**Two field-verified gotchas from `arc-1-lsp`'s decompile (worth importing):**

- **ATC "empty variant" works:** calling the ATC run with an *empty* check-variant
  triggers the system default variant — don't require the caller to know a variant
  name.
- **Hover needs a primer:** `adt-ls` hover returns null unless
  `textDocument/semanticTokens/full` ran first (token cache gate). Our REST
  `elementinfo` path doesn't have that gate, but it's a flag that hover/element
  info is the *fussiest* of the candidates — deprioritize it.

---

## 5. Tooling improvements (infrastructure, not new tools)

### 5.1 `Xml` helper (JDK `javax.xml`) — **do this first**

Every remaining high-value capability returns ADT XML. Today the only XML tool
(`list_transports`) extracts fields with two regexes (`REQ_BLOCK`, `ATTR`), which
is fragile (namespaces, attribute order, nested elements). The JDK ships
`javax.xml.parsers.DocumentBuilder` / XPath — **built in, not a third-party
dependency** (D8 stays satisfied). A ~40-line helper:

```java
final class Xml {
    static Document parse(String xml) { /* DocumentBuilderFactory, NS-aware */ }
    static List<Element> elements(Node ctx, String localName) { /* by local name, NS-agnostic */ }
    static String attr(Element e, String localName) { /* NS-agnostic attribute */ }
    static String text(Element e) { /* trimmed text content */ }
}
```

- NS-agnostic by local name so we don't hard-code `tm:` / `adtcore:` prefixes.
- Lets `where_used`, `object_structure`, `package_contents`, `check_syntax`,
  `unit_tests`, `atc` each parse in ~10 lines and emit clean JSON.
- Lets `list_transports`' `parse=true` path drop its regex.

This is the single change that keeps every proposed tool at the repo's ~80-line
target instead of ballooning with bespoke parsing.

### 5.2 Escape-hatch-as-prototyper (process, no code)

The roadmap repeatedly says a tool "needs one Eclipse HTTP trace to confirm the
XML body shape." We already ship that trace tool: `arc1_sap_http_post`. Workflow:

1. Call `arc1_sap_http_post` with the candidate endpoint + a hand-written body.
2. Read the real request/response shape back.
3. Hard-code the confirmed shape into a first-class `Arc1Sap…Tool`.

No Wireshark, no Eclipse trace flags, no second process. Document this in
`CONTRIBUTING.md` as the standard way to land a new REST-backed tool.

### 5.3 Optional `IFile`-sync helper — **demote from blocker to maybe-never**

Plan 02 framed this ~60-line helper as the prerequisite for syntax check /
revisions / unit tests. Per Section 3, REST removes that dependency for read-only
use. Keep the helper on the board **only** for a future capability that truly
needs editor-buffer semantics; do not build it speculatively.

### 5.4 Minor cleanups

- `AdtHttp` Javadoc still says *"POST support comes in v0.3 when where-used … are
  wired up."* POST shipped in v0.3; reword.
- Consider an optional shared `Tools.ok(json)` / `Tools.err(name, t)` to remove
  the `error(...)` boilerplate duplicated in every tool. **Trade-off:** CLAUDE.md
  deliberately favors "each tool ~80 lines that look like every other tool" for
  predictability. Recommend **keeping the duplication** (it's load-bearing for the
  "read one file to understand a tool" rule) unless the tool count grows past
  ~20. Listed for completeness, not recommended now.

---

## 6. Proposed new tools (value × effort, with confidence)

Confidence = how sure we are of the exact endpoint/body **without** a live probe.
*Java-API* tools inherit the high confidence of the existing wrappers; *REST*
tools should be confirmed once with the escape hatch (5.2) before hard-coding.

| # | Tool | Path | Endpoint / API | Value | Effort | Confidence | Needs `IFile`? |
|---|---|---|---|---|---|---|---|
| 1 | `arc1_sap_where_used` | REST POST | `…/informationsystem/usageReferences?uri=` | ★★★ | M | Med (body via 5.2) | No |
| 2 | `arc1_sap_package_contents` | REST POST | `…/repository/nodestructure?parent_name=&parent_type=DEVC/K` | ★★★ | M | Med-High | No |
| 3 | `arc1_sap_object_structure` | REST GET | `…/oo/classes/{n}/objectstructure?version=active` | ★★★ | S-M | Med | No |
| 4 | `arc1_sap_find_occurrences` | **Java** | `IOccurrenceFinderServices.getOccurrenceInfoByOffset` | ★★ | S | **High** (Plan 02 T6) | No |
| 5 | `arc1_sap_list_inactive` | REST GET | `/sap/bc/adt/activation/inactiveobjects` | ★★ | S | Med-High | No |
| 6 | `arc1_sap_check_syntax` | REST POST | `/sap/bc/adt/checkruns?reporters=abapCheckRun` | ★★★ | M | Med (body via 5.2) | No (active ver.) |
| 7 | `arc1_sap_run_unit_tests` | REST POST | `/sap/bc/adt/abapunit/testruns` | ★★★ | M | Med (body via 5.2) | No |
| 8 | `arc1_sap_atc_check` | REST POST×2 | `…/atc/worklists` then `…/atc/runs` (empty variant = default) | ★★ | L | Med (multi-step) | No |
| 9 | `arc1_sap_object_versions` | REST GET | `…/{uri}/versions` | ★ | S | Low (confirm) | No |
| 10 | `arc1_sap_pretty_print` | REST POST | `/sap/bc/adt/abapsource/prettyprinter` | ★ | S | Low (confirm) | No |
| 11 | `arc1_sap_type_hierarchy` | REST GET | objectstructure / typehierarchy feed | ★ | M | Low | No |
| 12 | `arc1_sap_element_info` (hover) | REST POST | `/sap/bc/adt/abapsource/elementinfo` | ★ | M | Low (fussy, §4) | No |

★★★ = closes a named gap that AI clients hit constantly (impact analysis, code
navigation, "does my change compile / pass tests"). ★ = nice-to-have.

### Sketches for the top 4

**1. `arc1_sap_where_used`** — impact analysis ("what calls this?"). Input:
`{destination, objectUri}` (optionally a sub-object/`uri` fragment). POST to
`usageReferences`, parse the result rows with `Xml` into
`[{uri, name, type, parentUri, usageInformation}]`. Cap results. This is the most
requested missing tool and the headline item on the roadmap.

**2. `arc1_sap_package_contents`** — code exploration. Input:
`{destination, package, types?}`. POST `nodestructure` with
`parent_type=DEVC/K&parent_name={PKG}`. Returns the Project-Explorer tree feed:
`[{name, type, uri, expandable}]`. Lets an AI walk a codebase top-down instead of
only fuzzy-searching by name.

**3. `arc1_sap_object_structure`** — outline of one object (methods, attributes,
events, includes) with offsets, so an AI can target `find_definition` /
`find_occurrences` precisely. Input `{destination, objectUri}`. GET
`objectstructure`, parse to a nested `components` array.

**4. `arc1_sap_find_occurrences`** — **lowest-risk next step**: it's already fully
specced in `docs/plans/02-feature-parity-push.md` (T6), uses a confirmed public
Java factory (`com.sap.adt.tools.abapsource` — one new `Require-Bundle`), and
needs no REST body archaeology. "Where is this symbol used *inside this source*?"

---

## 7. Recommended sequencing

- **Phase A (infra):** add `Xml` helper (5.1); reword `AdtHttp` doc (5.4);
  document escape-hatch-as-prototyper in `CONTRIBUTING.md` (5.2).
- **Phase B (quick, high-confidence):** `find_occurrences` (Java, ready) +
  `list_inactive` (simple GET). Proves the `Xml` helper and the new bundle.
- **Phase C (headline reads):** `where_used`, `package_contents`,
  `object_structure` — confirm each endpoint once with the escape hatch, then
  promote.
- **Phase D (quality):** `check_syntax`, `run_unit_tests`, then `atc_check`.
- **Phase E (optional):** `object_versions`, `pretty_print`, `type_hierarchy`,
  `element_info` — only if asked.

Each tool still follows the CLAUDE.md checklist: new `Arc1Sap…Tool.java`, add to
`plugin.xml`, extend `smoke-test.sh`, `./build.sh`, `-clean` restart, smoke-test.
Phases B–D each add a `Require-Bundle` line only if a new bundle is touched
(only `find_occurrences` does: `com.sap.adt.tools.abapsource`).

---

## 8. Explicitly out of scope (non-goals reaffirmed)

Mirrors CLAUDE.md so this research can't be read as scope-creep:

- **Mutating tools** — `create/update/activate/delete`, `generate`,
  `publish_service_binding`, `assign_transport`, `run_application`. These are the
  bulk of what `adt-ls`/`arc-1-lsp` add *over* us; they belong in SAP's own MCP
  surface or the Eclipse editor.
- **Free-form SQL / data preview** (`datapreview/freestyle`) — no server-side
  safety gate makes sense in a single-user desktop plugin.
- **A second process / Node bridge / consuming the reference SDKs as deps** — see
  D10.
- **The `IFile`-sync helper** — not built unless a future feature needs
  editor-buffer semantics (Section 5.3).

So even though the references list ~39 / ~92 operations, the **read-only,
in-Eclipse, no-new-dependency** filter narrows the genuinely useful additions to
the ~8 in Phases B–D (plus 4 optional).

---

## 9. Open questions to confirm live (via the escape hatch)

1. `usageReferences` exact request body + `Accept`/`Content-Type` vendor types,
   and whether a sub-object fragment URI is required.
2. `nodestructure` parameter names (`parent_type` vs `parentType`) and the
   `Accept` vendor type on the running ADT version.
3. `objectstructure` vendor type (`…objectstructure.v2+xml`?) and whether
   non-OO objects (PROG/FUGR) return a usable outline or 404.
4. `checkruns` body (`<chkrun:checkObjectList>`) and whether referencing only the
   URI (active version) is enough, or a source blob is required.
5. `abapunit/testruns` `<aunit:runConfiguration>` body and whether the call blocks
   for results (expected) or needs polling.
6. `find_occurrences`: the instantiation path for `IOccurrenceFinderServices`
   (factory method vs OSGi service tracker) — the one open item from Plan 02 T6.

All six are answerable in minutes against a logged-in destination using
`arc1_sap_http_get` / `arc1_sap_http_post` — no new tooling, no second process.
