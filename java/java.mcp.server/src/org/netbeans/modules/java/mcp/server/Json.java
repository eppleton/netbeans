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
package org.netbeans.modules.java.mcp.server;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal helpers for building JSON structures as plain maps and lists,
 * which {@code org.json.simple.JSONValue} serializes.
 */
public final class Json {

    private Json() {
    }

    /**
     * Creates an ordered JSON object from alternating keys and values.
     */
    public static Map<String, Object> obj(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Expecting key/value pairs");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return m;
    }

    public static String string(Map<?, ?> args, String key) {
        Object v = args.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    public static boolean bool(Map<?, ?> args, String key, boolean defaultValue) {
        Object v = args.get(key);
        return v instanceof Boolean b ? b : defaultValue;
    }

    public static int integer(Map<?, ?> args, String key, int defaultValue) {
        Object v = args.get(key);
        return v instanceof Number n ? n.intValue() : defaultValue;
    }
}
