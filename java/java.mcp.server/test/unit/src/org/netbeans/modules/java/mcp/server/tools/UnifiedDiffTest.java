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

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class UnifiedDiffTest {

    @Test
    public void identicalGivesNothing() {
        assertEquals("", UnifiedDiff.diff("a.txt", "a.txt", "x\ny\n", "x\ny\n"));
    }

    @Test
    public void changedLineWithContext() {
        String before = "1\n2\n3\n4\n5\n6\n7\n8\n9\n";
        String after = "1\n2\n3\n4\nfive\n6\n7\n8\n9\n";
        assertEquals("""
                diff --git a/a.txt b/a.txt
                --- a/a.txt
                +++ b/a.txt
                @@ -2,7 +2,7 @@
                 2
                 3
                 4
                -5
                +five
                 6
                 7
                 8
                """, UnifiedDiff.diff("a.txt", "a.txt", before, after));
    }

    @Test
    public void distantChangesGetSeparateHunks() {
        String before = "a\n1\n2\n3\n4\n5\n6\n7\n8\nb\n";
        String after = "A\n1\n2\n3\n4\n5\n6\n7\n8\nB\n";
        String diff = UnifiedDiff.diff("f", "f", before, after);
        assertEquals(2, diff.split("\n@@ ", -1).length - 1);
    }

    @Test
    public void insertionIntoEmptyFile() {
        assertEquals("""
                diff --git a/n b/n
                --- a/n
                +++ b/n
                @@ -0,0 +1,2 @@
                +x
                +y
                """, UnifiedDiff.diff("n", "n", "", "x\ny\n"));
    }

    @Test
    public void missingFinalNewlineIsAChange() {
        assertEquals("""
                diff --git a/f b/f
                --- a/f
                +++ b/f
                @@ -1,2 +1,2 @@
                 a
                -b
                +b
                \\ No newline at end of file
                """, UnifiedDiff.diff("f", "f", "a\nb\n", "a\nb"));
    }

    @Test
    public void renameWithAndWithoutChanges() {
        assertEquals("""
                diff --git a/p/Old.java b/p/New.java
                rename from p/Old.java
                rename to p/New.java
                """, UnifiedDiff.diff("p/Old.java", "p/New.java", "class X {}\n", "class X {}\n"));
        String diff = UnifiedDiff.diff("p/Old.java", "p/New.java", "class Old {}\n", "class New {}\n");
        assertEquals("""
                diff --git a/p/Old.java b/p/New.java
                rename from p/Old.java
                rename to p/New.java
                --- a/p/Old.java
                +++ b/p/New.java
                @@ -1,1 +1,1 @@
                -class Old {}
                +class New {}
                """, diff);
    }
}
