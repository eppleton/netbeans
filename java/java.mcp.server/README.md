<!--

    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.

-->

# Java MCP Server (prototype)

Headless [Model Context Protocol](https://modelcontextprotocol.io) server that gives
coding agents semantic access to the NetBeans Java infrastructure: the same parser,
index and refactoring engine the IDE and the Java LSP server use.

## Tools

| Tool | What it does |
|---|---|
| `find_symbol` | Types declared in the workspace by prefix, glob or partially qualified name |
| `find_usages` | Semantic references to a type, method, constructor or field (`WhereUsedQuery`) |
| `find_implementations` | Subtypes of a type (all or direct) or overriding methods of a method |
| `outline` | Members of a type or file: modifiers, canonical signatures, declared types, line numbers |
| `diagnostics` | Compile errors (optionally warnings) of given files or the whole workspace, without a build |
| `rename` | Renames a type, method or field with the refactoring engine; writes to disk, returns a unified diff |
| `move` | Moves a top-level class to another package, updating package declaration, imports and references |
| `change_signature` | Adds, removes, reorders, renames or retypes parameters, or changes name, return type or visibility of a method; updates callers and overriders |
| `safe_delete` | Deletes a type or member only if it is unused; otherwise lists the blocking usages |
| `inline` | Inlines a method or constant into all usages and removes it |
| `apply_rule` | Structural search, or search and replace with a NetBeans declarative hint rule (Jackpot) across the workspace; dry run and path scope supported |

All refactoring tools write their changes to disk and return warnings plus a unified diff (`git diff`
format). Fatal problems stop them before anything changes.
| `workspace_status` | Opened projects, source roots, indexing state |

Symbols are addressed by name, not by cursor position:

```
com.acme.Foo                    com.acme.Foo.Inner  /  com.acme.Foo$Inner
com.acme.Foo#count              com.acme.Foo#bar(String,int)
com.acme.Foo#<init>()           com.acme.Foo#Foo(String)
```

## Running

Build NetBeans (at least the Java cluster, which contains this module). The build installs the
launcher `nb-mcp` next to the module, in `<netbeans>/java/bin/nb-mcp` (for a source build:
`nbbuild/netbeans/java/bin/nb-mcp`). It starts NetBeans headless as an MCP server on stdio:

```sh
nb-mcp /path/to/project        # a Maven/Gradle/Ant project or a folder of projects; default: current directory
nb-mcp --check /path/to/project  # only print which NetBeans, JDK and data directory would be used
```

* A JDK 17 or newer is needed. `nb-mcp` takes `--jdk`, `$NB_MCP_JDK`, `$JAVA_HOME`, the `java` on
  the `PATH` or (macOS) `/usr/libexec/java_home`, in this order.
* Every workspace gets its own NetBeans userdir and index cache under `~/.cache/nb-mcp`
  (`--data` / `$NB_MCP_DATA`). The first start indexes the project and the JDK and resolves Maven
  dependencies, which can take minutes on a large project; tools report "not ready" until then.
  Later starts reuse the index: read tools (`find_*`, `outline`, `apply_rule` searches) answer
  right away from the previous run's index while NetBeans checks it for changes (the answer says
  so); refactorings and `diagnostics` wait for that check, which takes about 100 seconds on
  NetBeans' own `java/` folder (160 modules). Tool calls wait up to 5 minutes before they report
  "not ready".
* Compiler messages are English (`--locale` to change).
* The server exits when the client closes stdin. Logs: stderr and
  `<data>/<workspace>-<id>/userdir/var/log/messages.log`.
* Other NetBeans instances, including a running IDE, are not affected.

In the snippets below, replace `/path/to/nb-mcp` with the launcher's absolute path. Clients start
the server in the project directory, so no workspace argument is needed; give one if your client
starts servers elsewhere.

### Claude Code

```sh
claude mcp add netbeans -- /path/to/nb-mcp
```

or, shared with the team, `.mcp.json` in the project:

```json
{ "mcpServers": { "netbeans": { "command": "/path/to/nb-mcp", "args": [] } } }
```

Allow more startup time and long tool calls (a refactoring right after a restart waits for
the index check): `MCP_TIMEOUT=120000 MCP_TOOL_TIMEOUT=600000 claude`.

### VS Code (GitHub Copilot agent mode)

`.vscode/mcp.json`:

```json
{ "servers": { "netbeans": { "type": "stdio", "command": "/path/to/nb-mcp", "args": ["${workspaceFolder}"] } } }
```

### Codex CLI

`~/.codex/config.toml`:

```toml
[mcp_servers.netbeans]
command = "/path/to/nb-mcp"
args = []
startup_timeout_sec = 120
```

### Cursor

`.cursor/mcp.json` in the project:

```json
{ "mcpServers": { "netbeans": { "command": "/path/to/nb-mcp", "args": [] } } }
```

### omp (Oh My Pi)

`.omp/mcp.json` in the project (omp also reads `.mcp.json`, `.cursor/` and `.vscode/` files):

```json
{ "mcpServers": { "netbeans": { "command": "/path/to/nb-mcp", "args": [] } } }
```

### Without the launcher

```sh
mkdir -p <userdir>/var && touch <userdir>/var/imported
<netbeans>/bin/netbeans --nogui --nosplash \
    -J-Djava.awt.headless=true -J-Duser.language=en -J-Duser.country=US \
    --jdkhome /path/to/jdk-17+ --userdir <userdir> --cachedir <cachedir> \
    --start-mcp-server --mcp-workspace /path/to/project
```

* Use a dedicated userdir per workspace. With a userdir that is already in use, the launcher
  forwards the command line to the running instance instead of starting a new one.
* Without `<userdir>/var/imported`, a fresh userdir offers to import the settings of an installed
  NetBeans; the dialog fails headless and NetBeans exits before the server starts.
* stdout carries the protocol only.

## Evaluation

`test/eval/run.sh` measures whether the server helps: it runs four tasks with Claude Code in headless
mode, once with only its built-in tools and once with this server added, each on a fresh copy of a
generated two-module project full of traps for text search (overloads, same-named methods of
unrelated classes, names in comments and strings, `String` next to `StringBuilder`):

| Task | Checked by |
|---|---|
| 01 find the callers of one overload | exact `path:line` list, no source changes |
| 02 rename a method across modules | compiles, only the right `apply` renamed |
| 03 remove a deprecated method, migrate callers | compiles, tests pass, no call left |
| 04 `String.length() == 0` to `isEmpty()` | compiles, `StringBuilder`, arrays and collections untouched |

It records correctness, turns, tokens, cost, time and the tools the agent called per run (the
session transcripts are kept as stream-json; `--reps N` for repetitions) and prints a Markdown
summary. Results so far: [test/eval/RESULTS.md](test/eval/RESULTS.md). **Every run is a real Claude Code session on your login and costs
tokens.** The checks were validated by solving all tasks with the server's own tools.

## Design notes

* The protocol layer (`McpServer`) is a small JSON-RPC-over-stdio implementation on top of
  json_simple, so the module needs no new external libraries. Only the tools capability is
  implemented. If more of the protocol is needed it can be replaced by the official MCP Java SDK
  without touching the tools.
* Projects are opened like the Java LSP server does it: priming build, contained projects,
  `OpenProjects`, then waiting for the initial scan.
* Before every tool call the workspace folder is refreshed and pending scans are awaited, so
  edits the agent made on disk are reflected in the results.
* The module uses public APIs only; no friend-list changes in other modules are required.
