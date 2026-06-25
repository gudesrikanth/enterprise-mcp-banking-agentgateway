#!/usr/bin/env bash
# Functional smoke tests for the bank MCP platform through agentgateway.
# Usage: mcp_smoke.sh <gateway-mcp-url> <payments-admin-jwt>
#   e.g. mcp_smoke.sh http://localhost:8080/mcp "$TOKEN"
# Exits non-zero on the first failed assertion.
set -uo pipefail

URL="${1:?usage: mcp_smoke.sh <url> <token>}"
TOKEN="${2:?usage: mcp_smoke.sh <url> <token>}"
ACC='Accept: application/json, text/event-stream'
CT='Content-Type: application/json'
AUTH="Authorization: Bearer ${TOKEN}"
fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "PASS: $*"; }

mcp_body() { sed 's/^data: //' | tr -d '\r'; }   # strip SSE framing

# --- 1) no token -> 401 ----------------------------------------------------
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 -H "$ACC" -H "$CT" -X POST "$URL" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"ci","version":"1"}}}')
[ "$code" = "401" ] || fail "no-token expected 401, got $code"
pass "no token -> 401"

# --- handshake with a valid token -----------------------------------------
SID=$(curl -s --max-time 20 -D - -o /dev/null -H "$ACC" -H "$CT" -H "$AUTH" -X POST "$URL" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"ci","version":"1"}}}' \
  | awk 'tolower($1)=="mcp-session-id:"{print $2}' | tr -d '\r')
[ -n "$SID" ] || fail "no mcp-session-id returned for a valid token"
curl -s --max-time 10 -H "$ACC" -H "$CT" -H "$AUTH" -H "mcp-session-id: $SID" -X POST "$URL" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
pass "authenticated handshake"

call() { # method json -> body (SSE-stripped)
  curl -s --max-time 20 -H "$ACC" -H "$CT" -H "$AUTH" -H "mcp-session-id: $SID" -X POST "$URL" -d "$1" | mcp_body
}
http_call() { # json -> http code only
  curl -s -o /dev/null -w '%{http_code}' --max-time 20 -H "$ACC" -H "$CT" -H "$AUTH" -H "mcp-session-id: $SID" -X POST "$URL" -d "$1"
}

# --- 2) federated tools/list has the 6 bank tools --------------------------
tools=$(call '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | jq -r '.result.tools[].name' | sort | tr '\n' ' ')
for t in accounts_get_account accounts_get_balance accounts_list_accounts accounts_list_transactions payments_list_transfers payments_transfer_funds; do
  echo "$tools" | grep -qw "$t" || fail "tools/list missing $t (got: $tools)"
done
pass "tools/list has all 6 federated tools"

# --- 3) read tool works (teller-safe) -------------------------------------
acc=$(call '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"accounts_list_accounts","arguments":{}}}' \
  | jq -r '.result.content[0].text')
echo "$acc" | grep -q "ACC-1001" || fail "accounts_list_accounts did not return ACC-1001 (got: ${acc:0:120})"
pass "accounts_list_accounts returns accounts"

# --- 4) write tool succeeds for payments-admin ----------------------------
res=$(call '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"payments_transfer_funds","arguments":{"fromAccountId":"ACC-1001","toAccountId":"ACC-1002","amountCents":100}}}' \
  | jq -r '.result.content[0].text')
echo "$res" | grep -q '"status":"POSTED"' || fail "transfer_funds not POSTED (got: ${res:0:160})"
pass "payments_transfer_funds POSTED"

# --- 5) rate limit: transfer_funds capped -> a 429 under rapid fire ---------
# The policy is a LOCAL token bucket of 3/min, enforced PER proxy replica. With
# replicas=2 behind the Service, up to 2x3=6 calls can succeed before any pod
# starts returning 429, so we must fire >6 to deterministically trip it
# regardless of how the Service spreads the calls. (For a true cluster-wide cap
# independent of replica count, use a global rate limit - see docs/07.)
got429=no
for i in $(seq 1 8); do
  c=$(http_call '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"payments_transfer_funds","arguments":{"fromAccountId":"ACC-1001","toAccountId":"ACC-1002","amountCents":1}}}')
  [ "$c" = "429" ] && { got429=yes; break; }
done
[ "$got429" = "yes" ] || fail "rate limit: expected a 429 within 8 rapid transfers"
pass "rate limit on transfer_funds (429)"

echo "ALL FUNCTIONAL TESTS PASSED"
