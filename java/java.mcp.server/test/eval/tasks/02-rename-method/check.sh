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

# run in the project directory; exit 0 = correct
fail() { echo "$*"; exit 1; }
./compile.sh > /dev/null 2>&1 || fail "does not compile"
grep -q 'public int applyDiscount(Order order)' core/src/main/java/com/acme/core/PriceCalculator.java || fail "declaration not renamed"
grep -q 'public int apply(int amount)' core/src/main/java/com/acme/core/PriceCalculator.java || fail "apply(int) must keep its name"
grep -q 'int net = calculator.applyDiscount(order);' app/src/main/java/com/acme/app/Shop.java || fail "Shop call not renamed"
grep -q 'return calculator.apply(amount);' app/src/main/java/com/acme/app/Shop.java || fail "apply(int) call changed"
grep -q 'int gross = net + tax.apply(order);' app/src/main/java/com/acme/app/Shop.java || fail "Tax.apply call changed"
grep -q 'calculator.applyDiscount(order)' core/src/main/java/com/acme/core/Receipt.java || fail "Receipt call not renamed"
grep -q 'new PriceCalculator(10).applyDiscount(order)' core/src/test/java/com/acme/core/OrderTest.java || fail "test call not renamed"
grep -q 'new Tax().apply(order)' core/src/test/java/com/acme/core/OrderTest.java || fail "Tax.apply in test changed"
grep -q 'public Integer apply(Order order)' core/src/main/java/com/acme/core/Tax.java || fail "Tax.apply renamed"
echo "correct"
