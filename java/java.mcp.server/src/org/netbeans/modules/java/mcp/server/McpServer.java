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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.json.simple.JSONValue;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.openide.util.RequestProcessor;

/**
 * Model Context Protocol server over the stdio transport: newline-delimited
 * JSON-RPC 2.0 messages on the given streams.
 * <p>
 * Deliberately small and dependency-free (it uses json_simple, which the IDE
 * already ships). Only the {@code tools} capability is implemented. If the
 * protocol surface grows, this class is the one place to replace with the
 * official MCP Java SDK.
 */
public final class McpServer {

    private static final Logger LOG = Logger.getLogger(McpServer.class.getName());

    static final String LATEST_PROTOCOL_VERSION = "2025-11-25";
    static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = Set.of(
            "2024-11-05", "2025-03-26", "2025-06-18", LATEST_PROTOCOL_VERSION);

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;

    private static final String INSTRUCTIONS
            = "Semantic Java code intelligence backed by the NetBeans Java infrastructure "
            + "(the same parser, index and refactoring engine the IDE uses). "
            + "Prefer these tools over grep/text search for Java questions: results are exact "
            + "(overloads, overrides, imports and same-named symbols are told apart) and compact. "
            + "Symbols are written as fully qualified names: com.acme.Foo, com.acme.Foo.Inner, "
            + "com.acme.Foo#bar, com.acme.Foo#bar(String,int), com.acme.Foo#<init>(). "
            + "Use find_symbol when you only know a simple or partial type name. "
            + "Files edited on disk are picked up automatically before each call.";

    private final BufferedReader in;
    private final Writer out;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final RequestProcessor worker = new RequestProcessor("MCP tool calls", 4, true);

    public McpServer(InputStream in, OutputStream out, List<? extends Tool> tools) {
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        for (Tool t : tools) {
            this.tools.put(t.name(), t);
        }
    }

    /**
     * Reads and dispatches messages until the input stream is closed.
     */
    public void run() throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            Object message;
            try {
                message = new JSONParser().parse(line);
            } catch (ParseException ex) {
                sendError(null, PARSE_ERROR, "Parse error: " + ex);
                continue;
            }
            if (message instanceof List<?> batch) {
                for (Object m : batch) {
                    dispatch(m);
                }
            } else {
                dispatch(message);
            }
        }
    }

    private void dispatch(Object message) {
        if (!(message instanceof Map<?, ?> m)) {
            sendError(null, INVALID_REQUEST, "Invalid request");
            return;
        }
        if (!(m.get("method") instanceof String method)) {
            // a response to a server-initiated request; we never send any
            return;
        }
        boolean isNotification = !m.containsKey("id");
        Object id = m.get("id");
        Map<?, ?> params = m.get("params") instanceof Map<?, ?> p ? p : Map.of();
        switch (method) {
            case "initialize" -> sendResult(id, initialize(params));
            case "ping" -> sendResult(id, Map.of());
            case "tools/list" -> sendResult(id, listTools());
            case "tools/call" -> worker.post(() -> callTool(id, params));
            default -> {
                // notifications/initialized, notifications/cancelled, ... need no answer
                if (!isNotification) {
                    sendError(id, METHOD_NOT_FOUND, "Method not found: " + method);
                }
            }
        }
    }

    private Map<String, Object> initialize(Map<?, ?> params) {
        Object requested = params.get("protocolVersion");
        String version = requested instanceof String v && SUPPORTED_PROTOCOL_VERSIONS.contains(v)
                ? v : LATEST_PROTOCOL_VERSION;
        return Json.obj(
                "protocolVersion", version,
                "capabilities", Json.obj("tools", Json.obj("listChanged", false)),
                "serverInfo", Json.obj("name", "netbeans-java", "version", "0.1"),
                "instructions", INSTRUCTIONS);
    }

    private Map<String, Object> listTools() {
        List<Object> list = new ArrayList<>();
        for (Tool t : tools.values()) {
            list.add(Json.obj(
                    "name", t.name(),
                    "description", t.description(),
                    "inputSchema", t.inputSchema()));
        }
        return Json.obj("tools", list);
    }

    private void callTool(Object id, Map<?, ?> params) {
        Object name = params.get("name");
        Tool tool = name instanceof String n ? tools.get(n) : null;
        if (tool == null) {
            sendError(id, INVALID_PARAMS, "Unknown tool: " + name);
            return;
        }
        Map<?, ?> args = params.get("arguments") instanceof Map<?, ?> a ? a : Map.of();
        Tool.Result result;
        try {
            result = tool.call(args);
        } catch (Exception | LinkageError | AssertionError ex) {
            LOG.log(Level.INFO, "Tool " + name + " failed", ex);
            result = Tool.Result.error(tool.name() + " failed: " + ex);
        }
        sendResult(id, Json.obj(
                "content", List.of(Json.obj("type", "text", "text", result.text())),
                "isError", result.error()));
    }

    private void sendResult(Object id, Object result) {
        send(Json.obj("jsonrpc", "2.0", "id", id, "result", result));
    }

    private void sendError(Object id, int code, String message) {
        send(Json.obj("jsonrpc", "2.0", "id", id, "error", Json.obj("code", code, "message", message)));
    }

    private synchronized void send(Map<String, Object> message) {
        try {
            // JSONValue escapes line breaks inside strings, so each message stays on one line
            out.write(JSONValue.toJSONString(message));
            out.write('\n');
            out.flush();
        } catch (IOException ex) {
            LOG.log(Level.INFO, "Cannot write MCP message", ex);
        }
    }
}
