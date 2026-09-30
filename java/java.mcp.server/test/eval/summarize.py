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

"""Summarizes runs.tsv of run.sh as Markdown: per task and mode, then per mode."""

import collections
import statistics
import sys

COLUMNS = ["task", "mode", "rep", "result", "turns", "tokens", "output_tokens", "cost_usd", "seconds",
           "tool_calls", "mcp_calls", "mcp_errors", "mcp_status", "model", "tools", "stop"]


def numbers(rows, key):
    return [float(r[key]) for r in rows if r.get(key) not in (None, "", "None")]


def mean_sd(rows, key, fmt):
    values = numbers(rows, key)
    if not values:
        return "-"
    if len(values) == 1:
        return fmt % values[0]
    return (fmt + " ± " + fmt) % (statistics.mean(values), statistics.stdev(values))


def mean(rows, key, fmt):
    values = numbers(rows, key)
    return fmt % statistics.mean(values) if values else "-"


def top_tools(rows, n=4):
    counts = collections.Counter()
    for r in rows:
        for item in r.get("tools", "-").split():
            name, _, count = item.rpartition(":")
            if name:
                counts[name] += int(count)
    return ", ".join("%s %d" % (name, c) for name, c in counts.most_common(n)) or "-"


runs = collections.OrderedDict()
for line in open(sys.argv[1], encoding="utf-8"):
    row = dict(zip(COLUMNS, line.rstrip("\n").split("\t")))
    runs.setdefault((row["task"], row["mode"]), []).append(row)

print("| task | mode | correct | turns | tokens | cost (USD) | seconds | tool calls | runs using MCP | top tools (all reps) |")
print("|---|---|---|---|---|---|---|---|---|---|")
for (task, mode), rows in runs.items():
    passed = sum(1 for r in rows if r["result"] == "pass")
    using = sum(1 for r in rows if r.get("mcp_calls") not in (None, "", "0"))
    print("| %s | %s | %d/%d | %s | %s | %s | %s | %s | %s | %s |" % (
        task, mode, passed, len(rows), mean_sd(rows, "turns", "%.1f"), mean(rows, "tokens", "%.0f"),
        mean_sd(rows, "cost_usd", "%.3f"), mean(rows, "seconds", "%.0f"), mean(rows, "tool_calls", "%.1f"),
        "%d/%d" % (using, len(rows)) if mode != "baseline" else "-", top_tools(rows)))

print()
print("| mode | correct | mean turns | mean cost per run (USD) | total cost (USD) | MCP calls | MCP errors |")
print("|---|---|---|---|---|---|---|")
by_mode = collections.OrderedDict()
for (task, mode), rows in runs.items():
    by_mode.setdefault(mode, []).extend(rows)
for mode, rows in by_mode.items():
    passed = sum(1 for r in rows if r["result"] == "pass")
    print("| %s | %d/%d | %s | %s | %.2f | %d | %d |" % (
        mode, passed, len(rows), mean(rows, "turns", "%.1f"), mean(rows, "cost_usd", "%.3f"),
        sum(numbers(rows, "cost_usd")), sum(numbers(rows, "mcp_calls")), sum(numbers(rows, "mcp_errors"))))

models = sorted({r.get("model", "") for rows in runs.values() for r in rows} - {""})
print()
print("Model: %s" % (", ".join(models) or "unknown"))
stopped = [r for rows in runs.values() for r in rows if r.get("stop", "success") != "success"]
if stopped:
    print("NOTE: %d session(s) did not end normally: %s" % (
        len(stopped), ", ".join("%s %s rep %s (%s)" % (r["task"], r["mode"], r["rep"], r.get("stop")) for r in stopped)))
disconnected = [r for rows in runs.values() for r in rows
                if r["mode"] != "baseline" and "=connected" not in r.get("mcp_status", "")]
if disconnected:
    print("WARNING: the NetBeans server was not connected in %d run(s): %s" % (
        len(disconnected), ", ".join("%s rep %s (%s)" % (r["task"], r["rep"], r.get("mcp_status")) for r in disconnected)))
