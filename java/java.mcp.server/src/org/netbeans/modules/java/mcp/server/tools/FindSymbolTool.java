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

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import javax.lang.model.element.TypeElement;
import org.netbeans.api.java.source.ClassIndex;
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.java.source.ElementHandle;
import org.netbeans.api.java.source.SourceUtils;
import org.netbeans.modules.java.mcp.server.Json;
import org.netbeans.modules.java.mcp.server.Tool;
import org.netbeans.modules.java.mcp.server.Workspace;
import org.openide.filesystems.FileObject;

/**
 * Finds types declared in the workspace sources by (partial) name.
 */
public final class FindSymbolTool implements Tool {

    private static final int DEFAULT_LIMIT = 50;

    private final Workspace workspace;

    public FindSymbolTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "find_symbol";
    }

    @Override
    public String description() {
        return "Find classes, interfaces, enums and records declared in the workspace sources by name. "
                + "Returns fully qualified names (to use with the other tools), kind and file. "
                + "The query is a case-insensitive prefix of the simple name (\"OrderServ\"), "
                + "a glob (\"*Service\", \"Order*Impl\"), or a partially qualified name "
                + "(\"billing.Invoice\") to narrow by package.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Json.obj(
                "type", "object",
                "properties", Json.obj(
                        "query", Json.obj("type", "string", "description", "Type name, prefix or glob"),
                        "limit", Json.obj("type", "integer", "description", "Maximum results, default " + DEFAULT_LIMIT)),
                "required", List.of("query"));
    }

    @Override
    public Result call(Map<?, ?> arguments) throws Exception {
        String query = Json.string(arguments, "query");
        if (query == null) {
            return Result.error("'query' is required");
        }
        int limit = Json.integer(arguments, "limit", DEFAULT_LIMIT);
        List<FileObject> roots;
        try {
            roots = workspace.awaitSourceRoots(30, TimeUnit.SECONDS);
        } catch (Workspace.NotReadyException ex) {
            return Result.error(ex.getMessage());
        }

        String simple = query.substring(query.lastIndexOf('.') + 1);
        String qualifierFilter = query.contains(".") ? query.substring(0, query.lastIndexOf('.')).toLowerCase(Locale.ROOT) : null;
        boolean glob = simple.contains("*") || simple.contains("?");
        ClassIndex.NameKind kind = glob ? ClassIndex.NameKind.CASE_INSENSITIVE_REGEXP : ClassIndex.NameKind.CASE_INSENSITIVE_PREFIX;
        String pattern = glob ? globToRegex(simple) : simple;

        // qualified name -> "kind  path"
        Map<String, String> found = new TreeMap<>();
        for (FileObject root : roots) {
            ClasspathInfo cpInfo = ClasspathInfo.create(root);
            Set<ElementHandle<TypeElement>> types = cpInfo.getClassIndex()
                    .getDeclaredTypes(pattern, kind, EnumSet.of(ClassIndex.SearchScope.SOURCE));
            if (types == null) {
                continue;
            }
            for (ElementHandle<TypeElement> h : types) {
                String fqn = h.getQualifiedName().replace('$', '.');
                if (qualifierFilter != null && !fqn.toLowerCase(Locale.ROOT).contains(qualifierFilter)) {
                    continue;
                }
                if (found.containsKey(fqn)) {
                    continue;
                }
                FileObject file = SourceUtils.getFile(h, cpInfo);
                String where = file != null ? workspace.displayPath(file) : "?";
                found.put(fqn, h.getKind().name().toLowerCase(Locale.ROOT) + "  " + where);
            }
        }
        if (found.isEmpty()) {
            return Result.ok("No types matching '" + query + "' in the workspace sources.");
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, String> e : found.entrySet()) {
            if (shown++ == limit) {
                sb.append("... ").append(found.size() - limit).append(" more, refine the query or raise 'limit'\n");
                break;
            }
            sb.append(e.getKey()).append("  ").append(e.getValue()).append('\n');
        }
        return Result.ok(sb.toString());
    }

    static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> {
                    if (Character.isJavaIdentifierPart(c)) {
                        sb.append(c);
                    } else {
                        sb.append('\\').append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
