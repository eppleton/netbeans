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
import org.netbeans.api.queries.FileEncodingQuery;
import org.netbeans.modules.java.mcp.server.Tool.Result;
import org.netbeans.modules.java.mcp.server.Workspace;
import org.netbeans.modules.refactoring.api.Problem;
import org.netbeans.modules.refactoring.api.RefactoringElement;
import org.netbeans.modules.refactoring.api.RefactoringSession;
import org.netbeans.modules.refactoring.api.Scope;
import org.netbeans.modules.refactoring.api.WhereUsedQuery;
import org.openide.filesystems.FileObject;
import org.openide.util.lookup.Lookups;

/**
 * Runs a {@link WhereUsedQuery} over the workspace sources and lists the
 * found locations grouped by file as {@code line: source line}.
 */
final class WhereUsed {

    private WhereUsed() {
    }

    /**
     * Creates a query for the resolved symbol, scoped to the given source roots.
     */
    static WhereUsedQuery query(SymbolResolver.Resolved symbol, List<FileObject> roots) {
        WhereUsedQuery query = new WhereUsedQuery(Lookups.singleton(symbol.handle()));
        query.getContext().add(Scope.create(roots, null, null, true));
        return query;
    }

    /**
     * Runs the query and formats its results.
     *
     * @param noun what one result is, e.g. "usage"; pluralized by appending "s"
     */
    static Result run(Workspace workspace, WhereUsedQuery query, String signature, String noun, int limit) throws IOException {
        RefactoringSession session = RefactoringSession.create("MCP " + noun + "s");
        try {
            Problem p = firstFatal(query.preCheck());
            if (p == null) {
                p = firstFatal(query.checkParameters());
            }
            if (p == null) {
                p = firstFatal(query.prepare(session));
            }
            if (p != null) {
                return Result.error("Search for " + noun + "s of " + signature + " failed: " + p.getMessage());
            }
            return Result.ok(format(workspace, session, signature, noun, limit));
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

    private static String format(Workspace workspace, RefactoringSession session, String signature, String noun, int limit) throws IOException {
        // file -> line -> source line; TreeMaps keep output stable and sorted
        Map<String, TreeMap<Integer, String>> byFile = new TreeMap<>();
        Map<FileObject, List<String>> lineCache = new HashMap<>();
        int total = 0;
        for (RefactoringElement re : session.getRefactoringElements()) {
            FileObject file = re.getParentFile();
            if (file == null || !file.isData() || re.getPosition() == null) {
                continue;
            }
            int line = re.getPosition().getBegin().getLine();
            List<String> lines = lineCache.computeIfAbsent(file, WhereUsed::readLines);
            String text = line >= 0 && line < lines.size() ? lines.get(line).strip() : "";
            if (byFile.computeIfAbsent(workspace.displayPath(file), k -> new TreeMap<>()).put(line + 1, text) == null) {
                total++;
            }
        }
        if (total == 0) {
            return "No " + noun + "s of " + signature + " in the workspace.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(total).append(' ').append(noun).append(total == 1 ? "" : "s").append(" of ").append(signature)
                .append(" in ").append(byFile.size()).append(byFile.size() == 1 ? " file" : " files").append('\n');
        int shown = 0;
        outer:
        for (Map.Entry<String, TreeMap<Integer, String>> f : byFile.entrySet()) {
            sb.append('\n').append(f.getKey()).append('\n');
            for (Map.Entry<Integer, String> l : f.getValue().entrySet()) {
                if (shown++ == limit) {
                    sb.append("\n... ").append(total - limit).append(" more ").append(noun)
                            .append("s not shown, raise 'limit' to see them\n");
                    break outer;
                }
                sb.append("  ").append(l.getKey()).append(": ").append(l.getValue()).append('\n');
            }
        }
        return sb.toString();
    }

    static List<String> readLines(FileObject fo) {
        try {
            Charset cs = FileEncodingQuery.getEncoding(fo);
            return fo.asLines(cs.name());
        } catch (IOException ex) {
            return new ArrayList<>();
        }
    }
}
