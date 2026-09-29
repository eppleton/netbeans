/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.netbeans.modules.java.mcp.server.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Line-based unified diff (Myers' algorithm), in the format of
 * {@code git diff}: renamed files get {@code rename from/to} headers.
 */
final class UnifiedDiff {

    static final int CONTEXT = 3;

    private enum Op {
        EQUAL, DELETE, INSERT
    }

    /** One line of the edit script with its 0-based index in the old and new text. */
    private record Edit(Op op, int oldIndex, int newIndex) {
    }

    private UnifiedDiff() {
    }

    /**
     * Diff of one file.
     *
     * @return the diff, or an empty string if path and content are unchanged
     */
    static String diff(String oldPath, String newPath, String oldText, String newText) {
        boolean renamed = !oldPath.equals(newPath);
        if (!renamed && oldText.equals(newText)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("diff --git a/").append(oldPath).append(" b/").append(newPath).append('\n');
        if (renamed) {
            sb.append("rename from ").append(oldPath).append('\n');
            sb.append("rename to ").append(newPath).append('\n');
        }
        if (oldText.equals(newText)) {
            return sb.toString();
        }
        sb.append("--- a/").append(oldPath).append('\n');
        sb.append("+++ b/").append(newPath).append('\n');
        Text a = new Text(oldText);
        Text b = new Text(newText);
        hunks(a, b, edits(a.keys, b.keys), sb);
        return sb.toString();
    }

    /** Diff of a deleted file. */
    static String deletion(String path, String oldText) {
        StringBuilder sb = new StringBuilder();
        sb.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
        sb.append("deleted file mode 100644\n");
        if (!oldText.isEmpty()) {
            sb.append("--- a/").append(path).append('\n');
            sb.append("+++ /dev/null\n");
            Text a = new Text(oldText);
            Text b = new Text("");
            hunks(a, b, edits(a.keys, b.keys), sb);
        }
        return sb.toString();
    }

    /** Lines of a text; remembers whether the last line ends with a line break. */
    private static final class Text {

        final List<String> lines;
        /** Lines to compare: a last line without line break differs from the same line with one. */
        final List<String> keys;
        final boolean missingFinalNewline;

        Text(String text) {
            List<String> l = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
            // "a\nb\n" splits into [a, b, ""]: the empty tail is not a line
            missingFinalNewline = !text.isEmpty() && !text.endsWith("\n");
            if (l.get(l.size() - 1).isEmpty()) {
                l.remove(l.size() - 1);
            }
            lines = l;
            keys = new ArrayList<>(l);
            if (missingFinalNewline) {
                keys.set(keys.size() - 1, keys.get(keys.size() - 1) + "\0");
            }
        }

        void appendLine(StringBuilder sb, char prefix, int index) {
            sb.append(prefix).append(lines.get(index)).append('\n');
            if (missingFinalNewline && index == lines.size() - 1) {
                sb.append("\\ No newline at end of file\n");
            }
        }
    }

    private static List<Edit> edits(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int max = n + m;
        int offset = max + 1;
        int[] v = new int[2 * max + 3];
        List<int[]> trace = new ArrayList<>();
        int depth = -1;
        search:
        for (int d = 0; d <= max; d++) {
            trace.add(v.clone());
            for (int k = -d; k <= d; k += 2) {
                int x = k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])
                        ? v[offset + k + 1] : v[offset + k - 1] + 1;
                int y = x - k;
                while (x < n && y < m && a.get(x).equals(b.get(y))) {
                    x++;
                    y++;
                }
                v[offset + k] = x;
                if (x >= n && y >= m) {
                    depth = d;
                    break search;
                }
            }
        }
        // walk back through the recorded frontiers to recover the edit script
        List<Edit> reversed = new ArrayList<>();
        int x = n;
        int y = m;
        for (int d = depth; d >= 0; d--) {
            int[] vd = trace.get(d);
            int k = x - y;
            int prevK = k == -d || (k != d && vd[offset + k - 1] < vd[offset + k + 1]) ? k + 1 : k - 1;
            int prevX = vd[offset + prevK];
            int prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                x--;
                y--;
                reversed.add(new Edit(Op.EQUAL, x, y));
            }
            if (d > 0) {
                if (x == prevX) {
                    reversed.add(new Edit(Op.INSERT, prevX, prevY));
                } else {
                    reversed.add(new Edit(Op.DELETE, prevX, prevY));
                }
            }
            x = prevX;
            y = prevY;
        }
        List<Edit> result = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            result.add(reversed.get(i));
        }
        return result;
    }

    private static void hunks(Text a, Text b, List<Edit> edits, StringBuilder sb) {
        int i = 0;
        while (i < edits.size()) {
            // next change
            while (i < edits.size() && edits.get(i).op() == Op.EQUAL) {
                i++;
            }
            if (i == edits.size()) {
                return;
            }
            int start = Math.max(0, i - CONTEXT);
            // extend while changes are separated by at most 2 * CONTEXT equal lines
            int end = i;
            int equalRun = 0;
            for (int j = i; j < edits.size(); j++) {
                if (edits.get(j).op() == Op.EQUAL) {
                    if (++equalRun > 2 * CONTEXT) {
                        break;
                    }
                } else {
                    equalRun = 0;
                    end = j;
                }
            }
            int stop = Math.min(edits.size(), end + 1 + CONTEXT);

            int oldCount = 0;
            int newCount = 0;
            for (int j = start; j < stop; j++) {
                Op op = edits.get(j).op();
                if (op != Op.INSERT) {
                    oldCount++;
                }
                if (op != Op.DELETE) {
                    newCount++;
                }
            }
            Edit first = edits.get(start);
            // unified diff numbers empty ranges by the line before them
            int oldStart = oldCount == 0 ? first.oldIndex() : first.oldIndex() + 1;
            int newStart = newCount == 0 ? first.newIndex() : first.newIndex() + 1;
            sb.append("@@ -").append(oldStart).append(',').append(oldCount)
                    .append(" +").append(newStart).append(',').append(newCount).append(" @@\n");
            for (int j = start; j < stop; j++) {
                Edit e = edits.get(j);
                switch (e.op()) {
                    case EQUAL -> a.appendLine(sb, ' ', e.oldIndex());
                    case DELETE -> a.appendLine(sb, '-', e.oldIndex());
                    case INSERT -> b.appendLine(sb, '+', e.newIndex());
                }
            }
            i = stop;
        }
    }
}
