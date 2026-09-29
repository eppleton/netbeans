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
# Usage: smoke.sh [netbeans-launcher]
#   defaults to <repo>/nbbuild/netbeans/bin/netbeans
# Environment:
#   SMOKE_DIR     work directory (default ${TMPDIR:-/tmp}/nbmcp-smoke); the
#                 project is regenerated on each run, userdir and cache are kept
#   JDK_HOME      JDK for NetBeans (default: the one of 'java' on PATH)
#   READY_TIMEOUT seconds to wait for the workspace to be ready (default 600)

set -u

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../../.." && pwd)
NB=${1:-$REPO/nbbuild/netbeans/bin/netbeans}
SMOKE_DIR=${SMOKE_DIR:-${TMPDIR:-/tmp}/nbmcp-smoke}
READY_TIMEOUT=${READY_TIMEOUT:-600}
if [ -z "${JDK_HOME:-}" ]; then
    JDK_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")
fi
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
        return lines.stream().mapToInt(l -> l.quantity * 10).sum();
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

mkdir -p "$SMOKE_DIR/userdir/var"
# a fresh userdir otherwise offers to import settings of an installed NetBeans,
# and that dialog fails headless: NetBeans exits before the server starts
touch "$SMOKE_DIR/userdir/var/imported"
STDERR_LOG=$SMOKE_DIR/stderr.log
echo "Project:  $PROJECT"
echo "Launcher: $NB"
echo "JDK:      $JDK_HOME"
echo "Logs:     $STDERR_LOG, $SMOKE_DIR/userdir/var/log/messages.log"

# FIFOs instead of coproc: macOS ships bash 3.2
TO_NB=$SMOKE_DIR/to-nb.fifo
FROM_NB=$SMOKE_DIR/from-nb.fifo
rm -f "$TO_NB" "$FROM_NB"
mkfifo "$TO_NB" "$FROM_NB"
"$NB" --nogui --nosplash -J-Djava.awt.headless=true \
    -J-Duser.language=en -J-Duser.country=US \
    --jdkhome "$JDK_HOME" \
    --userdir "$SMOKE_DIR/userdir" --cachedir "$SMOKE_DIR/cache" \
    --start-mcp-server --mcp-workspace "$PROJECT" \
    < "$TO_NB" > "$FROM_NB" 2> "$STDERR_LOG" &
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

# json_simple escapes '/' as '\/'; undo that so paths can be matched literally
unescaped() {
    printf '%s' "$RESPONSE" | sed 's#\\/#/#g'
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
expect_not 'addLine(\"pear\")'

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

cleanup
if [ "$FAILURES" -eq 0 ]; then
    echo "SMOKE TEST PASSED"
else
    echo "SMOKE TEST FAILED: $FAILURES problem(s)"
    exit 1
fi
