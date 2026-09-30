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

# Generates the evaluation project: a two-module Maven build without external
# dependencies, full of names that text search confuses (overloads, same-named
# methods of unrelated classes, names in comments and strings, String vs.
# StringBuilder). ./compile.sh compiles everything with plain javac.
#
# Usage: fixture.sh <directory>   (the directory is replaced)

set -eu
[ $# -eq 1 ] || { echo "usage: fixture.sh <directory>" >&2; exit 2; }
P=$1
rm -rf "$P"
CORE=$P/core/src/main/java/com/acme/core
CORE_TEST=$P/core/src/test/java/com/acme/core
APP=$P/app/src/main/java/com/acme/app
mkdir -p "$CORE" "$CORE_TEST" "$APP"

cat > "$P/pom.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.acme</groupId>
    <artifactId>shop-parent</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <modules>
        <module>core</module>
        <module>app</module>
    </modules>
    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
</project>
EOF

cat > "$P/core/pom.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.acme</groupId>
        <artifactId>shop-parent</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>
    <artifactId>core</artifactId>
</project>
EOF

cat > "$P/app/pom.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.acme</groupId>
        <artifactId>shop-parent</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>
    <artifactId>app</artifactId>
    <dependencies>
        <dependency>
            <groupId>com.acme</groupId>
            <artifactId>core</artifactId>
            <version>1.0-SNAPSHOT</version>
        </dependency>
    </dependencies>
</project>
EOF

cat > "$P/compile.sh" <<'EOF'
#!/bin/sh
# compiles all modules (main and test) with plain javac; exit code 0 = compiles
out=$(mktemp -d)
javac -d "$out" $(find "$(dirname "$0")" -name '*.java' -path '*/src/*')
status=$?
rm -rf "$out"
exit $status
EOF
chmod +x "$P/compile.sh"

cat > "$CORE/Order.java" <<'EOF'
package com.acme.core;

import java.util.ArrayList;
import java.util.List;

public class Order {

    public static class Line {
        final String sku;
        final int quantity;

        public Line(String sku, int quantity) {
            this.sku = sku;
            this.quantity = quantity;
        }
    }

    private final List<Line> lines = new ArrayList<>();
    private String note = "";

    public Order addLine(String sku) {
        // same as addLine(sku, 1)
        return addLine(sku, 1);
    }

    public Order addLine(String sku, int quantity) {
        return addLine(new Line(sku, quantity));
    }

    public Order addLine(Line line) {
        lines.add(line);
        return this;
    }

    public List<Line> lines() {
        return lines;
    }

    public int total() {
        int sum = 0;
        for (Line l : lines) {
            sum += l.quantity * Catalog.price(l.sku);
        }
        return sum;
    }

    /**
     * @deprecated use {@link #total()}, it returns the same value
     */
    @Deprecated
    public int legacyTotal() {
        return total();
    }

    public void setNote(String note) {
        this.note = note;
    }

    public boolean hasNote() {
        return note.length() == 0 ? false : true;
    }
}
EOF

cat > "$CORE/Catalog.java" <<'EOF'
package com.acme.core;

import java.util.Map;

public final class Catalog {

    private static final Map<String, Integer> PRICES = Map.of("apple", 3, "pear", 4, "melon", 12);

    private Catalog() {
    }

    public static int price(String sku) {
        if (sku == null || sku.length() == 0) {
            throw new IllegalArgumentException("no sku");
        }
        return PRICES.getOrDefault(sku, 10);
    }

    public static boolean known(String sku) {
        return PRICES.containsKey(sku);
    }
}
EOF

cat > "$CORE/Cart.java" <<'EOF'
package com.acme.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** A shopping cart; unrelated to Order although it also has addLine(String, int). */
public class Cart {

    private final Map<String, Integer> items = new LinkedHashMap<>();

    public Cart addLine(String sku, int quantity) {
        items.merge(sku, quantity, Integer::sum);
        return this;
    }

    public Order toOrder() {
        Order order = new Order();
        items.forEach((sku, quantity) -> order.addLine(sku, quantity));
        return order;
    }

    public boolean isEmptyCart() {
        return items.size() == 0;
    }
}
EOF

cat > "$CORE/PriceCalculator.java" <<'EOF'
package com.acme.core;

public class PriceCalculator {

    private final int discountPercent;

    public PriceCalculator(int discountPercent) {
        this.discountPercent = discountPercent;
    }

    /** Applies the discount to the order total. */
    public int apply(Order order) {
        return order.total() * (100 - discountPercent) / 100;
    }

    public int apply(int amount) {
        return amount * (100 - discountPercent) / 100;
    }
}
EOF

cat > "$CORE/Tax.java" <<'EOF'
package com.acme.core;

import java.util.function.Function;

/** Unrelated to PriceCalculator, although it also has apply(Order). */
public class Tax implements Function<Order, Integer> {

    @Override
    public Integer apply(Order order) {
        return order.total() / 5;
    }
}
EOF

cat > "$CORE/Receipt.java" <<'EOF'
package com.acme.core;

public class Receipt {

    public static String render(Order order, PriceCalculator calculator) {
        StringBuilder sb = new StringBuilder();
        for (Order.Line l : order.lines()) {
            if (sb.length() == 0) {
                sb.append("Items: ");
            } else {
                sb.append(", ");
            }
            sb.append(l.quantity).append("x ").append(l.sku);
        }
        String[] footer = {};
        if (footer.length == 0) {
            sb.append('\n');
        }
        sb.append("Total: ").append(calculator.apply(order));
        sb.append(" (was ").append(order.legacyTotal()).append(")");
        return sb.toString();
    }
}
EOF

cat > "$CORE_TEST/OrderTest.java" <<'EOF'
package com.acme.core;

public class OrderTest {

    public static void main(String[] args) {
        Order order = new Order().addLine("apple", 2).addLine("pear");
        check(order.total() == 10, "total");
        check(order.legacyTotal() == order.total(), "legacy total");
        check(new PriceCalculator(10).apply(order) == 9, "discount");
        check(new Tax().apply(order) == 2, "tax");
        String empty = "";
        check(empty.length() == 0, "empty string");
        System.out.println("ok");
    }

    static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
EOF

cat > "$APP/Shop.java" <<'EOF'
package com.acme.app;

import com.acme.core.Cart;
import com.acme.core.Order;
import com.acme.core.PriceCalculator;
import com.acme.core.Receipt;
import com.acme.core.Tax;
import java.util.List;
import java.util.function.Function;

public class Shop {

    private final PriceCalculator calculator = new PriceCalculator(5);
    private final Function<Order, Integer> tax = new Tax();

    public String checkout(String customer, List<String> skus) {
        if (customer.length() == 0 || skus.size() == 0) {
            return "nothing to do";
        }
        Order order = new Order();
        for (String sku : skus) {
            order.addLine(sku, 1);
        }
        int net = calculator.apply(order);
        int gross = net + tax.apply(order);
        System.out.println("legacyTotal was " + order.legacyTotal());
        return Receipt.render(order, calculator) + " gross " + gross;
    }

    public Order quickOrder(String sku) {
        Cart cart = new Cart().addLine(sku, 2);
        return cart.toOrder().addLine("melon", 1);
    }

    public int discounted(int amount) {
        return calculator.apply(amount);
    }
}
EOF

cat > "$APP/Main.java" <<'EOF'
package com.acme.app;

import java.util.List;

public class Main {

    public static void main(String[] args) {
        String name = args.length == 0 ? "guest" : args[0];
        StringBuilder log = new StringBuilder(name);
        if (log.length() == 0) {
            log.append("anonymous");
        }
        System.out.println(new Shop().checkout(name, List.of("apple", "pear")));
        System.out.println(log);
    }
}
EOF

(cd "$P" && ./compile.sh) || { echo "fixture does not compile" >&2; exit 1; }
