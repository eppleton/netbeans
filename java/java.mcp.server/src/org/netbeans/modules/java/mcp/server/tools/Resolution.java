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
import java.util.List;
import java.util.Map;
import org.netbeans.modules.java.mcp.server.Json;
import org.netbeans.modules.java.mcp.server.Tool.Result;
import org.netbeans.modules.java.mcp.server.Workspace;
import org.openide.filesystems.FileObject;

/**
 * The {@code symbol} argument of a tool call resolved against the workspace:
 * either the source roots and the symbol, or an error result to return.
 */
record Resolution(List<FileObject> roots, SymbolResolver.Resolved symbol, Result error) {

    static Resolution of(Workspace workspace, Map<?, ?> arguments, Workspace.Freshness freshness) throws IOException {
        String text = Json.string(arguments, "symbol");
        if (text == null) {
            return failed("'symbol' is required");
        }
        SymbolSpec spec;
        try {
            spec = SymbolSpec.parse(text);
        } catch (IllegalArgumentException ex) {
            return failed(ex.getMessage());
        }
        try {
            List<FileObject> roots = workspace.awaitSourceRoots(freshness);
            return new Resolution(roots, SymbolResolver.resolve(spec, roots), null);
        } catch (Workspace.NotReadyException | SymbolResolver.ResolutionException ex) {
            return failed(ex.getMessage());
        }
    }

    private static Resolution failed(String message) {
        return new Resolution(null, null, Result.error(message));
    }
}
