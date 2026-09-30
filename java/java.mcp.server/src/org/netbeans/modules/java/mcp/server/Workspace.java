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
package org.netbeans.modules.java.mcp.server;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.netbeans.api.java.project.JavaProjectConstants;
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectManager;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.api.project.SourceGroup;
import org.netbeans.api.project.Sources;
import org.netbeans.api.project.ui.OpenProjects;
import org.netbeans.modules.parsing.api.indexing.IndexingManager;
import org.netbeans.spi.project.ActionProgress;
import org.netbeans.spi.project.ActionProvider;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.modules.Places;
import org.openide.util.Lookup;
import org.openide.util.RequestProcessor;
import org.openide.util.lookup.Lookups;

/**
 * The folder the MCP server works on. Opens the project(s) found there the way
 * the Java LSP server does (priming build, contained projects,
 * {@link OpenProjects}) so that class paths get registered and indexed, then
 * tracks readiness for the tools.
 */
public final class Workspace {

    private static final Logger LOG = Logger.getLogger(Workspace.class.getName());
    private static final RequestProcessor RP = new RequestProcessor(Workspace.class.getName(), 1);
    private static final long PRIMING_TIMEOUT_MINUTES = 15;
    /** How long a tool call waits for the workspace before reporting that it is not ready. */
    private static final long WAIT_MINUTES = 5;
    /** Written to the cache directory once the index is complete, so the next start knows it is warm. */
    private static final String INDEXED_MARKER = "nb-mcp/indexed";

    /**
     * How fresh the index must be for a tool call.
     */
    public enum Freshness {
        /**
         * Read queries: after a restart the index of the previous run may be used while NetBeans
         * verifies it; changes made on disk while the server was not running may be missing then.
         */
        INDEXED,
        /** Refactorings and diagnostics: wait until the index is verified and up to date. */
        CURRENT
    }

    private final File root;
    /** Projects opened (before the initial scan). */
    private final CompletableFuture<List<Project>> opened = new CompletableFuture<>();
    /** The initial scan finished. */
    private final CompletableFuture<Void> scanned = new CompletableFuture<>();
    private final ThreadLocal<Boolean> answeredUnverified = new ThreadLocal<>();
    private volatile boolean warmStart;
    private volatile String phase = "starting";

    public Workspace(File root) {
        this.root = FileUtil.normalizeFile(root);
    }

    public File getRoot() {
        return root;
    }

    /** Human-readable state: starting, opening projects, indexing, ready or failed. */
    public String getPhase() {
        return phase;
    }

    public boolean isIndexing() {
        return IndexingManager.getDefault().isIndexing();
    }

    /**
     * Whether the index is still being verified after a restart; read queries are then answered
     * from the index of the previous run.
     */
    public boolean isVerifyingWarmIndex() {
        return warmStart && !scanned.isDone();
    }

    /** Projects opened for this workspace, or an empty list while still opening. */
    public List<Project> getProjects() {
        return opened.isDone() && !opened.isCompletedExceptionally() ? opened.join() : List.of();
    }

    public void openAsync() {
        RP.post(this::open);
    }

    private void open() {
        try {
            FileObject dir = FileUtil.toFileObject(root);
            if (dir == null || !dir.isFolder()) {
                throw new IOException("Workspace folder does not exist: " + root);
            }
            phase = "looking for projects";
            Set<Project> projects = new LinkedHashSet<>();
            collectProjects(dir, projects);
            if (projects.isEmpty()) {
                throw new IOException("No project (Maven, Gradle, Ant) found in " + root);
            }
            phase = "priming projects (resolving dependencies)";
            prime(projects);
            phase = "opening projects";
            OpenProjects.getDefault().open(projects.toArray(Project[]::new), false);
            OpenProjects.getDefault().openProjects().get();
            for (Project p : projects) {
                // initializes source groups and FileOwnerQuery, like the LSP server does
                ProjectUtils.getSources(p).getSourceGroups(Sources.TYPE_GENERIC);
            }
            File marker = Places.getCacheSubfile(INDEXED_MARKER);
            warmStart = marker.isFile();
            opened.complete(List.copyOf(projects));
            phase = warmStart ? "verifying the index of the previous run (read queries are answered meanwhile)" : "indexing";
            List<FileObject> roots = javaSourceRoots(projects);
            waitForScan(roots, 0);
            if (!marker.isFile()) {
                marker.createNewFile();
            }
            scanned.complete(null);
            phase = "ready";
            LOG.log(Level.INFO, "MCP workspace {0} ready: {1} projects, {2} Java source roots",
                    new Object[]{root, projects.size(), roots.size()});
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Cannot open MCP workspace " + root, t);
            phase = "failed: " + t.getMessage();
            opened.completeExceptionally(t);
            scanned.completeExceptionally(t);
        }
    }

    private static void collectProjects(FileObject dir, Set<Project> into) throws IOException {
        Project p = ProjectManager.getDefault().findProject(dir);
        if (p != null) {
            into.add(p);
            Set<Project> contained = ProjectUtils.getContainedProjects(p, true);
            if (contained != null) {
                into.addAll(contained);
            }
            return;
        }
        // not a project itself: accept projects one level down (a folder of checkouts)
        for (FileObject child : dir.getChildren()) {
            if (child.isFolder() && !child.getNameExt().startsWith(".")) {
                Project cp = ProjectManager.getDefault().findProject(child);
                if (cp != null) {
                    into.add(cp);
                    Set<Project> contained = ProjectUtils.getContainedProjects(cp, true);
                    if (contained != null) {
                        into.addAll(contained);
                    }
                }
            }
        }
    }

    /** Runs the priming build (e.g. Maven dependency download) where a project offers one. */
    private static void prime(Set<Project> projects) {
        List<CompletableFuture<Void>> builds = new ArrayList<>();
        for (Project p : projects) {
            ActionProvider ap = p.getLookup().lookup(ActionProvider.class);
            if (ap == null
                    || !Arrays.asList(ap.getSupportedActions()).contains(ActionProvider.COMMAND_PRIME)
                    || !ap.isActionEnabled(ActionProvider.COMMAND_PRIME, Lookup.EMPTY)) {
                continue;
            }
            CompletableFuture<Void> done = new CompletableFuture<>();
            builds.add(done);
            ap.invokeAction(ActionProvider.COMMAND_PRIME, Lookups.fixed(new ActionProgress() {
                @Override
                protected void started() {
                }

                @Override
                public void finished(boolean success) {
                    if (!success) {
                        LOG.log(Level.WARNING, "Priming build failed for {0}", p.getProjectDirectory());
                    }
                    done.complete(null);
                }
            }));
        }
        try {
            CompletableFuture.allOf(builds.toArray(CompletableFuture[]::new))
                    .get(PRIMING_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (InterruptedException | ExecutionException | TimeoutException ex) {
            LOG.log(Level.WARNING, "Priming did not finish, continuing anyway", ex);
        }
    }

    /**
     * Waits until the scan of the roots is finished.
     *
     * @param timeoutMillis maximum wait, 0 for no limit
     */
    private static void waitForScan(List<FileObject> roots, long timeoutMillis)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        if (roots.isEmpty()) {
            return;
        }
        JavaSource js = JavaSource.create(ClasspathInfo.create(roots.get(0)));
        if (js != null) {
            Future<Void> done = js.runWhenScanFinished(cc -> { }, true);
            if (timeoutMillis > 0) {
                done.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } else {
                done.get();
            }
        }
    }

    public static List<FileObject> javaSourceRoots(Iterable<Project> projects) {
        Set<FileObject> roots = new LinkedHashSet<>();
        for (Project p : projects) {
            for (SourceGroup g : ProjectUtils.getSources(p).getSourceGroups(JavaProjectConstants.SOURCES_TYPE_JAVA)) {
                roots.add(g.getRootFolder());
            }
        }
        return List.copyOf(roots);
    }

    /**
     * Waits until the workspace is open and indexed as fresh as the call
     * needs, picks up changes made on disk by the agent, and returns the Java
     * source roots. Waits up to {@value #WAIT_MINUTES} minutes.
     *
     * @throws NotReadyException if the workspace is not ready in time
     */
    public List<FileObject> awaitSourceRoots(Freshness freshness) throws NotReadyException {
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(WAIT_MINUTES);
        List<Project> projects;
        try {
            projects = opened.get(WAIT_MINUTES, TimeUnit.MINUTES);
        } catch (TimeoutException ex) {
            throw notReady();
        } catch (InterruptedException | ExecutionException ex) {
            throw new NotReadyException("The workspace could not be opened: " + phase);
        }
        List<FileObject> roots = javaSourceRoots(projects);
        // the agent edits files directly on disk; make NetBeans notice before answering
        FileUtil.refreshFor(root);
        if (freshness == Freshness.INDEXED && isVerifyingWarmIndex()) {
            // the index of the previous run is complete; NetBeans only checks it for changes
            answeredUnverified.set(Boolean.TRUE);
            return roots;
        }
        try {
            scanned.get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            // the refresh above may have started another scan
            waitForScan(roots, Math.max(1, deadline - System.currentTimeMillis()));
        } catch (TimeoutException ex) {
            throw notReady();
        } catch (IOException | InterruptedException | ExecutionException ex) {
            LOG.log(Level.INFO, "Waiting for scan failed", ex);
        }
        return roots;
    }

    private NotReadyException notReady() {
        return new NotReadyException("The workspace is not ready yet (" + phase + ") after waiting "
                + WAIT_MINUTES + " minutes. The first start of a large workspace indexes everything, "
                + "later starts are faster; retry, or call workspace_status to see the progress.");
    }

    /**
     * Starts tracking a tool call on the current thread.
     */
    public void beginCall() {
        answeredUnverified.remove();
    }

    /**
     * Whether the tool call on the current thread was answered from the index
     * of the previous run while NetBeans was still verifying it.
     */
    public boolean endCallAnsweredUnverified() {
        boolean unverified = Boolean.TRUE.equals(answeredUnverified.get());
        answeredUnverified.remove();
        return unverified;
    }

    /**
     * Finds a file given relative to the workspace root or as an absolute path.
     *
     * @return the file, or {@code null} if it does not exist
     */
    public FileObject findFile(String path) {
        File f = new File(path);
        if (!f.isAbsolute()) {
            f = new File(root, path);
        }
        return FileUtil.toFileObject(FileUtil.normalizeFile(f));
    }

    /** Returns the path of a file relative to the workspace, or its absolute path outside of it. */
    public String displayPath(FileObject fo) {
        FileObject rootFo = FileUtil.toFileObject(root);
        if (fo.equals(rootFo)) {
            return ".";
        }
        String rel = rootFo != null ? FileUtil.getRelativePath(rootFo, fo) : null;
        return rel != null ? rel : fo.getPath();
    }

    public static final class NotReadyException extends Exception {

        public NotReadyException(String message) {
            super(message);
        }
    }
}
