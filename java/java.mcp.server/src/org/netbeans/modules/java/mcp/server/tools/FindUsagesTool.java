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
import java.util.Map;
import javax.lang.model.element.ElementKind;
import org.netbeans.modules.java.mcp.server.Json;
import org.netbeans.modules.java.mcp.server.Tool;
import org.netbeans.modules.java.mcp.server.Workspace;
import org.netbeans.modules.refactoring.api.WhereUsedQuery;
import org.netbeans.modules.refactoring.java.api.WhereUsedQueryConstants;
import org.openide.filesystems.FileObject;

/**
 * Finds references to a type, method, constructor or field using the Java
 * refactoring engine ({@link WhereUsedQuery}).
 */
public final class FindUsagesTool implements Tool {

    private static final int DEFAULT_LIMIT = 200;

    private final Workspace workspace;

    public FindUsagesTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "find_usages";
    }

    @Override
    public String description() {
        return "Find all references to a Java type, method, constructor or field in the workspace, "
                + "resolved semantically: overloads, same-named members of other types, comments and "
                + "strings are not confused with real usages. Use this instead of grep before changing "
                + "or removing an API. Symbol syntax: com.acme.Foo, com.acme.Foo#bar(String,int), "
                + "com.acme.Foo#<init>(), com.acme.Foo#field. Parameter types may be omitted when the "
                + "method name is not overloaded. Output: usages grouped by file as 'line: source line'.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Json.obj(
                "type", "object",
                "properties", Json.obj(
                        "symbol", Json.obj("type", "string",
                                "description", "Fully qualified symbol, e.g. com.acme.OrderService#save(Order)"),
                        "include_overriding", Json.obj("type", "boolean",
                                "description", "For methods: also report usages of overriding methods. Default false."),
                        "limit", Json.obj("type", "integer",
                                "description", "Maximum usages listed, default " + DEFAULT_LIMIT)),
                "required", List.of("symbol"));
    }

    @Override
    public Result call(Map<?, ?> arguments) throws Exception {
        String symbolText = Json.string(arguments, "symbol");
        if (symbolText == null) {
            return Result.error("'symbol' is required");
        }
        SymbolSpec spec;
        try {
            spec = SymbolSpec.parse(symbolText);
        } catch (IllegalArgumentException ex) {
            return Result.error(ex.getMessage());
        }
        boolean includeOverriding = Json.bool(arguments, "include_overriding", false);
        int limit = Json.integer(arguments, "limit", DEFAULT_LIMIT);

        List<FileObject> roots;
        SymbolResolver.Resolved symbol;
        try {
            roots = workspace.awaitSourceRoots(Workspace.Freshness.INDEXED);
            symbol = SymbolResolver.resolve(spec, roots);
        } catch (Workspace.NotReadyException | SymbolResolver.ResolutionException ex) {
            return Result.error(ex.getMessage());
        }

        WhereUsedQuery query = WhereUsed.query(symbol, roots);
        query.putValue(WhereUsedQuery.FIND_REFERENCES, true);
        if (includeOverriding && symbol.kind() == ElementKind.METHOD) {
            query.putValue(WhereUsedQueryConstants.FIND_OVERRIDING_METHODS, true);
        }
        return WhereUsed.run(workspace, query, symbol.signature(), "usage", limit);
    }
}
