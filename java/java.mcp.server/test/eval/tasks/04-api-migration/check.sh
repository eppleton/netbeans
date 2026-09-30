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
for f in core/src/main/java/com/acme/core/Order.java:'note.isEmpty()' \
         core/src/main/java/com/acme/core/Catalog.java:'sku.isEmpty()' \
         core/src/test/java/com/acme/core/OrderTest.java:'empty.isEmpty()' \
         app/src/main/java/com/acme/app/Shop.java:'customer.isEmpty()'; do
    grep -qF "${f#*:}" "${f%%:*}" || fail "String check not migrated in ${f%%:*}"
done
grep -rn --include='*.java' 'note.length() == 0\|sku.length() == 0\|empty.length() == 0\|customer.length() == 0' . && fail "String length checks left"
for f in core/src/main/java/com/acme/core/Receipt.java:'sb.length() == 0' \
         app/src/main/java/com/acme/app/Main.java:'log.length() == 0' \
         app/src/main/java/com/acme/app/Main.java:'args.length == 0' \
         core/src/main/java/com/acme/core/Receipt.java:'footer.length == 0' \
         core/src/main/java/com/acme/core/Cart.java:'items.size() == 0' \
         app/src/main/java/com/acme/app/Shop.java:'skus.size() == 0'; do
    grep -qF "${f#*:}" "${f%%:*}" || fail "must stay unchanged: ${f#*:} in ${f%%:*}"
done
echo "correct"
