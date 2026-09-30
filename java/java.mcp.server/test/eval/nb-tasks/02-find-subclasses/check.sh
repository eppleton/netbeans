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
expected='org.netbeans.modules.j2ee.jpa.refactoring.moveclass.PersistenceXmlMoveClass
org.netbeans.modules.j2ee.jpa.refactoring.PersistenceXmlRefactoring
org.netbeans.modules.j2ee.jpa.refactoring.rename.EntityRename
org.netbeans.modules.j2ee.jpa.refactoring.rename.PersistenceXmlPackageRename
org.netbeans.modules.j2ee.jpa.refactoring.rename.PersistenceXmlRename
org.netbeans.modules.j2ee.jpa.refactoring.rename.RelationshipMappingRename
org.netbeans.modules.j2ee.jpa.refactoring.safedelete.PersistenceXmlSafeDelete
org.netbeans.modules.j2ee.jpa.refactoring.whereused.PersistenceXmlWhereUsed
org.netbeans.modules.j2ee.jpa.refactoring.whereused.RelationshipMappingWhereUsed
org.netbeans.modules.refactoring.java.plugins.ChangeParametersPlugin
org.netbeans.modules.refactoring.java.plugins.CopyClassesRefactoringPlugin
org.netbeans.modules.refactoring.java.plugins.CopyClassRefactoringPlugin
org.netbeans.modules.refactoring.java.plugins.EncapsulateFieldRefactoringPlugin
org.netbeans.modules.refactoring.java.plugins.EncapsulateFieldsPlugin
org.netbeans.modules.refactoring.java.plugins.ExtractInterfaceRefactoringPlugin
org.netbeans.modules.refactoring.java.plugins.ExtractSuperclassRefactoringPlugin
org.netbeans.modules.refactoring.java.plugins.InlineRefactoringPlugin
org.netbeans.modules.refactoring.java.plugins.InnerToOuterRefactoringPlugin
org.netbeans.modules.refactoring.java.plugins.IntroduceLocalExtensionPlugin
org.netbeans.modules.refactoring.java.plugins.IntroduceParameterPlugin
org.netbeans.modules.refactoring.java.plugins.JavaWhereUsedQueryPlugin
org.netbeans.modules.refactoring.java.plugins.MoveFileRefactoringPlugin
org.netbeans.modules.refactoring.java.plugins.MoveMembersRefactoringPlugin
org.netbeans.modules.refactoring.java.plugins.PullUpRefactoringPlugin
org.netbeans.modules.refactoring.java.plugins.PushDownRefactoringPlugin
org.netbeans.modules.refactoring.java.plugins.RenamePropertyRefactoringPlugin
org.netbeans.modules.refactoring.java.plugins.RenameRefactoringPlugin
org.netbeans.modules.refactoring.java.plugins.RenameTestClassRefactoringPlugin
org.netbeans.modules.refactoring.java.plugins.ReplaceConstructorWithBuilderPlugin
org.netbeans.modules.refactoring.java.plugins.ReplaceConstructorWithFactoryPlugin
org.netbeans.modules.refactoring.java.plugins.SafeDeleteRefactoringPlugin
org.netbeans.modules.refactoring.java.plugins.UseSuperTypeRefactoringPlugin'
[ -f answer.txt ] || { echo "no answer.txt"; exit 1; }
got=$(sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' answer.txt | grep -v '^$' | sort -u)
if [ "$got" != "$expected" ]; then
    echo "wrong classes: $(diff <(echo "$expected") <(echo "$got") | grep -c '^<') missing, $(diff <(echo "$expected") <(echo "$got") | grep -c '^>') wrong"
    exit 1
fi
changed=$(git status --porcelain -- . ':!answer.txt')
[ -z "$changed" ] || { echo "files changed: $changed"; exit 1; }
echo "correct"
