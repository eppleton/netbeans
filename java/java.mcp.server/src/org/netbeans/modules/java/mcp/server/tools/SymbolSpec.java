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
import java.util.List;

/**
 * Parsed form of the symbol syntax used by the tools:
 * <pre>
 * com.acme.Foo                      type (nested types: com.acme.Foo.Inner or com.acme.Foo$Inner)
 * com.acme.Foo#count                field, or method if unambiguous
 * com.acme.Foo#bar(String,int)      method with parameter types (simple or qualified names)
 * com.acme.Foo#&lt;init&gt;(String)       constructor (com.acme.Foo#Foo(String) works too)
 * </pre>
 * Parameter types are compared by erasure, so {@code List} matches
 * {@code java.util.List<String>}; varargs may be written as {@code String...}.
 *
 * @param typeName canonical name of the type, with '.' separating nested types
 * @param member member name, or {@code null} when the symbol is the type itself
 * @param parameterTypes parameter types, or {@code null} when not given (any overload)
 */
public record SymbolSpec(String typeName, String member, List<String> parameterTypes) {

    public static final String CONSTRUCTOR = "<init>";

    public static SymbolSpec parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Empty symbol");
        }
        String s = text.strip();
        String typePart = s;
        String memberPart = null;
        int hash = s.indexOf('#');
        if (hash >= 0) {
            typePart = s.substring(0, hash).strip();
            memberPart = s.substring(hash + 1).strip();
        }
        String typeName = stripGenerics(typePart).replace('$', '.');
        if (!isQualifiedIdentifier(typeName)) {
            throw new IllegalArgumentException("Not a type name: '" + typePart
                    + "'. Expected a fully qualified name like com.acme.Foo");
        }
        if (memberPart == null) {
            return new SymbolSpec(typeName, null, null);
        }
        String name = memberPart;
        List<String> params = null;
        int open = memberPart.indexOf('(');
        if (open >= 0) {
            int close = memberPart.lastIndexOf(')');
            if (close < open) {
                throw new IllegalArgumentException("Unbalanced parentheses in '" + memberPart + "'");
            }
            name = memberPart.substring(0, open).strip();
            params = parseParameters(memberPart.substring(open + 1, close));
        }
        String simpleTypeName = typeName.substring(typeName.lastIndexOf('.') + 1);
        if (name.equals(simpleTypeName)) {
            name = CONSTRUCTOR;
        }
        if (!name.equals(CONSTRUCTOR) && !isIdentifier(name)) {
            throw new IllegalArgumentException("Not a member name: '" + name + "'");
        }
        return new SymbolSpec(typeName, name, params);
    }

    public boolean isConstructor() {
        return CONSTRUCTOR.equals(member);
    }

    /**
     * Whether a parameter type written by the agent matches a parameter's erasure.
     *
     * @param spec as written in the symbol, normalized by {@link #parse}
     * @param erasure erasure of the declared parameter type, e.g. {@code java.util.List}
     */
    public static boolean parameterMatches(String spec, String erasure) {
        return spec.equals(erasure) || erasure.endsWith("." + spec);
    }

    private static List<String> parseParameters(String text) {
        List<String> result = new ArrayList<>();
        if (text.isBlank()) {
            return result;
        }
        // split on top-level commas only, generics may contain commas
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                result.add(normalizeParameter(text.substring(start, i)));
                start = i + 1;
            }
        }
        result.add(normalizeParameter(text.substring(start)));
        return result;
    }

    private static String normalizeParameter(String p) {
        String t = stripGenerics(p).replaceAll("\\s+", "");
        // drop a parameter name if one was given: "String s" -> "String"
        String[] words = stripGenerics(p).strip().split("\\s+");
        if (words.length == 2 && isIdentifier(words[1])) {
            t = words[0];
        }
        t = t.replace("...", "[]").replace('$', '.');
        if (t.isEmpty()) {
            throw new IllegalArgumentException("Empty parameter type in '" + p + "'");
        }
        return t;
    }

    private static String stripGenerics(String s) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (char c : s.toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (depth == 0) {
                sb.append(c);
            }
        }
        return sb.toString().strip();
    }

    private static boolean isQualifiedIdentifier(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (String part : s.split("\\.", -1)) {
            if (!isIdentifier(part)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIdentifier(String s) {
        if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
