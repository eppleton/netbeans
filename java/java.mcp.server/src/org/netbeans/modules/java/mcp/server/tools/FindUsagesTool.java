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

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import javax.lang.model.element.ElementKind;
import org.netbeans.api.queries.FileEncodingQuery;
import org.netbeans.modules.java.mcp.server.Json;
import org.netbeans.modules.java.mcp.server.Tool;
import org.netbeans.modules.java.mcp.server.Workspace;
import org.netbeans.modules.refactoring.api.Problem;
import org.netbeans.modules.refactoring.api.RefactoringElement;
import org.netbeans.modules.refactoring.api.RefactoringSession;
import org.netbeans.modules.refactoring.api.Scope;
import org.netbeans.modules.refactoring.api.WhereUsedQuery;
import org.netbeans.modules.refactoring.java.api.WhereUsedQueryConstants;
import org.openide.filesystems.FileObject;
import org.openide.text.PositionBounds;
import org.openide.util.lookup.Lookups;

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
            roots = workspace.awaitSourceRoots(60, TimeUnit.SECONDS);
            symbol = SymbolResolver.resolve(spec, roots);
        } catch (Workspace.NotReadyException | SymbolResolver.ResolutionException ex) {
            return Result.error(ex.getMessage());
        }

        WhereUsedQuery query = new WhereUsedQuery(Lookups.singleton(symbol.handle()));
        query.putValue(WhereUsedQuery.FIND_REFERENCES, true);
        if (includeOverriding && symbol.kind() == ElementKind.METHOD) {
            query.putValue(WhereUsedQueryConstants.FIND_OVERRIDING_METHODS, true);
        }
        query.getContext().add(Scope.create(roots, null, null, true));

        RefactoringSession session = RefactoringSession.create("MCP find usages");
        try {
            Problem p = firstFatal(query.preCheck());
            if (p == null) {
                p = firstFatal(query.checkParameters());
            }
            if (p == null) {
                p = firstFatal(query.prepare(session));
            }
            if (p != null) {
                return Result.error("Find usages of " + symbol.signature() + " failed: " + p.getMessage());
            }
            return Result.ok(format(symbol.signature(), session, limit));
        } finally {
            session.finished();
        }
    }

    private static Problem firstFatal(Problem p) {
        for (; p != null; p = p.getNext()) {
            if (p.isFatal()) {
                return p;
            }
        }
        return null;
    }

    private String format(String signature, RefactoringSession session, int limit) throws IOException {
        // file -> line -> source line; TreeMaps keep output stable and sorted
        Map<String, TreeMap<Integer, String>> byFile = new TreeMap<>();
        Map<FileObject, List<String>> lineCache = new HashMap<>();
        int total = 0;
        for (RefactoringElement re : session.getRefactoringElements()) {
            FileObject file = re.getParentFile();
            PositionBounds pos = re.getPosition();
            if (file == null || !file.isData() || pos == null) {
                continue;
            }
            int line = pos.getBegin().getLine();
            List<String> lines = lineCache.computeIfAbsent(file, FindUsagesTool::readLines);
            String text = line >= 0 && line < lines.size() ? lines.get(line).strip() : "";
            if (byFile.computeIfAbsent(workspace.displayPath(file), k -> new TreeMap<>()).put(line + 1, text) == null) {
                total++;
            }
        }
        if (total == 0) {
            return "No usages of " + signature + " in the workspace.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(total).append(total == 1 ? " usage" : " usages").append(" of ").append(signature)
                .append(" in ").append(byFile.size()).append(byFile.size() == 1 ? " file" : " files").append('\n');
        int shown = 0;
        outer:
        for (Map.Entry<String, TreeMap<Integer, String>> f : byFile.entrySet()) {
            sb.append('\n').append(f.getKey()).append('\n');
            for (Map.Entry<Integer, String> l : f.getValue().entrySet()) {
                if (shown++ == limit) {
                    sb.append("\n... ").append(total - limit).append(" more usages not shown, raise 'limit' to see them\n");
                    break outer;
                }
                sb.append("  ").append(l.getKey()).append(": ").append(l.getValue()).append('\n');
            }
        }
        return sb.toString();
    }

    private static List<String> readLines(FileObject fo) {
        try {
            Charset cs = FileEncodingQuery.getEncoding(fo);
            return fo.asLines(cs.name());
        } catch (IOException ex) {
            return new ArrayList<>();
        }
    }
}
