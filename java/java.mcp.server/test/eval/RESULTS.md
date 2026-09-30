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

# Evaluation results

## 2026-09-30: 4 tasks × 2 modes × 3 repetitions

Claude Code 2.1.285 in headless mode (`claude -p`), model `claude-opus-5-5`, on the generated
fixture (`fixture.sh`), NetBeans index warmed up before the timed runs. `baseline` has Claude Code's
built-in tools only (Read, Edit, Write, Glob, Grep, restricted Bash); `netbeans` has the same plus this
server. Mean ± standard deviation over the repetitions; tokens include cache reads.

| task | mode | correct | turns | tokens | cost (USD) | seconds | tool calls | runs using MCP | top tools (all reps) |
|---|---|---|---|---|---|---|---|---|---|
| 01-find-callers | baseline | 3/3 | 9.7 ± 4.0 | 135064 | 0.174 ± 0.021 | 23 | 8.7 | - | Read 13, Bash 7, Write 3, Grep 2 |
| 01-find-callers | netbeans | 3/3 | 5.0 ± 0.0 | 112801 | 0.119 ± 0.001 | 20 | 4.0 | 3/3 | ToolSearch 3, nb.find_usages 3, Grep 3, Write 3 |
| 02-rename-method | baseline | 3/3 | 15.7 ± 3.1 | 196908 | 0.216 ± 0.018 | 35 | 14.7 | - | Read 17, Bash 14, Edit 12, Grep 1 |
| 02-rename-method | netbeans | 3/3 | 6.0 ± 1.7 | 124176 | 0.128 ± 0.017 | 21 | 5.0 | 3/3 | Bash 7, ToolSearch 3, nb.rename 3, Read 1 |
| 03-remove-deprecated | baseline | 3/3 | 13.7 ± 0.6 | 171310 | 0.176 ± 0.006 | 27 | 12.7 | - | Read 12, Edit 12, Bash 8, Grep 6 |
| 03-remove-deprecated | netbeans | 3/3 | 12.0 ± 5.3 | 209292 | 0.189 ± 0.037 | 34 | 11.0 | 3/3 | Bash 10, Edit 6, Read 5, ToolSearch 3 |
| 04-api-migration | baseline | 3/3 | 13.7 ± 3.2 | 172008 | 0.195 ± 0.019 | 28 | 12.7 | - | Edit 12, Bash 11, Read 10, Grep 5 |
| 04-api-migration | netbeans | 3/3 | 7.7 ± 1.2 | 142765 | 0.145 ± 0.010 | 22 | 6.7 | 3/3 | nb.apply_rule 6, Grep 6, Bash 5, ToolSearch 3 |

| mode | correct | mean turns | mean cost per run (USD) | total cost (USD) | MCP calls | MCP errors |
|---|---|---|---|---|---|---|
| baseline | 12/12 | 13.2 | 0.190 | 2.28 | 0 | 0 |
| netbeans | 12/12 | 7.7 | 0.145 | 1.74 | 20 | 0 |

Model: claude-opus-5-5

Observations (from the recorded tool calls):

* Correctness was the same: 24 of 24 runs passed their checks. The fixture's traps (overloads,
  same-named methods, names in comments and strings, `String` vs. `StringBuilder`) did not trip the
  baseline agent either, so on a project this small the gain is efficiency, not correctness.
* With the server, the agent used it in every run (20 calls, no errors) and needed 42% fewer turns
  and 24% less cost overall. The gain comes from single calls replacing read-and-edit loops:
  `find_usages` (task 1, 5 turns in every repetition vs. 6 to 14), `rename` (6 vs. 16 turns) and
  `apply_rule` (8 vs. 14 turns).
* Task 3 (remove a deprecated method) shows no gain and the largest spread. The agent chose
  different routes: twice `inline` (the deprecated method just returned `total()`), once
  `safe_delete` after migrating the callers by hand; each first explored with `find_usages`.
* Every run with the server starts with Claude Code's `ToolSearch`, which loads the MCP tool
  schemas on demand; that costs one turn per session.
* Small sample (3 repetitions, one fixture, one model). A larger real project, where text search
  actually gets things wrong, is the next step.

## 2026-09-30: NetBeans' own code base

Workspace: the `java/` folder of a NetBeans checkout (158 NetBeans module projects, 272 Java source
roots, about 8,900 Java files), started through `nb-mcp` with an empty index, built with
`cluster.config=basic` (Java plus apisupport, which recognizes NetBeans module projects). The
repository was an APFS clone, so the refactoring did not touch the real checkout.

| Call | Seconds | Result | Checked against |
|---|---|---|---|
| start until ready (empty index) | 344 | 158 projects open and indexed | |
| `find_symbol` WhereUsedQueryConst | 2.4 | the enum in refactoring.java | |
| `find_symbol` JavaRefactoringPlugin#process* | 1.2 | the `processFiles` overloads | |
| `find_usages` JavaRefactoringUtils#getClasspathInfoFor | 12.8 | 29 usages | identical to a grep for qualified calls; plain grep for the name: 65 lines, most of them the same-named internal `RefactoringUtils` overloads |
| `find_implementations` JavaRefactoringPlugin | 13.0 | 32 subclasses | 27 direct ones by grep, plus 5 indirect via `PersistenceXmlRefactoring` |
| `outline` CasualDiff (6,558 lines) | 0.8 | first 25 members | |
| `find_usages` ElementHandle | 43.9 | 1,952 usages in 320 files | |
| `diagnostics` whole workspace | 1.0 | 1 error | NetBeans' Ant build fails on the same line (a test importing `javax.annotation.Resource`) |
| `apply_rule` search `String.length() == 0` | 15.2 | 248 lines in 163 files | 335 lines by grep; the rest are other types, strings and comments (e.g. the two in `java.mcp.server` are rule examples in string literals) |
| `rename` Json#string to text | 2.0 | 10 files changed | no call of the old name left; `diagnostics` afterwards unchanged |

Found on the way: a NetBeans installation with the ergonomics cluster (every full IDE; also the
`basic` build) disables this module until the IDE's UI asks for it, so `--start-mcp-server` was an
unknown option. `nb-mcp` now starts NetBeans without the ergonomics cluster.

## 2026-09-30: agent with vs. without the server on NetBeans' `java/` folder

Tasks in `nb-tasks/`, run with `run.sh --repo <clone> --workspace java` (3 repetitions, `$3` cap per
session, model `claude-opus-5-5`). The server was started by each session (warm index, see below).

| Task | Mode | Correct | Turns | Tokens | Cost (USD) | Seconds |
|---|---|---|---|---|---|---|
| 01 find the 29 callers of `JavaRefactoringUtils#getClasspathInfoFor` (grep for the name: 65 lines) | baseline | 3/3 | 6.0 | 170,553 | 0.202 | 36 |
| | netbeans | 2/2 * | 5.0 | 134,240 | 0.160 | 47 |
| 02 find the 32 subclasses of `JavaRefactoringPlugin` (5 indirect) | baseline | 3/3 | 7.7 | 198,013 | 0.245 | 42 |
| | netbeans | 3/3 | 6.3 | 170,393 | 0.156 | 56 |
| 03 rename that method in 22 files, not the same-named internal ones | baseline | 3/3 | 51.3 | 357,632 | 0.559 | 111 |
| | netbeans | 3/3 | 9.0 | 247,328 | 0.220 | 141 |
| **all** | baseline | 9/9 | 21.7 | 242,066 | 0.335 | 63 |
| | netbeans | 8/8 | 7.0 | 190,205 | 0.181 | 86 |

\* One more session was correct too but is left out: the Mac went to idle sleep one second after the
server was started (power log: 543 s asleep, the same 543 s after which Claude Code reported the
connection timeout), so the agent worked with grep only and took 1,059 s. `run.sh` now keeps the
machine awake with `caffeinate -i`.

Observations:

* Every session was correct in both modes. On this code base, too, grep suffices for lookups: the
  names are distinctive, the baseline agent filters the same-named methods itself.
* With the server: 21% fewer tokens, 46% lower cost, 68% fewer turns. The difference is the
  refactoring: without the server the agent reads and edits each of the 22 files (51 turns); with it,
  one `rename` call (9 turns, 61% cheaper).
* Wall-clock time is still worse with the server (86 vs. 63 s), because each session starts NetBeans
  and a refactoring waits for the index check after startup (~100 s on this folder). Read tools no
  longer wait (see below); a server that keeps running between sessions would remove the rest.

An earlier run (1 repetition) failed the rename with the server: tool calls waited only 60 s for
the workspace, NetBeans needed 105 s after a restart (it re-checks 1,129 source roots, 80 s of it the
workspace's own 272 roots, with no changed file), and the agent gave up while waiting. Since then read
tools answer at once from the previous run's index during that check (their answer says so),
refactorings wait up to 5 minutes, and `run.sh` allows long tool calls (`MCP_TOOL_TIMEOUT`).
