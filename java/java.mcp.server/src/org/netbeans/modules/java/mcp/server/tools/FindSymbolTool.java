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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import org.netbeans.api.java.source.ClassIndex;
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.java.source.ElementHandle;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.SourceUtils;
import org.netbeans.modules.java.mcp.server.Json;
import org.netbeans.modules.java.mcp.server.Tool;
import org.netbeans.modules.java.mcp.server.Workspace;
import org.openide.filesystems.FileObject;

/**
 * Finds types declared in the workspace sources, or members of them, by (partial) name.
 */
public final class FindSymbolTool implements Tool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_TYPES_FOR_MEMBERS = 20;

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
        return "Find classes, interfaces, enums and records declared in the workspace sources by name, "
                + "or members of them. Returns canonical symbols (to use with the other tools), kind and "
                + "location. The query is a case-insensitive prefix of the simple type name (\"OrderServ\"), "
                + "a glob (\"*Service\", \"Order*Impl\") or a partially qualified name (\"billing.Invoice\"). "
                + "Add '#member' to search members of the matching types: \"OrderService#save\" (prefix), "
                + "\"*Repository#find*\" (glob), \"Order#<init>\" (constructors).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Json.obj(
                "type", "object",
                "properties", Json.obj(
                        "query", Json.obj("type", "string",
                                "description", "Type name, prefix or glob, optionally followed by #member"),
                        "limit", Json.obj("type", "integer", "description", "Maximum results, default " + DEFAULT_LIMIT)),
                "required", List.of("query"));
    }

    /** A type found in the index. */
    private record TypeHit(String kind, FileObject file) {
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
        int hash = query.indexOf('#');
        String typeQuery = hash < 0 ? query : query.substring(0, hash);
        String memberQuery = hash < 0 ? null : query.substring(hash + 1);
        if (typeQuery.isEmpty() || memberQuery != null && memberQuery.isEmpty()) {
            return Result.error("Use 'Type' or 'Type#member', e.g. OrderService#save");
        }

        Map<String, TypeHit> types = findTypes(typeQuery, roots);
        if (types.isEmpty()) {
            return Result.ok("No types matching '" + typeQuery + "' in the workspace sources.");
        }
        // canonical symbol -> "kind  location"
        Map<String, String> found = new TreeMap<>();
        String note = "";
        if (memberQuery == null) {
            types.forEach((fqn, hit) -> found.put(fqn,
                    hit.kind() + "  " + (hit.file() != null ? workspace.displayPath(hit.file()) : "?")));
        } else {
            if (types.size() > MAX_TYPES_FOR_MEMBERS) {
                note = "(members of the first " + MAX_TYPES_FOR_MEMBERS + " of " + types.size()
                        + " matching types; qualify the type part to narrow)\n";
            }
            findMembers(types, memberQuery, found);
            if (found.isEmpty()) {
                return Result.ok(note + "No members matching '" + memberQuery + "' in " + types.size()
                        + (types.size() == 1 ? " type" : " types") + " matching '" + typeQuery + "'.");
            }
        }
        StringBuilder sb = new StringBuilder(note);
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

    private Map<String, TypeHit> findTypes(String query, List<FileObject> roots) {
        String simple = query.substring(query.lastIndexOf('.') + 1);
        String qualifierFilter = query.contains(".") ? query.substring(0, query.lastIndexOf('.')).toLowerCase(Locale.ROOT) : null;
        boolean glob = simple.contains("*") || simple.contains("?");
        ClassIndex.NameKind kind = glob ? ClassIndex.NameKind.CASE_INSENSITIVE_REGEXP : ClassIndex.NameKind.CASE_INSENSITIVE_PREFIX;
        String pattern = glob ? globToRegex(simple) : simple;

        Map<String, TypeHit> found = new TreeMap<>();
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
                found.computeIfAbsent(fqn, k -> new TypeHit(h.getKind().name().toLowerCase(Locale.ROOT),
                        SourceUtils.getFile(h, cpInfo)));
            }
        }
        return found;
    }

    private void findMembers(Map<String, TypeHit> types, String memberQuery, Map<String, String> into) throws IOException {
        boolean constructors = SymbolSpec.CONSTRUCTOR.equals(memberQuery);
        Pattern pattern = memberQuery.contains("*") || memberQuery.contains("?")
                ? Pattern.compile(globToRegex(memberQuery), Pattern.CASE_INSENSITIVE)
                : Pattern.compile(Pattern.quote(memberQuery) + ".*", Pattern.CASE_INSENSITIVE);
        // parse each file once for all of its matching types
        Map<FileObject, List<String>> byFile = new LinkedHashMap<>();
        types.entrySet().stream().limit(MAX_TYPES_FOR_MEMBERS)
                .filter(e -> e.getValue().file() != null)
                .forEach(e -> byFile.computeIfAbsent(e.getValue().file(), f -> new ArrayList<>()).add(e.getKey()));
        for (Map.Entry<FileObject, List<String>> f : byFile.entrySet()) {
            JavaSource js = JavaSource.forFileObject(f.getKey());
            if (js == null) {
                continue;
            }
            String where = workspace.displayPath(f.getKey());
            js.runUserActionTask(cc -> {
                cc.toPhase(JavaSource.Phase.ELEMENTS_RESOLVED);
                for (String fqn : f.getValue()) {
                    TypeElement type = cc.getElements().getTypeElement(fqn);
                    if (type == null) {
                        continue;
                    }
                    for (Element e : type.getEnclosedElements()) {
                        boolean matches = switch (e.getKind()) {
                            case CONSTRUCTOR -> constructors;
                            case METHOD, FIELD, ENUM_CONSTANT, RECORD_COMPONENT ->
                                !constructors && pattern.matcher(e.getSimpleName()).matches();
                            default -> false;
                        };
                        if (matches) {
                            boolean implicit = cc.getElements().getOrigin(e) != Elements.Origin.EXPLICIT;
                            int line = implicit ? 0 : SymbolResolver.line(cc, e);
                            into.put(SymbolResolver.signature(e, cc), e.getKind().name().toLowerCase(Locale.ROOT)
                                    .replace('_', ' ') + "  " + where + (line > 0 ? ":" + line : "")
                                    + (implicit ? "  (implicit)" : ""));
                        }
                    }
                }
            }, true);
        }
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
