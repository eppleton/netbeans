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
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.netbeans.api.editor.mimelookup.MimeLookup;
import org.netbeans.api.lsp.Diagnostic;
import org.netbeans.api.queries.FileEncodingQuery;
import org.netbeans.modules.java.mcp.server.Json;
import org.netbeans.modules.java.mcp.server.Tool;
import org.netbeans.modules.java.mcp.server.Workspace;
import org.netbeans.modules.parsing.spi.indexing.ErrorsCache;
import org.netbeans.spi.lsp.ErrorProvider;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.URLMapper;

/**
 * Compile errors (and optionally warnings) of given files or of the whole
 * workspace. For the whole workspace the index tells which files have errors,
 * so only those are parsed again to get exact messages.
 */
public final class DiagnosticsTool implements Tool {

    private static final String JAVA_MIME = "text/x-java";
    private static final int DEFAULT_LIMIT = 100;

    private final Workspace workspace;

    public DiagnosticsTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "diagnostics";
    }

    @Override
    public String description() {
        return "Report Java compile errors, as javac inside the IDE sees them, without running a build. "
                + "Without 'files' it checks the whole workspace in seconds (the index knows which files have "
                + "errors), so call it after editing Java code instead of running mvn/gradle compile. With "
                + "'files' it checks just those files and can include warnings. Files edited on disk are picked "
                + "up automatically. Output: 'line:column severity: message' grouped by file.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Json.obj(
                "type", "object",
                "properties", Json.obj(
                        "files", Json.obj("type", "array", "items", Json.obj("type", "string"),
                                "description", "Java files relative to the workspace or absolute; default: whole workspace"),
                        "include_warnings", Json.obj("type", "boolean",
                                "description", "Also report warnings. Default false."),
                        "limit", Json.obj("type", "integer",
                                "description", "Maximum diagnostics listed, default " + DEFAULT_LIMIT)));
    }

    @Override
    public Result call(Map<?, ?> arguments) throws Exception {
        boolean includeWarnings = Json.bool(arguments, "include_warnings", false);
        int limit = Json.integer(arguments, "limit", DEFAULT_LIMIT);
        List<FileObject> roots;
        try {
            roots = workspace.awaitSourceRoots(60, TimeUnit.SECONDS);
        } catch (Workspace.NotReadyException ex) {
            return Result.error(ex.getMessage());
        }
        Collection<? extends ErrorProvider> providers = MimeLookup.getLookup(JAVA_MIME).lookupAll(ErrorProvider.class);
        if (providers.isEmpty()) {
            return Result.error("Java error checking is not available: no ErrorProvider for " + JAVA_MIME
                    + " (is the java.hints module enabled?)");
        }

        StringBuilder problems = new StringBuilder();
        List<FileObject> files = new ArrayList<>();
        boolean wholeWorkspace = !(arguments.get("files") instanceof List<?> requested) || requested.isEmpty();
        if (wholeWorkspace) {
            for (FileObject root : roots) {
                Collection<? extends URL> inError = includeWarnings
                        ? ErrorsCache.getAllFilesWithRecord(root.toURL())
                        : ErrorsCache.getAllFilesInError(root.toURL());
                for (URL u : inError) {
                    FileObject fo = URLMapper.findFileObject(u);
                    if (fo != null && fo.isData()) {
                        files.add(fo);
                    }
                }
            }
        } else {
            for (Object o : (List<?>) arguments.get("files")) {
                FileObject fo = o instanceof String s ? workspace.findFile(s) : null;
                if (fo == null || !fo.isData()) {
                    problems.append("not found: ").append(o).append('\n');
                } else if (!JAVA_MIME.equals(fo.getMIMEType())) {
                    problems.append("not a Java file: ").append(o).append('\n');
                } else {
                    files.add(fo);
                }
            }
        }

        // file -> diagnostics as "line:column severity: message"
        Map<String, List<String>> byFile = new TreeMap<>();
        int errors = 0;
        int warnings = 0;
        for (FileObject fo : files) {
            List<Diagnostic> diags = new ArrayList<>();
            ErrorProvider.Context ctx = new ErrorProvider.Context(fo, ErrorProvider.Kind.ERRORS);
            for (ErrorProvider p : providers) {
                List<? extends Diagnostic> found = p.computeErrors(ctx);
                if (found != null) {
                    diags.addAll(found);
                }
            }
            diags.removeIf(d -> !(d.getSeverity() == Diagnostic.Severity.Error
                    || includeWarnings && d.getSeverity() == Diagnostic.Severity.Warning));
            if (diags.isEmpty()) {
                continue;
            }
            diags.sort(Comparator.comparingInt(d -> d.getStartPosition().getOffset()));
            LineIndex lines = new LineIndex(fo);
            List<String> out = new ArrayList<>();
            for (Diagnostic d : diags) {
                if (d.getSeverity() == Diagnostic.Severity.Error) {
                    errors++;
                } else {
                    warnings++;
                }
                out.add(lines.position(d.getStartPosition().getOffset()) + " "
                        + d.getSeverity().name().toLowerCase(Locale.ROOT) + ": "
                        + d.getDescription().strip().replace("\n", "\n    "));
            }
            byFile.put(workspace.displayPath(fo), out);
        }

        StringBuilder sb = new StringBuilder();
        if (errors + warnings == 0) {
            sb.append(wholeWorkspace
                    ? "No compile errors in the workspace (" + roots.size() + " Java source roots)."
                    : "No " + (includeWarnings ? "errors or warnings" : "errors") + " in " + files.size()
                    + (files.size() == 1 ? " file." : " files."))
                    .append('\n');
        } else {
            sb.append(count(errors, "error"));
            if (includeWarnings) {
                sb.append(", ").append(count(warnings, "warning"));
            }
            sb.append(" in ").append(count(byFile.size(), "file")).append('\n');
            int shown = 0;
            outer:
            for (Map.Entry<String, List<String>> f : byFile.entrySet()) {
                sb.append('\n').append(f.getKey()).append('\n');
                for (String line : f.getValue()) {
                    if (shown++ == limit) {
                        sb.append("\n... ").append(errors + warnings - limit)
                                .append(" more not shown, raise 'limit' to see them\n");
                        break outer;
                    }
                    sb.append("  ").append(line).append('\n');
                }
            }
        }
        if (problems.length() > 0) {
            sb.append('\n').append(problems);
        }
        return Result.ok(sb.toString());
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    /** Maps document offsets (line ends normalized to \n, as in NetBeans documents) to line:column. */
    private static final class LineIndex {

        private final List<Integer> starts = new ArrayList<>();

        LineIndex(FileObject fo) {
            String text;
            try {
                text = fo.asText(FileEncodingQuery.getEncoding(fo).name());
            } catch (IOException ex) {
                text = "";
            }
            text = text.replace("\r\n", "\n").replace('\r', '\n');
            starts.add(0);
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    starts.add(i + 1);
                }
            }
        }

        String position(int offset) {
            int idx = Collections.binarySearch(starts, offset);
            int line = idx >= 0 ? idx : -idx - 2;
            return (line + 1) + ":" + (offset - starts.get(line) + 1);
        }
    }
}
