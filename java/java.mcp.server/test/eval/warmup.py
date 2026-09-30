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

"""Starts nb-mcp on a workspace, waits until it is indexed, then stops it again."""

import json
import subprocess
import sys
import time

nb_mcp, data, workspace = sys.argv[1:4]
server = subprocess.Popen([nb_mcp, "--data", data, workspace], stdin=subprocess.PIPE,
                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1)
next_id = 0


def request(method, params):
    global next_id
    next_id += 1
    server.stdin.write(json.dumps({"jsonrpc": "2.0", "id": next_id, "method": method, "params": params}) + "\n")
    server.stdin.flush()
    while True:
        line = server.stdout.readline()
        if not line:
            sys.exit("nb-mcp exited")
        message = json.loads(line)
        if message.get("id") == next_id:
            return message


request("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                       "clientInfo": {"name": "warmup", "version": "1"}})
start = time.time()
while True:
    text = request("tools/call", {"name": "workspace_status", "arguments": {}})["result"]["content"][0]["text"]
    if "State: ready" in text:
        break
    if "State: failed" in text or time.time() - start > 900:
        sys.exit(text)
    time.sleep(3)
print("indexed in %.0fs" % (time.time() - start))
server.stdin.close()
server.wait(60)
