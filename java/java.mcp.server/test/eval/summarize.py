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

"""Summarizes runs.tsv of run.sh as a Markdown table: per task and mode."""

import collections
import sys

COLUMNS = ["task", "mode", "rep", "result", "turns", "tokens", "output_tokens", "cost_usd", "seconds"]
runs = collections.OrderedDict()
for line in open(sys.argv[1]):
    row = dict(zip(COLUMNS, line.rstrip("\n").split("\t")))
    runs.setdefault((row["task"], row["mode"]), []).append(row)


def mean(rows, key, fmt):
    values = [float(r[key]) for r in rows if r[key] not in ("", "None")]
    return fmt % (sum(values) / len(values)) if values else "-"


print("| task | mode | correct | turns | tokens | output tokens | cost (USD) | seconds |")
print("|---|---|---|---|---|---|---|---|")
for (task, mode), rows in runs.items():
    passed = sum(1 for r in rows if r["result"] == "pass")
    print("| %s | %s | %d/%d | %s | %s | %s | %s | %s |" % (
        task, mode, passed, len(rows), mean(rows, "turns", "%.1f"), mean(rows, "tokens", "%.0f"),
        mean(rows, "output_tokens", "%.0f"), mean(rows, "cost_usd", "%.3f"), mean(rows, "seconds", "%.0f")))
