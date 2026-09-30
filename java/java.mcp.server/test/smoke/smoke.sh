#!/usr/bin/env bash

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# End-to-end smoke test of the MCP server: generates a small Maven project,
# starts a headless NetBeans on it, sends MCP requests over stdio and prints
# the responses. Finally appends a usage to a file on disk and checks that
# find_usages picks it up.
#
# The server is started through the nb-mcp launcher of the installation, so
# the launcher is tested too.
#
# Usage: smoke.sh [netbeans-launcher]
#   defaults to <repo>/nbbuild/netbeans/bin/netbeans
# Environment:
#   SMOKE_DIR     work directory (default ${TMPDIR:-/tmp}/nbmcp-smoke); the
#                 project is regenerated on each run, userdir and cache are kept
#   JDK_HOME      JDK for NetBeans (default: what nb-mcp finds)
#   READY_TIMEOUT seconds to wait for the workspace to be ready (default 600)

set -u

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../../.." && pwd)
NB=${1:-$REPO/nbbuild/netbeans/bin/netbeans}
SMOKE_DIR=${SMOKE_DIR:-${TMPDIR:-/tmp}/nbmcp-smoke}
READY_TIMEOUT=${READY_TIMEOUT:-600}
NB_MCP=$(cd "$(dirname "$NB")/.." && pwd)/java/bin/nb-mcp
PROJECT=$SMOKE_DIR/project
PKG=$PROJECT/src/main/java/com/acme/shop
FAILURES=0

fail() {
    echo "FAIL: $*" >&2
    FAILURES=$((FAILURES + 1))
}

# --- 1. the test project -----------------------------------------------------

rm -rf "$PROJECT"
mkdir -p "$PKG" "$PROJECT/src/test/java/com/acme/shop"

cat > "$PROJECT/pom.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.acme</groupId>
    <artifactId>shop</artifactId>
    <version>1.0-SNAPSHOT</version>
    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter-api</artifactId>
            <version>5.10.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
EOF

cat > "$PKG/PaymentService.java" <<'EOF'
package com.acme.shop;

public interface PaymentService {
    boolean pay(Order order);
}
EOF

cat > "$PKG/CardPayment.java" <<'EOF'
package com.acme.shop;

public class CardPayment implements PaymentService {
    @Override
    public boolean pay(Order order) {
        return order.total() < 1000;
    }
}
EOF

cat > "$PKG/InvoicePayment.java" <<'EOF'
package com.acme.shop;

public class InvoicePayment implements PaymentService {
    @Override
    public boolean pay(Order order) {
        return true;
    }
}
EOF

cat > "$PKG/Order.java" <<'EOF'
package com.acme.shop;

import java.util.ArrayList;
import java.util.List;

public class Order {

    public static class Line {
        final String sku;
        final int quantity;

        Line(String sku, int quantity) {
            this.sku = sku;
            this.quantity = quantity;
        }
    }

    private final List<Line> lines = new ArrayList<>();

    public Order addLine(String sku) {
        return addLine(sku, 1);
    }

    public Order addLine(String sku, int quantity) {
        return addLine(new Line(sku, quantity));
    }

    public Order addLine(Line line) {
        lines.add(line);
        return this;
    }

    public int total() {
        return Pricing.withTax(lines.stream().mapToInt(l -> l.quantity * 10).sum());
    }
}
EOF

cat > "$PKG/Validation.java" <<'EOF'
package com.acme.shop;

class Validation {
    static boolean blank(String s) {
        return s == null || s.length() == 0;
    }

    static boolean blankTrimmed(String s) {
        return s == null || s.trim().length() == 0;
    }

    static boolean noLines(java.util.List<String> lines) {
        return lines.size() == 0;
    }
}
EOF

cat > "$PKG/Pricing.java" <<'EOF'
package com.acme.shop;

public final class Pricing {
    public static final int TAX_PERCENT = 20;

    private Pricing() {
    }

    public static int withTax(int net) {
        return net + net * TAX_PERCENT / 100;
    }

    static int legacyRound(int value) {
        return value / 10 * 10;
    }
}
EOF

cat > "$PKG/Checkout.java" <<'EOF'
package com.acme.shop;

public class Checkout {
    private final PaymentService payment;

    public Checkout(PaymentService payment) {
        this.payment = payment;
    }

    public boolean buy(String sku, int quantity) {
        Order order = new Order().addLine(sku, quantity);
        return payment.pay(order);
    }
}
EOF

cat > "$PROJECT/src/test/java/com/acme/shop/CheckoutTest.java" <<'EOF'
package com.acme.shop;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CheckoutTest {
    @Test
    void cardPaymentAcceptsSmallOrders() {
        assertTrue(new Checkout(new CardPayment()).buy("apple", 3));
        assertTrue(new CardPayment().pay(new Order().addLine("pear")));
    }
}
EOF

# --- 2. start the server ---------------------------------------------------

[ -x "$NB_MCP" ] || { echo "FAIL: no executable $NB_MCP" >&2; exit 1; }
STDERR_LOG=$SMOKE_DIR/stderr.log
NB_MCP_ARGS=(--netbeans "$NB" --data "$SMOKE_DIR/data")
[ -n "${JDK_HOME:-}" ] && NB_MCP_ARGS+=(--jdk "$JDK_HOME")
echo "Project:  $PROJECT"
echo "Launcher: $NB_MCP ${NB_MCP_ARGS[*]}"
"$NB_MCP" "${NB_MCP_ARGS[@]}" --check "$PROJECT"
echo "Logs:     $STDERR_LOG, <data>/userdir/var/log/messages.log"

# FIFOs instead of coproc: macOS ships bash 3.2
TO_NB=$SMOKE_DIR/to-nb.fifo
FROM_NB=$SMOKE_DIR/from-nb.fifo
rm -f "$TO_NB" "$FROM_NB"
mkfifo "$TO_NB" "$FROM_NB"
"$NB_MCP" "${NB_MCP_ARGS[@]}" "$PROJECT" < "$TO_NB" > "$FROM_NB" 2> "$STDERR_LOG" &
NB_PID=$!
# fd 3 writes to the server's stdin, fd 4 reads its stdout
exec 3> "$TO_NB"
exec 4< "$FROM_NB"

cleanup() {
    # closing stdin must make the server shut NetBeans down by itself
    exec 3>&-
    for _ in $(seq 1 30); do
        kill -0 "$NB_PID" 2>/dev/null || return
        sleep 1
    done
    fail "NetBeans did not exit within 30s after stdin was closed, killing it"
    kill "$NB_PID" 2>/dev/null
}

NEXT_ID=0
RESPONSE=

# request <method> [params-json] -- sends a request and waits for its response
request() {
    NEXT_ID=$((NEXT_ID + 1))
    local params='{}'
    [ -n "${2:-}" ] && params=$2
    local msg="{\"jsonrpc\":\"2.0\",\"id\":$NEXT_ID,\"method\":\"$1\",\"params\":$params}"
    echo ">>> $msg"
    printf '%s\n' "$msg" >&3
    RESPONSE=
    local line
    while IFS= read -r -t "${3:-300}" line <&4; do
        case "$line" in
            \{*\"id\":$NEXT_ID,*)
                RESPONSE=$line
                echo "<<< $line"
                return 0 ;;
            \{*)
                echo "<<< (unexpected) $line" ;;
            *)
                fail "stdout is not protocol only: $line" ;;
        esac
    done
    fail "no response to request $NEXT_ID ($1)"
    return 1
}

notify() {
    local msg="{\"jsonrpc\":\"2.0\",\"method\":\"$1\"}"
    echo ">>> $msg"
    printf '%s\n' "$msg" >&3
}

# tool <name> <arguments-json> -- calls a tool; RESPONSE holds the reply
tool() {
    request tools/call "{\"name\":\"$1\",\"arguments\":$2}"
}

# undo the JSON escaping of '/' (json_simple writes '\/') and '"' so text can be matched literally
unescaped() {
    printf '%s' "$RESPONSE" | sed -e 's#\\/#/#g' -e 's#\\"#"#g'
}

expect() {
    case "$(unescaped)" in
        *"$1"*) ;;
        *) fail "expected '$1' in the last response" ;;
    esac
}

expect_not() {
    case "$(unescaped)" in
        *"$1"*) fail "did not expect '$1' in the last response" ;;
    esac
}

# --- 3. talk MCP -------------------------------------------------------------

request initialize '{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}' 600 \
    || { cleanup; exit 1; }
expect '"protocolVersion":"2025-06-18"'
notify notifications/initialized

request tools/list
expect '"name":"find_symbol"'
expect '"name":"find_usages"'
expect '"name":"workspace_status"'
expect '"name":"find_implementations"'
expect '"name":"outline"'
expect '"name":"diagnostics"'
expect '"name":"rename"'
expect '"name":"move"'
expect '"name":"change_signature"'
expect '"name":"safe_delete"'
expect '"name":"inline"'
expect '"name":"apply_rule"'

echo "--- waiting for the workspace to be ready (up to ${READY_TIMEOUT}s)"
START=$(date +%s)
while :; do
    tool workspace_status '{}'
    case "$RESPONSE" in
        *"State: ready"*) break ;;
        *"State: failed"*) fail "workspace failed to open"; break ;;
    esac
    if [ $(( $(date +%s) - START )) -ge "$READY_TIMEOUT" ]; then
        fail "workspace not ready after ${READY_TIMEOUT}s"
        break
    fi
    sleep 5
done
echo "--- ready after $(( $(date +%s) - START ))s"
expect 'src/main/java'
expect 'src/test/java'

tool find_symbol '{"query":"*Payment*"}'
expect 'com.acme.shop.PaymentService'
expect 'com.acme.shop.CardPayment'
expect 'com.acme.shop.InvoicePayment'

tool find_symbol '{"query":"Line"}'
expect 'com.acme.shop.Order.Line'

tool find_symbol '{"query":"Order#add"}'
expect 'com.acme.shop.Order#addLine(java.lang.String)  method  src/main/java/com/acme/shop/Order.java:20'
expect 'com.acme.shop.Order#addLine(java.lang.String,int)'
expect 'com.acme.shop.Order#addLine(com.acme.shop.Order.Line)'

tool find_symbol '{"query":"*Payment*#pay"}'
expect 'com.acme.shop.CardPayment#pay(com.acme.shop.Order)'
expect 'com.acme.shop.InvoicePayment#pay(com.acme.shop.Order)'
expect 'com.acme.shop.PaymentService#pay(com.acme.shop.Order)'

tool find_symbol '{"query":"Checkout#<init>"}'
expect 'com.acme.shop.Checkout#<init>(com.acme.shop.PaymentService)  constructor'
expect 'com.acme.shop.CheckoutTest#<init>()  constructor  src/test/java/com/acme/shop/CheckoutTest.java  (implicit)'

tool find_symbol '{"query":"Order#nope"}'
expect 'No members matching'

# overloads must be told apart: only the (String,int) calls
tool find_usages '{"symbol":"com.acme.shop.Order#addLine(String,int)"}'
expect '"isError":false'
expect 'Checkout.java'
expect 'addLine(sku, quantity)'
expect_not 'addLine("pear")'

tool find_usages '{"symbol":"com.acme.shop.Order#addLine"}'
expect '"isError":true'
expect 'ambiguous'

tool find_usages '{"symbol":"com.acme.shop.Order.Line"}'
expect 'Order.java'

tool find_usages '{"symbol":"com.acme.shop.PaymentService#pay(Order)","include_overriding":true}'
expect 'Checkout.java'
expect 'CheckoutTest.java'

tool find_usages '{"symbol":"com.acme.shop.CardPayment#<init>()"}'
expect 'CheckoutTest.java'

tool find_usages '{"symbol":"com.acme.shop.Nope"}'
expect '"isError":true'

tool find_implementations '{"symbol":"com.acme.shop.PaymentService"}'
expect '"isError":false'
expect 'CardPayment.java'
expect 'InvoicePayment.java'

tool find_implementations '{"symbol":"com.acme.shop.PaymentService#pay"}'
expect '2 implementations'
expect 'CardPayment.java'
expect 'InvoicePayment.java'

tool find_implementations '{"symbol":"com.acme.shop.Order#lines"}'
expect '"isError":true'

tool outline '{"symbol":"com.acme.shop.Order"}'
expect '"isError":false'
expect 'class com.acme.shop.Order'
expect 'public method com.acme.shop.Order#addLine(java.lang.String) : Order'
expect 'public static class com.acme.shop.Order.Line'
expect 'private final field com.acme.shop.Order#lines : List<Order.Line>'
expect 'public constructor com.acme.shop.Order#<init>()  (implicit)'

tool outline '{"file":"src/main/java/com/acme/shop/CardPayment.java"}'
expect 'class com.acme.shop.CardPayment implements PaymentService'
expect 'public method com.acme.shop.CardPayment#pay(com.acme.shop.Order) : boolean'

tool outline '{"symbol":"com.acme.shop.Order#total"}'
expect '"isError":true'

tool diagnostics '{}'
expect 'No compile errors in the workspace'

tool diagnostics '{"files":["src/main/java/com/acme/shop/Checkout.java","src/main/java/Missing.java"],"include_warnings":true}'
expect '"isError":false'
expect 'not found: src/main/java/Missing.java'

# --- 4. edits on disk show up in the next call --------------------------------

echo "--- appending a usage to Checkout.java"
echo 'class ExtraUsage { Order o = new Order().addLine("added-by-smoke-test", 7); }' >> "$PKG/Checkout.java"
tool find_usages '{"symbol":"com.acme.shop.Order#addLine(String,int)"}'
expect 'added-by-smoke-test'

echo "--- appending a compile error to Checkout.java"
echo 'class Broken { void x() { new Order().addLin("typo"); } }' >> "$PKG/Checkout.java"
tool diagnostics '{}'
expect '1 error in 1 file'
expect 'src/main/java/com/acme/shop/Checkout.java'
expect '16:39 error: cannot find symbol'
expect 'symbol:   method addLin(String)'

tool diagnostics '{"files":["src/test/java/com/acme/shop/CheckoutTest.java"]}'
expect 'No errors in 1 file'

# --- 5. rename ---------------------------------------------------------------

echo "--- removing the compile error again"
sed -i.bak '$d' "$PKG/Checkout.java" && rm -f "$PKG/Checkout.java.bak"
tool diagnostics '{}'
expect 'No compile errors in the workspace'

tool rename '{"symbol":"com.acme.shop.Order#addLine(String,int)","new_name":"1bad"}'
expect '"isError":true'
expect 'not a valid Java identifier'

tool rename '{"symbol":"com.acme.shop.CardPayment","new_name":"InvoicePayment"}'
expect '"isError":true'
expect 'Nothing was changed'
[ -f "$PKG/CardPayment.java" ] || fail "a failed rename must not change files"

# only the (String,int) overload, including the usage added on disk in step 4
tool rename '{"symbol":"com.acme.shop.Order#addLine(String,int)","new_name":"addItem"}'
expect '"isError":false'
expect 'Done: rename com.acme.shop.Order#addLine(java.lang.String,int) to addItem. 2 files changed'
expect '+        Order order = new Order().addItem(sku, quantity);'
expect '+class ExtraUsage { Order o = new Order().addItem("added-by-smoke-test", 7); }'
expect '+        return addItem(sku, 1);'
grep -q 'addLine("pear")' "$PROJECT/src/test/java/com/acme/shop/CheckoutTest.java" \
    || fail "the addLine(String) overload must not be renamed"

# the returned diff is a valid patch: applying it in reverse restores the old content
if command -v python3 > /dev/null; then
    printf '%s' "$RESPONSE" | python3 -c 'import json,sys; t=json.load(sys.stdin)["result"]["content"][0]["text"]; print(t[t.index("diff --git"):], end="")' \
        > "$SMOKE_DIR/rename.patch"
    (cd "$PROJECT" && git apply -R --check "$SMOKE_DIR/rename.patch") \
        || fail "the rename diff does not apply in reverse"
fi

# a public class: its file is renamed too
tool rename '{"symbol":"com.acme.shop.CardPayment","new_name":"CreditCardPayment"}'
expect '"isError":false'
expect 'rename from src/main/java/com/acme/shop/CardPayment.java'
expect 'rename to src/main/java/com/acme/shop/CreditCardPayment.java'
expect '+public class CreditCardPayment implements PaymentService {'
expect 'src/test/java/com/acme/shop/CheckoutTest.java'
[ -f "$PKG/CreditCardPayment.java" ] && [ ! -f "$PKG/CardPayment.java" ] \
    || fail "CardPayment.java should have been renamed to CreditCardPayment.java"

# an interface method: the implementations follow
tool rename '{"symbol":"com.acme.shop.PaymentService#pay","new_name":"charge"}'
expect '"isError":false'
expect '+    boolean charge(Order order);'
expect 'src/main/java/com/acme/shop/CreditCardPayment.java'
expect 'src/main/java/com/acme/shop/InvoicePayment.java'
expect '+        return payment.charge(order);'

tool find_usages '{"symbol":"com.acme.shop.Order#addItem(String,int)"}'
expect '3 usages'

tool diagnostics '{}'
expect 'No compile errors in the workspace'

# an edit on disk after a refactoring must still be picked up
echo 'class AfterRename { boolean b = new InvoicePayment().charge(new Order()); }' >> "$PKG/Checkout.java"
tool find_usages '{"symbol":"com.acme.shop.PaymentService#charge","include_overriding":true}'
expect 'AfterRename'

# --- 6. more refactorings -----------------------------------------------------

tool safe_delete '{"symbol":"com.acme.shop.Pricing#withTax(int)"}'
expect '"isError":true'
expect 'still used'
expect 'src/main/java/com/acme/shop/Order.java'
expect 'Nothing was changed'

tool safe_delete '{"symbol":"com.acme.shop.Pricing#legacyRound(int)"}'
expect '"isError":false'
expect '-    static int legacyRound(int value) {'

tool inline '{"symbol":"com.acme.shop.Pricing#TAX_PERCENT"}'
expect '"isError":false'
expect '+        return net + net * 20 / 100;'
expect '-    public static final int TAX_PERCENT = 20;'

tool inline '{"symbol":"com.acme.shop.Pricing#withTax(int)"}'
expect '"isError":false'
expect 'src/main/java/com/acme/shop/Order.java'
expect '-    public static int withTax(int net) {'

# only a private constructor is left: the class is unused and its file goes away
tool safe_delete '{"symbol":"com.acme.shop.Pricing"}'
expect '"isError":false'
expect 'deleted file mode 100644'
[ ! -f "$PKG/Pricing.java" ] || fail "Pricing.java should have been deleted"

tool change_signature '{"symbol":"com.acme.shop.Checkout#buy(String,int)","parameters":[{"from":"sku"},{"name":"express","type":"boolean"}]}'
expect '"isError":true'
expect 'needs'

tool change_signature '{"symbol":"com.acme.shop.Checkout#buy(String,int)","parameters":[{"from":"sku"},{"from":"quantity","name":"count"},{"name":"express","type":"boolean","default":"false"}]}'
expect '"isError":false'
expect '+    public boolean buy(String sku, int count, boolean express) {'
expect '+        assertTrue(new Checkout(new CreditCardPayment()).buy("apple", 3, false));'

tool move '{"symbol":"com.acme.shop.Order.Line","target_package":"com.acme.billing"}'
expect '"isError":true'

tool move '{"symbol":"com.acme.shop.InvoicePayment","target_package":"com.acme.billing"}'
expect '"isError":false'
expect 'rename from src/main/java/com/acme/shop/InvoicePayment.java'
expect 'rename to src/main/java/com/acme/billing/InvoicePayment.java'
expect '+package com.acme.billing;'
expect '+import com.acme.shop.Order;'
expect '+import com.acme.billing.InvoicePayment;'
[ -f "$PROJECT/src/main/java/com/acme/billing/InvoicePayment.java" ] || fail "InvoicePayment.java should be in com/acme/billing"

tool find_symbol '{"query":"InvoicePayment"}'
expect 'com.acme.billing.InvoicePayment'

tool diagnostics '{}'
expect 'No compile errors in the workspace'

# --- 7. structural search and replace -------------------------------------------

tool apply_rule '{"rule":"$s.length() == 0 :: $s instanceof java.lang.String"}'
expect '"isError":false'
expect '2 matching lines in 1 file'
expect '5: return s == null || s.length() == 0;'
expect '9: return s == null || s.trim().length() == 0;'
expect_not 'lines.size()'

RULE='$s.length() == 0 :: $s instanceof java.lang.String => $s.isEmpty() ;;'
tool apply_rule "{\"rule\":\"$RULE\",\"dry_run\":true}"
expect 'Dry run, nothing written: 1 file would change'
expect '+        return s == null || s.isEmpty();'
expect '+        return s == null || s.trim().isEmpty();'
grep -q 'isEmpty' "$PKG/Validation.java" && fail "a dry run must not write"

tool apply_rule "{\"rule\":\"$RULE\",\"paths\":[\"src/test/java\"]}"
expect 'No matches, nothing to change'
grep -q 'isEmpty' "$PKG/Validation.java" && fail "changes outside 'paths' must not be written"

tool apply_rule "{\"rule\":\"$RULE\",\"paths\":[\"src/main/java\"]}"
expect 'Applied: 1 file changed and saved'
[ "$(grep -c 'isEmpty()' "$PKG/Validation.java")" = 2 ] || fail "the rewrite was not written to disk"

tool apply_rule '{"rule":"$s.length() == 0 :: $s instanceof java.lang.String"}'
expect 'No matches'

tool apply_rule '{"rule":"$a.foo((( => bar ;;"}'
expect '"isError":true'
expect 'does not parse as Java'

tool diagnostics '{}'
expect 'No compile errors in the workspace'

cleanup
if [ "$FAILURES" -eq 0 ]; then
    echo "SMOKE TEST PASSED"
else
    echo "SMOKE TEST FAILED: $FAILURES problem(s)"
    exit 1
fi
