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

Build the IDE (or just the Java cluster), then start NetBeans headless:

```sh
nbbuild/netbeans/bin/netbeans --nogui --nosplash \
    -J-Djava.awt.headless=true -J-Duser.language=en -J-Duser.country=US \
    --jdkhome /path/to/jdk-17+ \
    --userdir  ~/.cache/nb-mcp/myproject/userdir \
    --cachedir ~/.cache/nb-mcp/myproject/cache \
    --start-mcp-server --mcp-workspace /path/to/myproject
```

* Use a **dedicated userdir per workspace**. With a userdir that is already in use,
  the launcher forwards the command line to the running instance instead of starting a new one.
* Create `<userdir>/var/imported` before the first start. Otherwise a fresh userdir offers
  to import the settings of an installed NetBeans, the dialog fails headless and NetBeans exits:
  `mkdir -p <userdir>/var && touch <userdir>/var/imported`.
* The cache directory holds the index; keep it between runs so only the first start is slow.
* `--mcp-workspace` may be a project or a folder containing projects; it defaults to the
  current directory.
* `-J-Duser.language=en -J-Duser.country=US` makes compiler messages English regardless of the
  system locale, which is what agents expect.
* stdout carries the protocol only. Logs go to stderr and `<userdir>/var/log/messages.log`.
* NetBeans exits when the client closes stdin.

### Claude Code

```sh
claude mcp add netbeans -- /path/to/netbeans/bin/netbeans --nogui --nosplash \
    -J-Djava.awt.headless=true -J-Duser.language=en -J-Duser.country=US \
    --userdir ~/.cache/nb-mcp/myproject/userdir --cachedir ~/.cache/nb-mcp/myproject/cache \
    --start-mcp-server --mcp-workspace .
```

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
