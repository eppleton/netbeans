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
