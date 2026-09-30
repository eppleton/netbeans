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

import java.util.Map;

/**
 * Wraps a tool so that its answer says when it came from the index of the
 * previous run while NetBeans was still verifying it after a restart.
 */
final class WorkspaceTool implements Tool {

    static final String UNVERIFIED_NOTE = "\n\nNote: answered from the index of the previous run while NetBeans is "
            + "still checking it after startup; changes made on disk while the server was not running may be "
            + "missing. Repeat the call later for verified results.";

    private final Tool delegate;
    private final Workspace workspace;

    WorkspaceTool(Tool delegate, Workspace workspace) {
        this.delegate = delegate;
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String description() {
        return delegate.description();
    }

    @Override
    public Map<String, Object> inputSchema() {
        return delegate.inputSchema();
    }

    @Override
    public Result call(Map<?, ?> arguments) throws Exception {
        // calls run concurrently on worker threads; the workspace tracks each on its own thread
        workspace.beginCall();
        Result result;
        boolean unverified;
        try {
            result = delegate.call(arguments);
        } finally {
            unverified = workspace.endCallAnsweredUnverified();
        }
        return unverified ? new Result(result.text() + UNVERIFIED_NOTE, result.error()) : result;
    }
}
