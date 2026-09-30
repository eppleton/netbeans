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
expected='java.lsp.server/src/org/netbeans/modules/java/lsp/server/protocol/TextDocumentServiceImpl.java:1508
java.lsp.server/src/org/netbeans/modules/java/lsp/server/protocol/TextDocumentServiceImpl.java:1593
java.lsp.server/src/org/netbeans/modules/java/lsp/server/refactoring/ChangeMethodParametersRefactoring.java:282
java.lsp.server/src/org/netbeans/modules/java/lsp/server/refactoring/ExtractSuperclassOrInterfaceRefactoring.java:245
java.lsp.server/src/org/netbeans/modules/java/lsp/server/refactoring/MoveRefactoring.java:373
java.lsp.server/src/org/netbeans/modules/java/lsp/server/refactoring/MoveRefactoring.java:383
java.lsp.server/src/org/netbeans/modules/java/lsp/server/refactoring/PullUpRefactoring.java:247
java.lsp.server/src/org/netbeans/modules/java/lsp/server/refactoring/PushDownRefactoring.java:190
java.mcp.server/src/org/netbeans/modules/java/mcp/server/tools/ChangeSignatureTool.java:202
java.mcp.server/src/org/netbeans/modules/java/mcp/server/tools/InlineTool.java:88
java.mcp.server/src/org/netbeans/modules/java/mcp/server/tools/MoveTool.java:111
java.mcp.server/src/org/netbeans/modules/java/mcp/server/tools/RenameTool.java:124
java.mcp.server/src/org/netbeans/modules/java/mcp/server/tools/SafeDeleteTool.java:115
refactoring.java/src/org/netbeans/modules/refactoring/java/plugins/ExtractSuperclassRefactoringPlugin.java:259
refactoring.java/src/org/netbeans/modules/refactoring/java/plugins/InlineRefactoringPlugin.java:78
refactoring.java/src/org/netbeans/modules/refactoring/java/plugins/MoveMembersRefactoringPlugin.java:114
refactoring.java/src/org/netbeans/modules/refactoring/java/spi/JavaRefactoringPlugin.java:166
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/CopyClassesUI.java:156
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/CopyClassesUI.java:159
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/MoveClassesUI.java:174
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/MoveClassesUI.java:177
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/MoveClassUI.java:104
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/MoveClassUI.java:93
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/RenameRefactoringUI.java:120
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/RenameRefactoringUI.java:127
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/RenameRefactoringUI.java:145
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/RenameRefactoringUI.java:160
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/SafeDeleteUI.java:100
refactoring.java/src/org/netbeans/modules/refactoring/java/ui/SafeDeleteUI.java:83'
[ -f answer.txt ] || { echo "no answer.txt"; exit 1; }
got=$(sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's#^\./##' answer.txt | grep -v '^$' | sort -u)
if [ "$got" != "$expected" ]; then
    echo "wrong call sites: $(diff <(echo "$expected") <(echo "$got") | grep -c '^<') missing, $(diff <(echo "$expected") <(echo "$got") | grep -c '^>') wrong"
    exit 1
fi
changed=$(git status --porcelain -- . ':!answer.txt')
[ -z "$changed" ] || { echo "files changed: $changed"; exit 1; }
echo "correct"
