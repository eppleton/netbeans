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
import org.netbeans.modules.refactoring.api.WhereUsedQuery;
import org.netbeans.modules.refactoring.java.api.WhereUsedQueryConstants;
import org.openide.filesystems.FileObject;

/**
 * Finds subtypes of a type or overriding methods of a method using the Java
 * refactoring engine ({@link WhereUsedQuery}).
 */
public final class FindImplementationsTool implements Tool {

    private static final int DEFAULT_LIMIT = 200;

    private final Workspace workspace;

    public FindImplementationsTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "find_implementations";
    }

    @Override
    public String description() {
        return "Find the implementations of a Java interface or class (all subtypes, also indirect ones) "
                + "or of a method (all overriding methods) in the workspace. Use this instead of grepping "
                + "for 'implements'/'extends' or method names, e.g. before changing an interface method. "
                + "Symbol syntax: com.acme.Foo, com.acme.Foo#bar(String,int); parameter types may be omitted "
                + "when the method name is not overloaded. Output: declarations grouped by file as "
                + "'line: source line'.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Json.obj(
                "type", "object",
                "properties", Json.obj(
                        "symbol", Json.obj("type", "string",
                                "description", "Fully qualified type or method, e.g. com.acme.PaymentService#pay(Order)"),
                        "direct_only", Json.obj("type", "boolean",
                                "description", "For types: only direct subtypes. Default false."),
                        "limit", Json.obj("type", "integer",
                                "description", "Maximum results listed, default " + DEFAULT_LIMIT)),
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
        boolean directOnly = Json.bool(arguments, "direct_only", false);
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
        query.putValue(WhereUsedQuery.FIND_REFERENCES, false);
        switch (symbol.kind()) {
            case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE ->
                query.putValue(directOnly ? WhereUsedQueryConstants.FIND_DIRECT_SUBCLASSES
                        : WhereUsedQueryConstants.FIND_SUBCLASSES, true);
            case METHOD ->
                query.putValue(WhereUsedQueryConstants.FIND_OVERRIDING_METHODS, true);
            default -> {
                return Result.error(symbol.signature() + " is a " + symbol.kind().name().toLowerCase(Locale.ROOT)
                        + "; implementations exist only for types and methods. Use find_usages instead.");
            }
        }
        return WhereUsed.run(workspace, query, symbol.signature(), "implementation", limit);
    }
}
