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
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import org.netbeans.api.sendopts.CommandException;
import org.netbeans.modules.java.mcp.server.tools.ApplyRuleTool;
import org.netbeans.modules.java.mcp.server.tools.ChangeSignatureTool;
import org.netbeans.modules.java.mcp.server.tools.DiagnosticsTool;
import org.netbeans.modules.java.mcp.server.tools.FindImplementationsTool;
import org.netbeans.modules.java.mcp.server.tools.FindSymbolTool;
import org.netbeans.modules.java.mcp.server.tools.FindUsagesTool;
import org.netbeans.modules.java.mcp.server.tools.InlineTool;
import org.netbeans.modules.java.mcp.server.tools.MoveTool;
import org.netbeans.modules.java.mcp.server.tools.OutlineTool;
import org.netbeans.modules.java.mcp.server.tools.RenameTool;
import org.netbeans.modules.java.mcp.server.tools.SafeDeleteTool;
import org.netbeans.modules.java.mcp.server.tools.WorkspaceStatusTool;
import org.netbeans.spi.sendopts.Arg;
import org.netbeans.spi.sendopts.ArgsProcessor;
import org.netbeans.spi.sendopts.Description;
import org.netbeans.spi.sendopts.Env;
import org.openide.LifecycleManager;
import org.openide.util.NbBundle.Messages;

/**
 * Command line entry point:
 * {@code netbeans --nogui --start-mcp-server [stdio] [--mcp-workspace <dir>]}.
 * The call blocks until the client closes stdin, then shuts NetBeans down.
 */
public final class McpArgsProcessor implements ArgsProcessor {

    @Arg(longName = "start-mcp-server", defaultValue = "stdio")
    @Description(shortDescription = "#DESC_StartMcpServer")
    @Messages("DESC_StartMcpServer=Starts a Model Context Protocol (MCP) server for Java on stdio")
    public String transport;

    @Arg(longName = "mcp-workspace")
    @Description(shortDescription = "#DESC_McpWorkspace")
    @Messages("DESC_McpWorkspace=Folder with the project(s) the MCP server works on; defaults to the current directory")
    public String workspace;

    @Messages({
        "# {0} - transport",
        "ERR_UnsupportedTransport=Unsupported MCP transport ''{0}'', only ''stdio'' is available"
    })
    @Override
    public void process(Env env) throws CommandException {
        if (transport == null) {
            return;
        }
        if (!transport.isEmpty() && !"stdio".equals(transport)) {
            throw new CommandException(570, Bundle.ERR_UnsupportedTransport(transport));
        }
        File dir = workspace == null ? env.getCurrentDirectory() : new File(workspace);
        if (!dir.isAbsolute()) {
            dir = new File(env.getCurrentDirectory(), workspace);
        }

        InputStream protocolIn = env.getInputStream();
        OutputStream protocolOut = env.getOutputStream();
        // stdout now belongs to the protocol: anything else printed there would corrupt
        // the JSON-RPC stream, so send stray System.out output to stderr instead
        System.setOut(System.err);

        Workspace ws = new Workspace(dir);
        ws.openAsync();
        List<Tool> tools = List.of(
                new FindSymbolTool(ws),
                new FindUsagesTool(ws),
                new FindImplementationsTool(ws),
                new OutlineTool(ws),
                new DiagnosticsTool(ws),
                new RenameTool(ws),
                new MoveTool(ws),
                new ChangeSignatureTool(ws),
                new SafeDeleteTool(ws),
                new InlineTool(ws),
                new ApplyRuleTool(ws),
                new WorkspaceStatusTool(ws));
        McpServer server = new McpServer(protocolIn, protocolOut,
                tools.stream().map(t -> (Tool) new WorkspaceTool(t, ws)).toList());
        try {
            server.run();
        } catch (IOException ex) {
            throw (CommandException) new CommandException(571).initCause(ex);
        } finally {
            // the client went away; don't leave a headless NetBeans running
            LifecycleManager.getDefault().exit();
        }
    }
}
