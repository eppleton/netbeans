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

import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

public class SymbolSpecTest {

    @Test
    public void type() {
        SymbolSpec s = SymbolSpec.parse("com.acme.Foo");
        assertEquals("com.acme.Foo", s.typeName());
        assertNull(s.member());
        assertNull(s.parameterTypes());
    }

    @Test
    public void nestedTypeWithDollar() {
        assertEquals("com.acme.Foo.Inner", SymbolSpec.parse("com.acme.Foo$Inner").typeName());
    }

    @Test
    public void memberWithoutParameters() {
        SymbolSpec s = SymbolSpec.parse("com.acme.Foo#bar");
        assertEquals("bar", s.member());
        assertNull(s.parameterTypes());
    }

    @Test
    public void methodWithParameters() {
        SymbolSpec s = SymbolSpec.parse("com.acme.Foo#bar(String, java.util.Map<String, List<Integer>>, int[], Object...)");
        assertEquals(List.of("String", "java.util.Map", "int[]", "Object[]"), s.parameterTypes());
    }

    @Test
    public void parameterNamesAreIgnored() {
        SymbolSpec s = SymbolSpec.parse("com.acme.Foo#bar(String name, int count)");
        assertEquals(List.of("String", "int"), s.parameterTypes());
    }

    @Test
    public void noArgMethod() {
        assertEquals(List.of(), SymbolSpec.parse("com.acme.Foo#bar()").parameterTypes());
    }

    @Test
    public void constructors() {
        assertTrue(SymbolSpec.parse("com.acme.Foo#<init>()").isConstructor());
        assertTrue(SymbolSpec.parse("com.acme.Foo#Foo(String)").isConstructor());
        assertFalse(SymbolSpec.parse("com.acme.Foo#foo(String)").isConstructor());
    }

    @Test
    public void parameterMatching() {
        assertTrue(SymbolSpec.parameterMatches("String", "java.lang.String"));
        assertTrue(SymbolSpec.parameterMatches("java.lang.String", "java.lang.String"));
        assertTrue(SymbolSpec.parameterMatches("Outer.Inner", "com.acme.Outer.Inner"));
        assertTrue(SymbolSpec.parameterMatches("int", "int"));
        assertFalse(SymbolSpec.parameterMatches("String", "com.acme.MyString"));
        assertFalse(SymbolSpec.parameterMatches("String", "java.lang.String[]"));
    }

    @Test
    public void invalidInput() {
        for (String bad : new String[]{"", "#foo", "com..Foo", "com.acme.Foo#1x", "com.acme.Foo#bar(String"}) {
            try {
                SymbolSpec.parse(bad);
                fail("Expected failure for '" + bad + "'");
            } catch (IllegalArgumentException expected) {
            }
        }
    }

    @Test
    public void globToRegex() {
        assertEquals(".*Service", FindSymbolTool.globToRegex("*Service"));
        assertEquals("Order.Impl", FindSymbolTool.globToRegex("Order?Impl"));
    }
}
