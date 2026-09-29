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
 * A tool offered to MCP clients.
 */
public interface Tool {

    /** Tool name as seen by the agent, e.g. {@code find_usages}. */
    String name();

    /**
     * Description shown to the agent. This is the agent's only documentation,
     * so it should say when to prefer the tool and what the input syntax is.
     */
    String description();

    /** JSON Schema of the {@code arguments} object. */
    Map<String, Object> inputSchema();

    /**
     * Executes the tool. Runs on a worker thread, never on the protocol reader thread.
     *
     * @param arguments the parsed {@code arguments} object, never {@code null}
     */
    Result call(Map<?, ?> arguments) throws Exception;

    /**
     * Text result of a tool call. Errors the agent can act on (unknown symbol,
     * workspace still indexing, ...) are returned as results with
     * {@code error == true}, not as protocol errors.
     */
    record Result(String text, boolean error) {

        public static Result ok(String text) {
            return new Result(text, false);
        }

        public static Result error(String text) {
            return new Result(text, true);
        }
    }
}
