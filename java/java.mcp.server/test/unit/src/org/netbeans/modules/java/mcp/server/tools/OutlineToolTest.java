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

public class OutlineToolTest {

    @Test
    public void simpleTypesDropsPackages() {
        assertEquals("String", OutlineTool.simpleTypes("java.lang.String"));
        assertEquals("List<Order.Line>", OutlineTool.simpleTypes("java.util.List<com.acme.shop.Order.Line>"));
        assertEquals("Map<String,List<Foo>>", OutlineTool.simpleTypes("java.util.Map<java.lang.String,java.util.List<a.b.Foo>>"));
    }

    @Test
    public void simpleTypesKeepsPrimitivesArraysAndWildcards() {
        assertEquals("int", OutlineTool.simpleTypes("int"));
        assertEquals("String[]", OutlineTool.simpleTypes("java.lang.String[]"));
        assertEquals("List<? extends Number>", OutlineTool.simpleTypes("java.util.List<? extends java.lang.Number>"));
        assertEquals("T", OutlineTool.simpleTypes("T"));
    }
}
