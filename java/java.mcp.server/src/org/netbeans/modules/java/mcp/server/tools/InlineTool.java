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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.netbeans.modules.java.mcp.server.Json;
import org.netbeans.modules.java.mcp.server.Tool;
import org.netbeans.modules.java.mcp.server.Workspace;
import org.netbeans.modules.refactoring.java.api.InlineRefactoring;
import org.netbeans.modules.refactoring.java.api.JavaRefactoringUtils;

/**
 * Inlines a method or a constant into all its usages and removes it.
 */
public final class InlineTool implements Tool {

    private final Workspace workspace;

    public InlineTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "inline";
    }

    @Override
    public String description() {
        return "Inline a Java method (replace every call with the method body, adapting parameters and returns) or "
                + "a constant (static final field: replace every usage with its value) and remove the declaration; "
                + "write the changes to disk. Refuses, and changes nothing, when inlining is not possible (e.g. "
                + "recursive or overridden methods, bodies referencing members that are not accessible at a call "
                + "site). An argument is substituted for each use of its parameter, so an argument expression can "
                + "end up evaluated more than once: check the diff when arguments have side effects or are "
                + "expensive. Symbol syntax: com.acme.Foo#helper(String), com.acme.Foo#MAX_SIZE. Output: warnings, "
                + "then a unified diff.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Json.obj(
                "type", "object",
                "properties", Json.obj(
                        "symbol", Json.obj("type", "string",
                                "description", "Fully qualified method or constant, e.g. com.acme.Foo#helper(String)"),
                        "max_diff_lines", Json.obj("type", "integer",
                                "description", "Maximum diff lines returned, default " + RefactoringRunner.DEFAULT_MAX_DIFF_LINES)),
                "required", List.of("symbol"));
    }

    @Override
    public Result call(Map<?, ?> arguments) throws Exception {
        int maxDiffLines = Json.integer(arguments, "max_diff_lines", RefactoringRunner.DEFAULT_MAX_DIFF_LINES);
        Resolution r = Resolution.of(workspace, arguments);
        if (r.error() != null) {
            return r.error();
        }
        SymbolResolver.Resolved symbol = r.symbol();
        InlineRefactoring.Type type = switch (symbol.kind()) {
            case METHOD -> InlineRefactoring.Type.METHOD;
            case FIELD -> InlineRefactoring.Type.CONSTANT;
            default -> null;
        };
        if (type == null) {
            return Result.error("inline works on methods and constants; " + symbol.signature() + " is a "
                    + symbol.kind().name().toLowerCase(Locale.ROOT) + ".");
        }
        InlineRefactoring refactoring = new InlineRefactoring(symbol.handle(), type);
        refactoring.getContext().add(JavaRefactoringUtils.getClasspathInfoFor(symbol.file()));
        return new RefactoringRunner(workspace, r.roots(), refactoring, "inline " + symbol.signature())
                .involving(symbol.file())
                .run(maxDiffLines);
    }
}
