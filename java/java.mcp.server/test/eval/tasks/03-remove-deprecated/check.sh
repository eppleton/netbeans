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
if grep -rn 'legacyTotal()' --include='*.java' . | grep -v '"[^"]*legacyTotal[^"]*"' ; then fail "legacyTotal() still declared or called"; fi
grep -q 'order.total()).append(")")' core/src/main/java/com/acme/core/Receipt.java || fail "Receipt call not migrated to total()"
grep -q 'System.out.println("legacyTotal was " + order.total());' app/src/main/java/com/acme/app/Shop.java || fail "Shop call not migrated (or its message was changed)"
out=$(mktemp -d); javac -d "$out" $(find . -name '*.java' -path '*/src/*') 2> /dev/null
java -cp "$out" com.acme.core.OrderTest > /dev/null 2>&1 || { rm -rf "$out"; fail "OrderTest fails"; }
rm -rf "$out"
echo "correct"
