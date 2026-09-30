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
expected='app/src/main/java/com/acme/app/Shop.java:22
app/src/main/java/com/acme/app/Shop.java:32
core/src/main/java/com/acme/core/Cart.java:18
core/src/main/java/com/acme/core/Order.java:23
core/src/test/java/com/acme/core/OrderTest.java:6'
[ -f answer.txt ] || { echo "no answer.txt"; exit 1; }
got=$(sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's#^\./##' answer.txt | grep -v '^$' | sort -u)
if [ "$got" != "$expected" ]; then
    echo "wrong call sites:"; diff <(echo "$expected") <(echo "$got")
    exit 1
fi
changed=$(git status --porcelain -- . ':!answer.txt')
[ -z "$changed" ] || { echo "source files changed: $changed"; exit 1; }
echo "correct"
