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

"""Turns the stream-json transcript of one run into a row of runs.tsv.

Usage: metrics.py <run.jsonl> <task> <mode> <rep> <check exit status> <wall seconds>
Columns: see COLUMNS in summarize.py.
"""

import collections
import json
import sys

path, task, mode, rep, status, wall = sys.argv[1:7]
result = {}
model = ""
mcp_status = "-"
calls = collections.Counter()
names_by_id = {}
mcp_errors = 0
for line in open(path, encoding="utf-8"):
    try:
        event = json.loads(line)
    except ValueError:
        continue
    kind = event.get("type")
    if kind == "system" and event.get("subtype") == "init":
        model = event.get("model", "")
        servers = event.get("mcp_servers") or []
        mcp_status = ",".join("%s=%s" % (s.get("name"), s.get("status")) for s in servers) or "-"
    elif kind == "assistant":
        for item in event.get("message", {}).get("content", []):
            if item.get("type") == "tool_use":
                calls[item.get("name")] += 1
                names_by_id[item.get("id")] = item.get("name")
    elif kind == "user":
        content = event.get("message", {}).get("content", [])
        for item in content if isinstance(content, list) else []:
            if (item.get("type") == "tool_result" and item.get("is_error")
                    and names_by_id.get(item.get("tool_use_id"), "").startswith("mcp__")):
                mcp_errors += 1
    elif kind == "result":
        result = event

usage = result.get("usage", {})
tokens = sum(usage.get(k, 0) for k in ("input_tokens", "cache_creation_input_tokens",
                                       "cache_read_input_tokens", "output_tokens"))
mcp_calls = sum(n for name, n in calls.items() if name.startswith("mcp__"))
tools = " ".join("%s:%d" % (name.replace("mcp__netbeans__", "nb."), n) for name, n in calls.most_common())
print("\t".join(map(str, [task, mode, rep, "pass" if status == "0" else "FAIL",
                          result.get("num_turns", ""), tokens, usage.get("output_tokens", ""),
                          result.get("total_cost_usd", ""), wall, sum(calls.values()), mcp_calls,
                          mcp_errors, mcp_status, model, tools or "-", result.get("subtype", "none")])))
