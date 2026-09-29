/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.netbeans.modules.java.mcp.server.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.lang.model.SourceVersion;
import org.netbeans.modules.java.mcp.server.Json;
import org.netbeans.modules.java.mcp.server.Tool;
import org.netbeans.modules.java.mcp.server.Workspace;
import org.netbeans.modules.refactoring.api.RenameRefactoring;
import org.netbeans.modules.refactoring.java.api.JavaRefactoringUtils;
import org.openide.filesystems.FileObject;
import org.openide.util.lookup.Lookups;

/**
 * Renames a type, method or field with the Java refactoring engine and
 * reports the changes as a unified diff. The files are changed on disk.
 */
public final class RenameTool implements Tool {

    private final Workspace workspace;

    public RenameTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "rename";
    }

    @Override
    public String description() {
        return "Rename a Java type, method or field everywhere in the workspace with the IDE's refactoring "
                + "engine, and write the changes to disk. Handles what text replacement gets wrong: overloads "
                + "and same-named members of other types stay untouched, overriding methods are renamed with "
                + "the method, a renamed top-level class also renames its file, imports and qualified "
                + "references are updated. Symbol syntax: com.acme.Foo, com.acme.Foo#bar(String,int), "
                + "com.acme.Foo#field. If the rename would break the code (name clash, ...) nothing is "
                + "changed and the problem is reported. Output: warnings, then a unified diff of all changes "
                + "(use git to review or revert).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Json.obj(
                "type", "object",
                "properties", Json.obj(
                        "symbol", Json.obj("type", "string",
                                "description", "Fully qualified symbol, e.g. com.acme.OrderService#save(Order)"),
                        "new_name", Json.obj("type", "string",
                                "description", "New simple name, e.g. store (not qualified)"),
                        "max_diff_lines", Json.obj("type", "integer",
                                "description", "Maximum diff lines returned, default " + RefactoringRunner.DEFAULT_MAX_DIFF_LINES
                                + "; the rename itself is always complete")),
                "required", List.of("symbol", "new_name"));
    }

    @Override
    public Result call(Map<?, ?> arguments) throws Exception {
        String symbolText = Json.string(arguments, "symbol");
        String newName = Json.string(arguments, "new_name");
        if (symbolText == null || newName == null) {
            return Result.error("'symbol' and 'new_name' are required");
        }
        if (!SourceVersion.isIdentifier(newName) || SourceVersion.isKeyword(newName)) {
            return Result.error("'" + newName + "' is not a valid Java identifier. Give the new simple name only.");
        }
        int maxDiffLines = Json.integer(arguments, "max_diff_lines", RefactoringRunner.DEFAULT_MAX_DIFF_LINES);
        SymbolSpec spec;
        try {
            spec = SymbolSpec.parse(symbolText);
        } catch (IllegalArgumentException ex) {
            return Result.error(ex.getMessage());
        }
        List<FileObject> roots;
        SymbolResolver.Resolved symbol;
        try {
            roots = workspace.awaitSourceRoots(60, TimeUnit.SECONDS);
            symbol = SymbolResolver.resolve(spec, roots);
        } catch (Workspace.NotReadyException | SymbolResolver.ResolutionException ex) {
            return Result.error(ex.getMessage());
        }
        switch (symbol.kind()) {
            case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE, METHOD, FIELD, ENUM_CONSTANT -> {
            }
            case CONSTRUCTOR -> {
                return Result.error("Constructors are named after their class; rename the class instead.");
            }
            default -> {
                return Result.error("Renaming a " + symbol.kind() + " is not supported.");
            }
        }
        if (newName.equals(symbol.simpleName())) {
            return Result.error(symbol.signature() + " is already called " + newName + ".");
        }

        List<Object> lookup = new ArrayList<>();
        lookup.add(symbol.handle());
        if (symbol.topLevelType() && symbol.simpleName().equals(symbol.file().getName())) {
            // like the IDE and the LSP server: rename the file together with its public class
            lookup.add(symbol.file());
        }
        RenameRefactoring refactoring = new RenameRefactoring(Lookups.fixed(lookup.toArray()));
        refactoring.getContext().add(JavaRefactoringUtils.getClasspathInfoFor(symbol.file()));
        refactoring.setNewName(newName);

        return new RefactoringRunner(workspace, roots, refactoring,
                "rename " + symbol.signature() + " to " + newName)
                .involving(symbol.file())
                // a renamed public class renames its file
                .relocatingWith(fo -> fo.getParent() != null ? fo.getParent().getFileObject(newName, fo.getExt()) : null)
                .run(maxDiffLines);
    }
}
