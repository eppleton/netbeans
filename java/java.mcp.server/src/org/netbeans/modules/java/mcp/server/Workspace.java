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

    private final File root;
    private final CompletableFuture<List<Project>> opened = new CompletableFuture<>();
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
            phase = "indexing";
            List<FileObject> roots = javaSourceRoots(projects);
            waitForScan(roots);
            phase = "ready";
            LOG.log(Level.INFO, "MCP workspace {0} ready: {1} projects, {2} Java source roots",
                    new Object[]{root, projects.size(), roots.size()});
            opened.complete(List.copyOf(projects));
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Cannot open MCP workspace " + root, t);
            phase = "failed: " + t.getMessage();
            opened.completeExceptionally(t);
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

    private static void waitForScan(List<FileObject> roots) throws IOException, InterruptedException, ExecutionException {
        if (roots.isEmpty()) {
            return;
        }
        JavaSource js = JavaSource.create(ClasspathInfo.create(roots.get(0)));
        if (js != null) {
            js.runWhenScanFinished(cc -> { }, true).get();
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
     * Waits until the workspace is open and indexed, picks up changes made on
     * disk by the agent, and returns the Java source roots.
     *
     * @throws NotReadyException if the workspace is not ready within the timeout
     */
    public List<FileObject> awaitSourceRoots(long timeout, TimeUnit unit) throws NotReadyException {
        List<Project> projects;
        try {
            projects = opened.get(timeout, unit);
        } catch (TimeoutException ex) {
            throw new NotReadyException("The workspace is not ready yet (" + phase + "). "
                    + "Large projects take a while on first start; retry in a moment or call workspace_status.");
        } catch (InterruptedException | ExecutionException ex) {
            throw new NotReadyException("The workspace could not be opened: " + phase);
        }
        List<FileObject> roots = javaSourceRoots(projects);
        // the agent edits files directly on disk; make NetBeans notice before answering
        FileUtil.refreshFor(root);
        try {
            waitForScan(roots);
        } catch (IOException | InterruptedException | ExecutionException ex) {
            LOG.log(Level.INFO, "Waiting for scan failed", ex);
        }
        return roots;
    }

    /** Returns the path of a file relative to the workspace, or its absolute path outside of it. */
    public String displayPath(FileObject fo) {
        FileObject rootFo = FileUtil.toFileObject(root);
        String rel = rootFo != null ? FileUtil.getRelativePath(rootFo, fo) : null;
        return rel != null ? rel : fo.getPath();
    }

    public static final class NotReadyException extends Exception {

        public NotReadyException(String message) {
            super(message);
        }
    }
}
