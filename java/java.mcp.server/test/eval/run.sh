#!/usr/bin/env bash

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# Runs the evaluation tasks with Claude Code in headless mode, once with only
# its built-in tools ("baseline") and once with the NetBeans MCP server added
# ("netbeans"), each on a fresh copy of the fixture project, and records
# correctness (the task's check.sh), turns, tokens, cost and time.
#
# THIS USES YOUR CLAUDE CODE LOGIN AND COSTS TOKENS: every run is a real session.
#
# Usage: run.sh [--reps N] [--model M] [--modes baseline,netbeans] [--tasks GLOB]
#               [--nb-mcp PATH]
# Environment: EVAL_DIR work and result directory (default ${TMPDIR:-/tmp}/nbmcp-eval)
# Needs: claude, python3, git, javac

set -u

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../../.." && pwd)
EVAL_DIR=${EVAL_DIR:-${TMPDIR:-/tmp}/nbmcp-eval}
REPS=1
MODEL=
MODES=baseline,netbeans
TASKS='*'
NB_MCP=$REPO/nbbuild/netbeans/java/bin/nb-mcp

while [ $# -gt 0 ]; do
    case "$1" in
        --reps) REPS=$2; shift 2 ;;
        --model) MODEL=$2; shift 2 ;;
        --modes) MODES=$2; shift 2 ;;
        --tasks) TASKS=$2; shift 2 ;;
        --nb-mcp) NB_MCP=$2; shift 2 ;;
        *) echo "unknown argument $1" >&2; exit 2 ;;
    esac
done
for tool in claude python3 git javac; do
    command -v $tool > /dev/null || { echo "$tool is required" >&2; exit 2; }
done
[ -x "$NB_MCP" ] || { echo "no nb-mcp at $NB_MCP (build the module or use --nb-mcp)" >&2; exit 2; }

# always the same path, so the NetBeans index of the project stays warm between runs
WORK=$EVAL_DIR/work/project
RESULTS=$EVAL_DIR/results/$(date +%Y%m%d-%H%M%S)
mkdir -p "$RESULTS"
echo "Results: $RESULTS"

# built-in tools both modes may use; edits are auto-accepted, everything else is denied
BASE_TOOLS=(Read Edit Write Glob Grep "Bash(./compile.sh)" "Bash(javac:*)" "Bash(java:*)"
    "Bash(grep:*)" "Bash(find:*)" "Bash(ls:*)" "Bash(cat:*)" "Bash(git diff:*)" "Bash(git status:*)")

fresh_project() {
    "$HERE/fixture.sh" "$WORK" > /dev/null
    (cd "$WORK" && git init -q && git add -A \
        && git -c user.name=eval -c user.email=eval@example.invalid -c commit.gpgsign=false commit -qm fixture)
}

mcp_config() {
    if [ "$1" = netbeans ]; then
        python3 -c 'import json,sys; print(json.dumps({"mcpServers": {"netbeans": {"command": sys.argv[1], "args": ["--data", sys.argv[2], sys.argv[3]]}}}))' \
            "$NB_MCP" "$EVAL_DIR/nb-data" "$WORK"
    else
        echo '{"mcpServers": {}}'
    fi
}

if [[ ",$MODES," == *",netbeans,"* ]]; then
    # index the fixture once before the timed runs; the first start of a workspace indexes the JDK too
    echo "Warming up the NetBeans index..."
    fresh_project
    python3 "$HERE/warmup.py" "$NB_MCP" "$EVAL_DIR/nb-data" "$WORK" || { echo "warm-up failed" >&2; exit 1; }
fi

for task_dir in "$HERE"/tasks/$TASKS/; do
    task=$(basename "$task_dir")
    # the prompt without its license header
    prompt=$(sed '1,/-->/d' "$task_dir/prompt.md")
    for mode in ${MODES//,/ }; do
        for rep in $(seq 1 "$REPS"); do
            run=$task-$mode-$rep
            echo "--- $run"
            fresh_project
            allowed=("${BASE_TOOLS[@]}")
            [ "$mode" = netbeans ] && allowed+=(mcp__netbeans)
            args=(-p "$prompt" --output-format json --no-session-persistence
                --strict-mcp-config --mcp-config "$(mcp_config "$mode")"
                --permission-mode acceptEdits --allowedTools "${allowed[@]}")
            [ -n "$MODEL" ] && args+=(--model "$MODEL")
            start=$(date +%s)
            (cd "$WORK" && MCP_TIMEOUT=120000 claude "${args[@]}" > "$RESULTS/$run.json" 2> "$RESULTS/$run.stderr")
            end=$(date +%s)
            check=$(cd "$WORK" && bash "$task_dir/check.sh" 2>&1)
            status=$?
            (cd "$WORK" && git add -A && git diff --cached) > "$RESULTS/$run.diff"
            printf '%s\n' "$check" > "$RESULTS/$run.check"
            python3 - "$RESULTS/$run.json" "$task" "$mode" "$rep" "$status" "$((end - start))" >> "$RESULTS/runs.tsv" <<'PY'
import json, sys
path, task, mode, rep, status, wall = sys.argv[1:]
try:
    r = json.load(open(path))
except Exception:
    r = {}
u = r.get("usage", {})
tokens = sum(u.get(k, 0) for k in ("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens", "output_tokens"))
print("\t".join(map(str, [task, mode, rep, "pass" if status == "0" else "FAIL", r.get("num_turns", ""),
                          tokens, u.get("output_tokens", ""), r.get("total_cost_usd", ""), wall])))
PY
            echo "    $(tail -1 "$RESULTS/runs.tsv" | cut -f4) ($(printf '%s' "$check" | tail -1))"
        done
    done
done

python3 "$HERE/summarize.py" "$RESULTS/runs.tsv" | tee "$RESULTS/summary.md"
