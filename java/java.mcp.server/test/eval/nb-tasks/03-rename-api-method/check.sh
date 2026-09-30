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

# run in the workspace (the java/ folder); exit 0 = correct
fail() { echo "$*"; exit 1; }
# occurrences, not lines: two lines in RenameRefactoringUI call both the API and the internal method
count() { grep -rho "$1" --include='*.java' . | wc -l | tr -d ' '; }
grep -q 'public static ClasspathInfo classpathInfoFor(' refactoring.java/src/org/netbeans/modules/refactoring/java/api/JavaRefactoringUtils.java \
    || fail "declaration not renamed"
[ "$(count 'JavaRefactoringUtils.getClasspathInfoFor(')" = 0 ] || fail "$(count 'JavaRefactoringUtils.getClasspathInfoFor(') calls of the old name left"
[ "$(count 'JavaRefactoringUtils.classpathInfoFor(')" = 29 ] || fail "expected 29 renamed calls, found $(count 'JavaRefactoringUtils.classpathInfoFor(')"
# the 67 original occurrences minus 29 calls and the declaration: the internal overloads and their calls
[ "$(count 'getClasspathInfoFor(')" = 37 ] || fail "other getClasspathInfoFor methods changed: $(count 'getClasspathInfoFor(') occurrences instead of 37"
echo "correct"
