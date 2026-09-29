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
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.lang.model.SourceVersion;
import org.netbeans.api.queries.FileEncodingQuery;
import org.netbeans.modules.java.mcp.server.Json;
import org.netbeans.modules.java.mcp.server.Tool;
import org.netbeans.modules.java.mcp.server.Workspace;
import org.netbeans.modules.parsing.api.indexing.IndexingManager;
import org.netbeans.modules.refactoring.api.Problem;
import org.netbeans.modules.refactoring.api.RefactoringElement;
import org.netbeans.modules.refactoring.api.RefactoringSession;
import org.netbeans.modules.refactoring.api.RenameRefactoring;
import org.netbeans.modules.refactoring.java.api.JavaRefactoringUtils;
import org.openide.filesystems.FileObject;
import org.openide.util.lookup.Lookups;

/**
 * Renames a type, method or field with the Java refactoring engine and
 * reports the changes as a unified diff. The files are changed on disk.
 */
public final class RenameTool implements Tool {

    private static final int DEFAULT_MAX_DIFF_LINES = 400;

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
                                "description", "Maximum diff lines returned, default " + DEFAULT_MAX_DIFF_LINES
                                + "; the rename itself is always complete")),
                "required", List.of("symbol", "new_name"));
    }

    /** Path and content of a file before the refactoring. */
    private record Before(String path, URL url, String text) {
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
        int maxDiffLines = Json.integer(arguments, "max_diff_lines", DEFAULT_MAX_DIFF_LINES);
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

        RefactoringSession session = RefactoringSession.create("MCP rename");
        try {
            // plugins often report the same problem once per element
            Set<String> warnings = new LinkedHashSet<>();
            Problem fatal = collect(refactoring.checkParameters(), warnings);
            if (fatal == null) {
                fatal = collect(refactoring.preCheck(), warnings);
            }
            if (fatal == null) {
                fatal = collect(refactoring.prepare(session), warnings);
            }
            if (fatal != null) {
                return Result.error("Cannot rename " + symbol.signature() + " to " + newName + ": "
                        + fatal.getMessage() + warningsText(warnings) + "\nNothing was changed.");
            }

            Map<FileObject, Before> before = new LinkedHashMap<>();
            snapshot(symbol.file(), before);
            for (RefactoringElement re : session.getRefactoringElements()) {
                snapshot(re.getParentFile(), before);
            }
            Problem commit = collect(session.doRefactoring(true), warnings);
            List<Change> changes = changes(before, newName);
            // the index learns about the saved files asynchronously; without this, a following
            // call (e.g. renaming a method of a just renamed class) could see stale file names
            reindex(roots, changes);
            if (commit != null) {
                return Result.error("Renaming " + symbol.signature() + " failed while applying the changes: "
                        + commit.getMessage() + warningsText(warnings)
                        + "\nSome files may have been changed; check with git status.");
            }
            if (changes.isEmpty()) {
                return Result.error("Renaming " + symbol.signature() + " to " + newName + " changed nothing."
                        + warningsText(warnings) + "\nDetails are in the IDE log (<userdir>/var/log/messages.log).");
            }
            return Result.ok(report(symbol, newName, changes, warnings, maxDiffLines));
        } finally {
            session.finished();
        }
    }

    /** Adds non-fatal problems to {@code warnings}; returns the first fatal one. */
    private static Problem collect(Problem p, Set<String> warnings) {
        Problem fatal = null;
        for (; p != null; p = p.getNext()) {
            if (p.isFatal()) {
                if (fatal == null) {
                    fatal = p;
                }
            } else {
                warnings.add(p.getMessage());
            }
        }
        return fatal;
    }

    private static String warningsText(Set<String> warnings) {
        StringBuilder sb = new StringBuilder();
        for (String w : warnings) {
            sb.append("\nWarning: ").append(w);
        }
        return sb.toString();
    }

    private void snapshot(FileObject fo, Map<FileObject, Before> into) throws IOException {
        if (fo != null && fo.isData() && !into.containsKey(fo)) {
            into.put(fo, new Before(workspace.displayPath(fo), fo.toURL(), read(fo)));
        }
    }

    private static String read(FileObject fo) throws IOException {
        return fo.asText(FileEncodingQuery.getEncoding(fo).name());
    }

    /**
     * A changed file.
     *
     * @param newUrl {@code null} if the file was deleted
     */
    private record Change(String path, String diff, URL oldUrl, URL newUrl, boolean renamed) {
    }

    private List<Change> changes(Map<FileObject, Before> before, String newName) throws IOException {
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<FileObject, Before> e : before.entrySet()) {
            FileObject fo = e.getKey();
            Before b = e.getValue();
            if (!fo.isValid()) {
                // the refactoring replaced the file object; look for the renamed file next to the old one
                FileObject parent = fo.getParent();
                FileObject renamed = parent != null ? parent.getFileObject(newName, fo.getExt()) : null;
                if (renamed == null) {
                    changes.add(new Change(b.path(), "deleted: " + b.path() + "\n", b.url(), null, false));
                    continue;
                }
                fo = renamed;
            }
            String path = workspace.displayPath(fo);
            String diff = UnifiedDiff.diff(b.path(), path, b.text(), read(fo));
            if (!diff.isEmpty()) {
                changes.add(new Change(path, diff, b.url(), fo.toURL(), !path.equals(b.path())));
            }
        }
        return changes;
    }

    private static void reindex(List<FileObject> roots, List<Change> changes) {
        for (FileObject root : roots) {
            URL rootUrl = root.toURL();
            String prefix = rootUrl.toString();
            Set<URL> files = new LinkedHashSet<>();
            for (Change c : changes) {
                if (c.oldUrl().toString().startsWith(prefix)) {
                    files.add(c.oldUrl());
                }
                if (c.newUrl() != null && c.newUrl().toString().startsWith(prefix)) {
                    files.add(c.newUrl());
                }
            }
            if (!files.isEmpty()) {
                IndexingManager.getDefault().refreshIndexAndWait(rootUrl, files);
            }
        }
    }

    private static String report(SymbolResolver.Resolved symbol, String newName, List<Change> changes,
            Set<String> warnings, int maxDiffLines) {
        long renamedFiles = changes.stream().filter(Change::renamed).count();
        StringBuilder sb = new StringBuilder();
        sb.append("Renamed ").append(symbol.signature()).append(" to ").append(newName).append(": ")
                .append(changes.size()).append(changes.size() == 1 ? " file" : " files").append(" changed");
        if (renamedFiles > 0) {
            sb.append(", ").append(renamedFiles).append(renamedFiles == 1 ? " file" : " files").append(" renamed");
        }
        sb.append(". The changes are saved to disk.\n");
        for (String w : warnings) {
            sb.append("Warning: ").append(w).append('\n');
        }
        sb.append('\n');
        int lines = 0;
        List<String> omitted = new ArrayList<>();
        for (Change c : changes) {
            int n = c.diff().split("\n", -1).length - 1;
            if (lines + n > maxDiffLines && lines > 0) {
                omitted.add(c.path());
                continue;
            }
            sb.append(c.diff());
            lines += n;
        }
        if (!omitted.isEmpty()) {
            sb.append("\n... diff omitted for ").append(omitted.size()).append(omitted.size() == 1 ? " file" : " files")
                    .append(" (raise 'max_diff_lines' or use git diff): ").append(String.join(", ", omitted)).append('\n');
        }
        return sb.toString();
    }
}
