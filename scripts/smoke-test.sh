#!/usr/bin/env bash
#
# Smoke-test all arc1_sap_* tools end-to-end against the running
# Eclipse MCP server. Run after Eclipse has restarted and the
# auto-login has completed.
#
# Usage:  ./scripts/smoke-test.sh [destination-id]
#         (destination-id defaults to A4H_001_marian_en_1)
#
set -euo pipefail

DEST=${1:-A4H_001_marian_en_1}
TOKEN_FILE=$HOME/.config/arc1/mcp-token.txt
if [[ ! -f "$TOKEN_FILE" ]]; then
    echo "ERROR: $TOKEN_FILE not found. Has Eclipse started?" >&2
    exit 1
fi
set -a; source <(grep -E '^(PORT|TOKEN|URL)=' "$TOKEN_FILE"); set +a

if [[ -z "${TOKEN:-}" ]]; then
    echo "ERROR: TOKEN missing from $TOKEN_FILE. Is SAP_CONTROLLED=true? Check Eclipse's MCP preferences for the actual token."
    exit 1
fi

HEAD=/tmp/arc1-smoke-headers.txt
init_session() {
    curl -s -D "$HEAD" -o /dev/null -X POST "$URL" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -H "Accept: application/json, text/event-stream" \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}'
    SESSION=$(grep -i '^mcp-session-id:' "$HEAD" | awk '{print $2}' | tr -d '\r')
    if [[ -z "$SESSION" ]]; then
        echo "FAIL: no Mcp-Session-Id returned from initialize"
        exit 1
    fi
    curl -s -X POST "$URL" \
        -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
        -H "Accept: application/json, text/event-stream" -H "Mcp-Session-Id: $SESSION" \
        -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' > /dev/null
}

call_tool() {
    local id=$1 name=$2 args=$3
    local raw
    raw=$(curl -s -X POST "$URL" \
        -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
        -H "Accept: application/json, text/event-stream" -H "Mcp-Session-Id: $SESSION" \
        -d "{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"tools/call\",\"params\":{\"name\":\"$name\",\"arguments\":$args}}")
    python3 -c "
import json, re, sys
raw = '''$raw'''
m = re.search(r'data:\s*(\{.*\})', raw, re.DOTALL)
d = json.loads(m.group(1) if m else raw)
r = d.get('result', {})
content = r.get('content', [{}])[0].get('text', '')
print('  isError:', r.get('isError', False))
print('  raw   :', content[:300])
"
}

init_session
echo "Connected. Session: $SESSION"
echo ""

echo "================================================================"
echo "TEST 0: tools/list"
echo "================================================================"
curl -s -X POST "$URL" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" -H "Mcp-Session-Id: $SESSION" \
    -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
    | python3 -c '
import json, re, sys
raw = sys.stdin.read()
m = re.search(r"data:\s*(\{.*\})", raw, re.DOTALL)
d = json.loads(m.group(1) if m else raw)
arc1 = [t["name"] for t in d["result"]["tools"] if t["name"].startswith("arc1_")]
sap  = [t["name"] for t in d["result"]["tools"] if not t["name"].startswith("arc1_")]
print(f"  arc1_*: {len(arc1)}  -> {arc1}")
print(f"  SAP  : {len(sap)}  -> {sap}")
assert len(arc1) >= 5, f"Expected >=5 arc1_ tools, got {len(arc1)}"
print("  PASS")
'
echo ""

echo "================================================================"
echo "TEST 1: arc1_sap_list_projects"
echo "================================================================"
call_tool 3 arc1_sap_list_projects '{}'
echo ""

echo "================================================================"
echo "TEST 2: arc1_sap_search (existing) — ZARC1*"
echo "================================================================"
call_tool 4 arc1_sap_search "{\"destination\":\"$DEST\",\"query\":\"ZARC1*\",\"maxResults\":5}"
echo ""

echo "================================================================"
echo "TEST 3: arc1_sap_repository_search — CL_ABAP*, type CLAS, max 3"
echo "================================================================"
call_tool 5 arc1_sap_repository_search "{\"destination\":\"$DEST\",\"query\":\"CL_ABAP\",\"objectTypes\":[\"CLAS\"],\"maxResults\":3,\"useTrailingWildcard\":true}"
echo ""

echo "================================================================"
echo "TEST 4: arc1_sap_object_info — CL_ABAP_TYPEDESCR"
echo "================================================================"
call_tool 6 arc1_sap_object_info "{\"destination\":\"$DEST\",\"objectUri\":\"/sap/bc/adt/oo/classes/CL_ABAP_TYPEDESCR\"}"
echo ""

echo "================================================================"
echo "TEST 5: arc1_sap_find_definition — identifier in CL_ABAP_TYPEDESCR source"
echo "================================================================"
call_tool 7 arc1_sap_find_definition "{\"destination\":\"$DEST\",\"sourceUri\":\"/sap/bc/adt/oo/classes/CL_ABAP_TYPEDESCR/source/main\",\"identifier\":\"CL_ABAP_DATADESCR\"}"
echo ""

echo "================================================================"
echo "TEST 6: arc1_sap_system_info — softwareComponents"
echo "================================================================"
call_tool 8 arc1_sap_system_info "{\"destination\":\"$DEST\",\"include\":[\"softwareComponents\"]}"
echo ""

echo "================================================================"
echo "TEST 7: arc1_sap_object_types — filter 'CLAS'"
echo "================================================================"
call_tool 9 arc1_sap_object_types "{\"destination\":\"$DEST\",\"typeFilter\":\"CLAS\"}"
echo ""

echo "================================================================"
echo "TEST 8 (v0.2): arc1_sap_http_get /sap/bc/adt/discovery"
echo "================================================================"
call_tool 10 arc1_sap_http_get "{\"destination\":\"$DEST\",\"uri\":\"/sap/bc/adt/discovery\",\"accept\":\"application/atomsvc+xml\"}"
echo ""

echo "================================================================"
echo "TEST 9 (v0.2): arc1_sap_read_source CL_ABAP_TYPEDESCR"
echo "================================================================"
call_tool 11 arc1_sap_read_source "{\"destination\":\"$DEST\",\"objectUri\":\"/sap/bc/adt/oo/classes/CL_ABAP_TYPEDESCR\"}"
echo ""

echo "================================================================"
echo "TEST 10 (v0.3): arc1_sap_http_post — repository/nodestructure"
echo "================================================================"
BODY='<asx:abap xmlns:asx="http://www.sap.com/abapxml" version="1.0"><asx:values><DATA><TV_NODEKEY>000000</TV_NODEKEY></DATA></asx:values></asx:abap>'
ESCAPED_BODY=$(printf '%s' "$BODY" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))')
call_tool 12 arc1_sap_http_post "{\"destination\":\"$DEST\",\"uri\":\"/sap/bc/adt/repository/nodestructure\",\"accept\":\"application/vnd.sap.as+xml; charset=UTF-8; dataname=com.sap.adt.RepositoryObjectTreeContent\",\"contentType\":\"application/vnd.sap.as+xml; charset=UTF-8; dataname=com.sap.adt.RepositoryObjectTreeContent\",\"body\":${ESCAPED_BODY}}"
echo ""

echo "================================================================"
echo "TEST 11 (v0.3): arc1_sap_list_transports — Modifiable"
echo "================================================================"
call_tool 13 arc1_sap_list_transports "{\"destination\":\"$DEST\",\"status\":\"Modifiable\",\"parse\":true}"
echo ""

echo "Smoke tests complete."
rm -f "$HEAD"
