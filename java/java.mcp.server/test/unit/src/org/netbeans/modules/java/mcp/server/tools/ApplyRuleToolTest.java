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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ApplyRuleToolTest {

    @Test
    public void validRulesPass() {
        assertNull(ApplyRuleTool.checkSyntax("$s.length() == 0 :: $s instanceof java.lang.String => $s.isEmpty() ;;"));
        assertNull(ApplyRuleTool.checkSyntax("new java.lang.Integer($v) => java.lang.Integer.valueOf($v) ;;"));
        assertNull(ApplyRuleTool.checkSyntax("java.util.Objects.equals($a, $b) ;;"));
        assertNull(ApplyRuleTool.checkSyntax("$list.foo($args$) => $list.bar($args$) ;; $x == null ;;"));
        assertNull(ApplyRuleTool.checkSyntax("if ($c) { $then; } else { $else; } => if (!$c) { $else; } else { $then; } ;;"));
    }

    @Test
    public void brokenPatternIsReported() {
        String message = ApplyRuleTool.checkSyntax("$a.foo((( => bar ;;");
        assertNotNull(message);
        assertTrue(message, message.contains("$a.foo((("));
    }

    @Test
    public void brokenReplacementIsReported() {
        String message = ApplyRuleTool.checkSyntax("$a.foo() => $a.bar(( ;;");
        assertNotNull(message);
        assertTrue(message, message.contains("$a.bar(("));
    }
}
