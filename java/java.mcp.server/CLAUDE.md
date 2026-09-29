# java.mcp.server — NetBeans Java tools for coding agents via MCP

## Goal

A headless NetBeans that serves the IDE's Java intelligence (index, javac-based
attribution, refactoring engine, Jackpot hints) to **any** coding agent over the
Model Context Protocol: Claude Code, Copilot agent mode, Codex, Cursor, omp, ...
The agent keeps editing files itself; this server answers semantic questions and
performs refactorings that are error-prone as text edits.

Our real competitor is "agent + jdtls via LSP". The value has to come from what
LSP does badly: symbol-addressed queries (no cursor positions), refactorings with
structured problems, fast diagnostics without a build, and pattern-based batch
transformations (Jackpot).

## Where we are

Module scaffold lives here, in a checkout of apache/netbeans on branch `mcp-server`
(our fork's working branch, based on apache/netbeans master; rebase onto master, don't merge).
The only change outside the module is one line registering it in
`nbbuild/cluster.properties` (java cluster). Read `README.md` for usage.

Done and verified by compilation + unit tests + a stdio protocol smoke test:
- `McpArgsProcessor`: `--start-mcp-server [stdio] --mcp-workspace <dir>`, redirects `System.out` to stderr, exits NetBeans when stdin closes.
- `McpServer`: hand-written MCP (JSON-RPC 2.0, newline-delimited, stdio) on json_simple. Tools capability only.
- `Workspace`: opens projects like `java.lsp.server`'s `Server.asyncOpenSelectedProjects1` (priming build, contained projects, `OpenProjects`, wait for scan); refreshes the folder and waits for scans before each tool call.
- Tools: `find_symbol` (ClassIndex; `Type#member` parses only the matching types' files), `find_usages` and
  `find_implementations` (WhereUsedQuery, shared in `WhereUsed`), `outline`, `diagnostics`, `workspace_status`.
- `SymbolSpec` / `SymbolResolver`: symbol syntax `com.acme.Foo`, `com.acme.Foo.Inner`, `com.acme.Foo#bar(String,int)`, `com.acme.Foo#<init>()`, `com.acme.Foo#field`. Tools output canonical signatures in the same syntax so agents can copy them.

**M0 done**: `test/smoke/smoke.sh` runs the server headless end to end and passes
(overloads told apart, overriding methods, implicit constructors, nested classes, test
sources, an `echo >>` edit picked up by the next call, NetBeans exits when stdin closes).
Learned on the way:
- A fresh userdir makes the launcher's `AutoUpgrade` offer to import settings of an installed
  NetBeans; the dialog throws `HeadlessException` and NetBeans exits with status 0 before
  the server starts. Create `<userdir>/var/imported` first (smoke.sh does; the M5 launcher must).
- `--start-mcp-server --mcp-workspace <dir>` parses as intended (optional argument defaults to `stdio`).
- json_simple escapes `/` as `\/` in output; valid JSON, but grep-based checks must account for it.
- macOS ships bash 3.2: no `coproc`, so smoke.sh talks to the server through FIFOs.

**M1 done** (smoke test covers every tool). Decisions and findings:
- `diagnostics` without `files` asks `ErrorsCache.getAllFilesInError` (public, parsing.indexing) which
  files the index marked as broken and runs `ErrorProvider` (api.lsp, via MimeLookup `text/x-java`,
  implemented in java.hints) only on those. Fast for any workspace size, and since every call refreshes
  and waits for the scan, a separate "changed since last call" mode was unnecessary.
- `ErrorProvider` offsets are document offsets (line ends normalized to `\n`); `DiagnosticsTool.LineIndex`
  maps them the same way. javac reports "cannot find symbol" at the identifier, not the dot.
- Compiler messages follow the JVM locale; pass `-J-Duser.language=en -J-Duser.country=US` (README,
  smoke.sh; the M5 launcher should default to it).
- Line numbers of declarations are the start of the declaration including annotations (`@Override`
  line). Implicit members (default constructors, ...) have origin `MANDATED` and are shown as `(implicit)`
  without a line.
- `SourcePositions.getStartPosition(CompilationUnitTree, Tree)` is deprecated in the nb-javac we build
  against but its replacement is not in the API jar; `SymbolResolver.line` suppresses the warning.

## Build, run, test

```sh
# from the repo root; the IDE is already built into nbbuild/netbeans
ant -f java/java.mcp.server/build.xml netbeans     # build + install module into nbbuild/netbeans
ant -f java/java.mcp.server/build.xml test          # unit tests

nbbuild/netbeans/bin/netbeans --nogui --nosplash \
    --userdir /tmp/nbmcp/ud --cachedir /tmp/nbmcp/cache \
    --start-mcp-server --mcp-workspace <maven-project>
```

- End-to-end check: `java/java.mcp.server/test/smoke/smoke.sh` (generates a Maven project under
  `$SMOKE_DIR`, default `$TMPDIR/nbmcp-smoke`; prints every request/response; exit code 0 = pass).
- Use a dedicated userdir; otherwise the launcher forwards the command line to a running NetBeans.
  Touch `<userdir>/var/imported` before the first start (see M0 notes above).
- Logs: stderr and `<userdir>/var/log/messages.log`.
- JDK 17+ required (`--jdkhome` if needed).

## Milestones

Work through these in order. Each ends with something verified end to end, not just compiled.

### M0 — Headless end-to-end run
1. Create a small Maven test project outside the repo (2–3 classes, an interface with two implementations, overloaded methods, a nested class, a usage in a test class).
2. Write `test/smoke/smoke.sh`: launches the server on that project, pipes `initialize`, `notifications/initialized`, `tools/list`, `workspace_status`, `find_symbol`, `find_usages` as newline-delimited JSON, prints responses. Keep stdin open until responses arrive (e.g. a FIFO or `sleep` in a subshell).
3. Fix whatever breaks. Likely suspects: option parsing of `--start-mcp-server` (it is an *optional-argument* option; check whether `--start-mcp-server --mcp-workspace x` parses as intended, else switch to `defaultValue`-less `--start-mcp-server=stdio` or a plain `@Arg` flag), stdout pollution, projects opening before modules are ready, `LifecycleManager.exit()` from the CLI thread, scan-wait deadlocks.
4. Verify that an external edit (append a usage with `echo >>`) shows up in the next `find_usages`.

### M1 — Read tools that pay off immediately
- `diagnostics(files?: string[])`: compile errors (and optionally warnings) for given files or all files changed since the last call; default: whole workspace errors only, capped. Look at how `TextDocumentServiceImpl.computeDiags` uses `org.netbeans.spi.lsp.ErrorProvider` (`ide/api.lsp`, public API) looked up per MIME type; reuse that rather than raw javac.
- `outline(symbol_or_file)`: members of a type with canonical signatures, modifiers and line numbers, no bodies.
- `find_implementations(symbol)`: subtypes / overriding methods (`WhereUsedQueryConstants.FIND_SUBCLASSES`, `FIND_OVERRIDING_METHODS`, `FIND_REFERENCES=false`).
- Extend `find_symbol` to members (`query` like `Order#save`) if cheap.

### M2 — First write operation: `rename`
- Input: `symbol`, `new_name`. Output: problems (fatal ones abort), then a unified diff of all changed files, plus file renames.
- Mechanics: see `TextDocumentServiceImpl.rename` (~line 1540). It builds the edit list through friend accessors (`APIAccessor`, `SPIAccessor`, `JavaModificationResult`). Two options:
  - (a) public API only: `session.doRefactoring(true)`, then diff before/after file contents. Git is the undo. **Start here.**
  - (b) true dry-run preview via the friend accessors. Needs this module added to friend lists of `refactoring.api` / `refactoring.java`. Only do it if (a) proves insufficient, and ask the user first (it touches other modules).
- Files open/modified in memory must be saved after refactoring (`SaveCookie` / `LifecycleManager.saveAll()`), otherwise the agent sees stale disk content.

### M3 — More refactorings
`move` (class to package), `change_signature`, `safe_delete` (report blocking usages instead of failing silently), `inline`. Same output contract as `rename`.

### M4 — Jackpot `apply_rule`
Input: a declarative hint rule (`$a.equals($b) :: $a instanceof String => java.util.Objects.equals($a, $b);;`), optional scope. Output: matches count + unified diff, applied like M2(a). Batch search/apply lives in `java/java.hints` (`spiimpl.batch`), friend API → same rule as M2(b): ask before touching friend lists. Put a short rule-syntax cheat sheet into the tool description.

### M5 — Packaging and evidence
- Launcher script `nb-mcp`: finds NetBeans install and JDK, derives per-workspace userdir/cachedir under `~/.cache/nb-mcp/<hash>`, passes the rest through.
- Config snippets in README for Claude Code, VS Code/Copilot, Codex, Cursor, omp.
- Small eval: same tasks (find all callers, rename across modules, remove a deprecated method, API migration) done by an agent with vs. without the server. Record correctness, turns, tokens.

## Design rules

- **Any agent, any model.** Tools only, stdio only, plain-text results. No sampling, elicitation or resources. Descriptions must say when to prefer the tool over grep and show the symbol syntax; don't rely on the model inferring it.
- **Few tools.** Aim for ≤ 12 in total. Clients cap tool counts and every description costs context.
- **Symbol-addressed.** Inputs by canonical symbol (file/line only as an optional fallback). Outputs use canonical signatures.
- **Compact, bounded output.** Group by file, `line: source line`, cap with a `limit` argument and say how many were omitted.
- **Errors are results.** Anything the agent can act on (unknown symbol, ambiguous overload with candidates, still indexing) is returned as `isError: true` text with a hint, not as a JSON-RPC error.
- **Fresh before answering.** Every tool calls `Workspace.awaitSourceRoots(...)` first.
- **Protocol layer stays swappable.** Tools must not depend on `McpServer` internals, so the official MCP Java SDK can replace it later.

## NetBeans repo conventions

- Every new file needs the Apache license header (copy from an existing file); RAT checks enforce it.
- Java 17 (`javac.release=17`), `-Xlint` clean. Match the surrounding code style.
- Dependencies go into `nbproject/project.xml` with specification versions. Prefer public APIs.
- **Do not modify other modules** (friend lists, refactoring of `java.lsp.server`, ...) without asking the user first. Keeping the diff outside this module minimal keeps rebasing easy and a later upstream PR reviewable.
- `java.lsp.server` is the reference for how to do things headless: `LspArgsProcessor`, `ConnectionSpec`, `protocol/Server.java` (project opening), `protocol/TextDocumentServiceImpl.java` (usages, rename, diagnostics), `protocol/WorkspaceServiceImpl.java`.
- Commit in small steps on `mcp-server` with descriptive messages; don't push.
