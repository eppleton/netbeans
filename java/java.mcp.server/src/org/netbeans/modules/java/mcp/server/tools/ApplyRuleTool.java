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

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.netbeans.api.actions.Savable;
import org.netbeans.api.java.source.ModificationResult;
import org.netbeans.api.java.source.WorkingCopy;
import org.netbeans.api.java.source.matching.Occurrence;
import org.netbeans.api.queries.FileEncodingQuery;
import org.netbeans.modules.java.mcp.server.Json;
import org.netbeans.modules.java.mcp.server.Tool;
import org.netbeans.modules.java.mcp.server.Workspace;
import org.netbeans.spi.java.hints.support.TransformationSupport;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/**
 * Structural search and replace with NetBeans declarative hint rules
 * (Jackpot), through the public {@link TransformationSupport}.
 */
public final class ApplyRuleTool implements Tool {

    private static final int DEFAULT_LIMIT = 200;
    private static final Pattern STATEMENT_VARIABLE = Pattern.compile("(\\$[A-Za-z_][\\w$]*)\\s*;");

    private final Workspace workspace;

    public ApplyRuleTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "apply_rule";
    }

    @Override
    public String description() {
        return "Structural search and replace over all Java code of the workspace, matching syntax trees and "
                + "types instead of text. With a rewrite ('=>') every match is transformed and the changes are "
                + "written to disk (dry_run: only show the diff); without one, the matches are listed. Use it "
                + "for API migrations and mechanical changes across many files instead of sed or editing file "
                + "by file.\n"
                + "Syntax: <pattern> [:: <conditions>] [=> <replacement>] ;;\n"
                + "- $name matches one expression/statement, $name$ any number of them (e.g. arguments), "
                + "$_ is an unnamed wildcard.\n"
                + "- Conditions constrain types: $x instanceof java.util.Collection (combine with &&).\n"
                + "- Write types fully qualified; imports are added and simplified.\n"
                + "- Several rules in one call, each ending with ;;\n"
                + "Examples:\n"
                + "  $s.length() == 0 :: $s instanceof java.lang.String => $s.isEmpty() ;;\n"
                + "  new java.lang.Integer($v) => java.lang.Integer.valueOf($v) ;;\n"
                + "  $c.size() == 0 :: $c instanceof java.util.Collection => $c.isEmpty() ;;\n"
                + "  java.util.Objects.equals($a, $b) ;;   (search only)\n"
                + "Search mode checks the pattern and type conditions only. Output: matches as 'line: source "
                + "line' grouped by file, or a unified diff of the changes.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Json.obj(
                "type", "object",
                "properties", Json.obj(
                        "rule", Json.obj("type", "string", "description", "One or more rules, each ending with ;;"),
                        "paths", Json.obj("type", "array", "items", Json.obj("type", "string"),
                                "description", "Limit to these folders or files (relative to the workspace); default: everything"),
                        "dry_run", Json.obj("type", "boolean",
                                "description", "Rewrite rules: only return the diff, write nothing. Default false."),
                        "limit", Json.obj("type", "integer",
                                "description", "Search: maximum matches listed, default " + DEFAULT_LIMIT),
                        "max_diff_lines", Json.obj("type", "integer",
                                "description", "Rewrite: maximum diff lines returned, default "
                                + RefactoringRunner.DEFAULT_MAX_DIFF_LINES)),
                "required", List.of("rule"));
    }

    @Override
    public Result call(Map<?, ?> arguments) throws Exception {
        String rule = Json.string(arguments, "rule");
        if (rule == null) {
            return Result.error("'rule' is required");
        }
        // without ";;" the text is taken as a bare pattern and conditions after "::" are not understood
        if (!rule.endsWith(";;")) {
            rule = rule + " ;;";
        }
        String invalid = checkSyntax(rule);
        if (invalid != null) {
            return Result.error(invalid);
        }
        List<FileObject> roots;
        try {
            roots = workspace.awaitSourceRoots(rule.contains("=>") ? Workspace.Freshness.CURRENT : Workspace.Freshness.INDEXED);
        } catch (Workspace.NotReadyException ex) {
            return Result.error(ex.getMessage());
        }
        List<FileObject> scope = new ArrayList<>();
        if (arguments.get("paths") instanceof List<?> paths) {
            for (Object p : paths) {
                FileObject fo = p instanceof String s ? workspace.findFile(s) : null;
                if (fo == null) {
                    return Result.error("No such file or folder: " + p);
                }
                scope.add(fo);
            }
        }
        return rule.contains("=>")
                ? rewrite(rule, scope, roots, Json.bool(arguments, "dry_run", false),
                        Json.integer(arguments, "max_diff_lines", RefactoringRunner.DEFAULT_MAX_DIFF_LINES))
                : search(rule, scope, Json.integer(arguments, "limit", DEFAULT_LIMIT));
    }

    private static boolean inScope(List<FileObject> scope, FileObject fo) {
        if (scope.isEmpty()) {
            return true;
        }
        for (FileObject s : scope) {
            if (s.equals(fo) || FileUtil.isParentOf(s, fo)) {
                return true;
            }
        }
        return false;
    }

    private Result search(String rule, List<FileObject> scope, int limit) {
        // file -> line -> source line (a line with several matches is listed once)
        Map<String, TreeMap<Integer, String>> byFile = Collections.synchronizedMap(new TreeMap<>());
        TransformationSupport.create(rule, (WorkingCopy wc, Occurrence occurrence) -> {
            FileObject fo = wc.getFileObject();
            if (!inScope(scope, fo)) {
                return;
            }
            int[] span = SymbolResolver.span(wc, occurrence.getOccurrenceRoot().getLeaf());
            if (span == null) {
                return;
            }
            CompilationUnitTree cu = wc.getCompilationUnit();
            int line = (int) cu.getLineMap().getLineNumber(span[0]);
            String text = wc.getText();
            int start = (int) cu.getLineMap().getStartPosition(line);
            int end = text.indexOf('\n', start);
            String source = text.substring(start, end < 0 ? text.length() : end).strip();
            byFile.computeIfAbsent(workspace.displayPath(fo), k -> new TreeMap<>()).put(line, source);
        }).processAllProjects();

        int total = byFile.values().stream().mapToInt(Map::size).sum();
        if (total == 0) {
            return Result.ok("No matches. If you expected some, check the pattern: types fully qualified, "
                    + "$variables for the parts that vary.");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(total).append(total == 1 ? " matching line" : " matching lines").append(" in ")
                .append(byFile.size()).append(byFile.size() == 1 ? " file" : " files").append('\n');
        int shown = 0;
        outer:
        for (Map.Entry<String, TreeMap<Integer, String>> f : byFile.entrySet()) {
            sb.append('\n').append(f.getKey()).append('\n');
            for (Map.Entry<Integer, String> l : f.getValue().entrySet()) {
                if (shown++ == limit) {
                    sb.append("\n... ").append(total - limit).append(" more not shown, raise 'limit' to see them\n");
                    break outer;
                }
                sb.append("  ").append(l.getKey()).append(": ").append(l.getValue()).append('\n');
            }
        }
        return Result.ok(sb.toString());
    }

    private Result rewrite(String rule, List<FileObject> scope, List<FileObject> roots, boolean dryRun, int maxDiffLines)
            throws IOException {
        Collection<? extends ModificationResult> results = TransformationSupport.create(rule).processAllProjects();
        Map<FileObject, String> before = new LinkedHashMap<>();
        Map<FileObject, String> planned = new LinkedHashMap<>();
        for (ModificationResult mr : results) {
            for (FileObject fo : mr.getModifiedFileObjects()) {
                if (!inScope(scope, fo)) {
                    for (ModificationResult.Difference d : mr.getDifferences(fo)) {
                        d.exclude(true);
                    }
                    continue;
                }
                String old = read(fo);
                String changed = mr.getResultingSource(fo);
                if (!old.equals(changed)) {
                    before.put(fo, old);
                    planned.put(fo, changed);
                }
            }
        }
        if (planned.isEmpty()) {
            return Result.ok("No matches, nothing to change. If you expected some, check the pattern: types fully "
                    + "qualified, $variables for the parts that vary, each rule ending with ;;");
        }
        Map<FileObject, String> after = planned;
        if (!dryRun) {
            for (ModificationResult mr : results) {
                mr.commit();
            }
            // commit() changes open documents instead of files; save them
            for (Savable savable : Savable.REGISTRY.lookupAll(Savable.class)) {
                savable.save();
            }
            after = new LinkedHashMap<>();
            Set<URL> urls = new LinkedHashSet<>();
            for (FileObject fo : before.keySet()) {
                after.put(fo, read(fo));
                urls.add(fo.toURL());
            }
            RefactoringRunner.reindex(roots, urls);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(dryRun ? "Dry run, nothing written: " : "Applied: ").append(before.size())
                .append(before.size() == 1 ? " file" : " files").append(dryRun ? " would change." : " changed and saved.")
                .append('\n').append('\n');
        int lines = 0;
        List<String> omitted = new ArrayList<>();
        for (Map.Entry<FileObject, String> e : before.entrySet()) {
            String path = workspace.displayPath(e.getKey());
            String diff = UnifiedDiff.diff(path, path, e.getValue(), after.get(e.getKey()));
            int n = diff.split("\n", -1).length - 1;
            if (lines + n > maxDiffLines && lines > 0) {
                omitted.add(path);
                continue;
            }
            sb.append(diff);
            lines += n;
        }
        if (!omitted.isEmpty()) {
            sb.append("\n... diff omitted for ").append(omitted.size()).append(omitted.size() == 1 ? " file" : " files")
                    .append(" (raise 'max_diff_lines' or use git diff): ").append(String.join(", ", omitted)).append('\n');
        }
        return Result.ok(sb.toString());
    }

    /**
     * Checks that the pattern and replacement of each rule parse as Java
     * (an expression or statements; $variables are valid identifiers). A rule
     * that does not parse would otherwise just find nothing.
     *
     * @return a message for the agent, or {@code null} if the rule looks fine
     */
    static String checkSyntax(String rules) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            return null;
        }
        for (String rule : rules.split(";;")) {
            rule = rule.strip();
            if (rule.isEmpty()) {
                continue;
            }
            int arrow = rule.indexOf("=>");
            String left = arrow < 0 ? rule : rule.substring(0, arrow);
            int conditions = left.indexOf("::");
            List<String> parts = new ArrayList<>();
            parts.add((conditions < 0 ? left : left.substring(0, conditions)).strip());
            if (arrow >= 0) {
                String replacement = rule.substring(arrow + 2);
                int fixConditions = replacement.indexOf("::");
                parts.add((fixConditions < 0 ? replacement : replacement.substring(0, fixConditions)).strip());
            }
            for (String part : parts) {
                String error = parseError(javac, "class R { Object o = (" + part + "\n); }");
                // "$body;" stands for any statement, but a bare name is no Java statement
                String statements = STATEMENT_VARIABLE.matcher(part).replaceAll("$1();");
                if (error != null && parseError(javac, "class R { void m() {\n" + statements + "\n} }") != null) {
                    return "The rule does not parse as Java: '" + part + "': " + error
                            + "\nPatterns and replacements are Java expressions or statements with $variables.";
                }
            }
        }
        return null;
    }

    private static String parseError(JavaCompiler javac, String code) {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject source = new SimpleJavaFileObject(URI.create("string:///R.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
        try {
            ((JavacTask) javac.getTask(null, null, diagnostics, List.of("-proc:none"), null, List.of(source))).parse();
        } catch (IOException | RuntimeException ex) {
            return null;
        }
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                // ROOT is javac's English bundle; ENGLISH would fall back to the default locale first
                return d.getMessage(Locale.ROOT);
            }
        }
        return null;
    }

    private static String read(FileObject fo) throws IOException {
        return fo.asText(FileEncodingQuery.getEncoding(fo).name());
    }
}
