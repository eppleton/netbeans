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
import java.util.Map;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.modules.java.mcp.server.Json;
import org.netbeans.modules.java.mcp.server.Tool;
import org.netbeans.modules.java.mcp.server.Workspace;
import org.openide.filesystems.FileObject;

/**
 * Reports what the server has opened and whether it is ready.
 */
public final class WorkspaceStatusTool implements Tool {

    private final Workspace workspace;

    public WorkspaceStatusTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "workspace_status";
    }

    @Override
    public String description() {
        return "Show the state of the Java workspace: opened projects, Java source roots and whether "
                + "indexing is still running. Call this if other tools report the workspace is not ready.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Json.obj("type", "object", "properties", Json.obj());
    }

    @Override
    public Result call(Map<?, ?> arguments) {
        StringBuilder sb = new StringBuilder();
        sb.append("Workspace: ").append(workspace.getRoot()).append('\n');
        sb.append("State: ").append(workspace.getPhase())
                .append(workspace.isIndexing() ? " (indexing in progress)" : "").append('\n');
        List<Project> projects = workspace.getProjects();
        if (!projects.isEmpty()) {
            sb.append("Projects:\n");
            for (Project p : projects) {
                sb.append("  ").append(ProjectUtils.getInformation(p).getDisplayName())
                        .append("  ").append(workspace.displayPath(p.getProjectDirectory())).append('\n');
            }
            sb.append("Java source roots:\n");
            for (FileObject root : Workspace.javaSourceRoots(projects)) {
                sb.append("  ").append(workspace.displayPath(root)).append('\n');
            }
        }
        return Result.ok(sb.toString());
    }
}
