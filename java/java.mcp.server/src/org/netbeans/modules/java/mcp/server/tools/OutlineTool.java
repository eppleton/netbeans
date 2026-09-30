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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.Elements;
import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.modules.java.mcp.server.Json;
import org.netbeans.modules.java.mcp.server.Tool;
import org.netbeans.modules.java.mcp.server.Workspace;
import org.openide.filesystems.FileObject;

/**
 * Lists the members of a type, or of all types in a file: modifiers,
 * canonical signatures, declared types and line numbers, without bodies.
 */
public final class OutlineTool implements Tool {

    private static final int DEFAULT_LIMIT = 300;
    // package prefixes in type names: java.util.List<com.acme.Foo> -> List<Foo>
    private static final Pattern PACKAGE_PREFIX = Pattern.compile("\\b(?:[a-z_$][\\w$]*\\.)+(?=[A-Za-z_$])");

    private final Workspace workspace;

    public OutlineTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "outline";
    }

    @Override
    public String description() {
        return "Show the structure of a Java type or file without reading it: nested types, fields, "
                + "constructors and methods with modifiers, declared types and line numbers. Each member is "
                + "listed with its canonical symbol (e.g. com.acme.Foo#bar(java.lang.String)) for use with "
                + "find_usages and find_implementations. Cheaper than reading the file when you only need the "
                + "API or want to know which lines to read. Give either 'symbol' (fully qualified type, "
                + "e.g. com.acme.Foo or com.acme.Foo.Inner) or 'file' (path relative to the workspace).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Json.obj(
                "type", "object",
                "properties", Json.obj(
                        "symbol", Json.obj("type", "string",
                                "description", "Fully qualified type, e.g. com.acme.OrderService"),
                        "file", Json.obj("type", "string",
                                "description", "Java source file, relative to the workspace or absolute"),
                        "limit", Json.obj("type", "integer",
                                "description", "Maximum members listed, default " + DEFAULT_LIMIT)));
    }

    @Override
    public Result call(Map<?, ?> arguments) throws Exception {
        String symbolText = Json.string(arguments, "symbol");
        String path = Json.string(arguments, "file");
        if ((symbolText == null) == (path == null)) {
            return Result.error("Give exactly one of 'symbol' (a fully qualified type) or 'file'.");
        }
        int limit = Json.integer(arguments, "limit", DEFAULT_LIMIT);
        List<FileObject> roots;
        try {
            roots = workspace.awaitSourceRoots(Workspace.Freshness.INDEXED);
        } catch (Workspace.NotReadyException ex) {
            return Result.error(ex.getMessage());
        }

        String typeName = null;
        FileObject file;
        if (symbolText != null) {
            SymbolSpec spec;
            try {
                spec = SymbolSpec.parse(symbolText);
            } catch (IllegalArgumentException ex) {
                return Result.error(ex.getMessage());
            }
            if (spec.member() != null) {
                return Result.error("outline takes a type, not a member; use " + spec.typeName());
            }
            typeName = spec.typeName();
            file = SymbolResolver.findSourceFile(typeName, roots);
            if (file == null) {
                return Result.error("No source for type " + typeName + " in the workspace. "
                        + "Use find_symbol to look up the qualified name.");
            }
        } else {
            file = workspace.findFile(path);
            if (file == null || !file.isData()) {
                return Result.error("No such file: " + path);
            }
        }
        JavaSource js = JavaSource.forFileObject(file);
        if (js == null) {
            return Result.error("Not a Java source file: " + workspace.displayPath(file));
        }
        String requestedType = typeName;
        AtomicReference<Result> result = new AtomicReference<>();
        js.runUserActionTask(cc -> {
            cc.toPhase(JavaSource.Phase.ELEMENTS_RESOLVED);
            List<TypeElement> types = new ArrayList<>();
            if (requestedType != null) {
                TypeElement type = cc.getElements().getTypeElement(requestedType);
                if (type == null) {
                    result.set(Result.error("Type " + requestedType + " not found in " + workspace.displayPath(file)));
                    return;
                }
                types.add(type);
            } else {
                types.addAll(cc.getTopLevelElements());
            }
            Printer p = new Printer(cc, limit);
            p.sb.append(workspace.displayPath(file)).append('\n');
            for (TypeElement t : types) {
                p.type(t);
            }
            result.set(Result.ok(p.finish()));
        }, true);
        return result.get();
    }

    static String simpleTypes(String type) {
        return PACKAGE_PREFIX.matcher(type).replaceAll("");
    }

    private static final class Printer {

        private final CompilationController cc;
        private final int limit;
        private final StringBuilder sb = new StringBuilder();
        private int members;
        private int omitted;

        Printer(CompilationController cc, int limit) {
            this.cc = cc;
            this.limit = limit;
        }

        void type(TypeElement type) {
            sb.append('\n').append(kind(type)).append(' ').append(type.getQualifiedName());
            String supers = supertypes(type);
            if (!supers.isEmpty()) {
                sb.append(supers);
            }
            sb.append("  (line ").append(line(type)).append(")\n");
            List<TypeElement> nested = new ArrayList<>();
            for (Element e : type.getEnclosedElements()) {
                if (e instanceof TypeElement te) {
                    nested.add(te);
                }
                member(e);
            }
            for (TypeElement te : nested) {
                type(te);
            }
        }

        private void member(Element e) {
            if (members++ >= limit) {
                omitted++;
                return;
            }
            boolean implicit = cc.getElements().getOrigin(e) != Elements.Origin.EXPLICIT;
            sb.append("  ").append(implicit ? "-" : String.valueOf(line(e))).append(": ");
            String mods = e.getModifiers().stream().map(m -> m.toString()).collect(Collectors.joining(" "));
            if (!mods.isEmpty()) {
                sb.append(mods).append(' ');
            }
            sb.append(kind(e)).append(' ').append(SymbolResolver.signature(e, cc));
            switch (e.getKind()) {
                case FIELD, ENUM_CONSTANT, RECORD_COMPONENT ->
                    sb.append(" : ").append(simpleTypes(e.asType().toString()));
                case METHOD -> {
                    ExecutableElement ee = (ExecutableElement) e;
                    sb.append(" : ").append(simpleTypes(ee.getReturnType().toString()));
                    if (!ee.getThrownTypes().isEmpty()) {
                        sb.append(" throws ").append(ee.getThrownTypes().stream()
                                .map(t -> simpleTypes(t.toString())).collect(Collectors.joining(", ")));
                    }
                }
                default -> {
                }
            }
            if (implicit) {
                sb.append("  (implicit)");
            }
            sb.append('\n');
        }

        private String supertypes(TypeElement type) {
            List<String> names = new ArrayList<>();
            if (type.getSuperclass().getKind() == TypeKind.DECLARED
                    && !"java.lang.Object".equals(type.getSuperclass().toString())
                    && type.getKind() == ElementKind.CLASS) {
                names.add("extends " + simpleTypes(type.getSuperclass().toString()));
            }
            if (!type.getInterfaces().isEmpty()) {
                names.add((type.getKind() == ElementKind.INTERFACE ? "extends " : "implements ")
                        + type.getInterfaces().stream().map(t -> simpleTypes(t.toString())).collect(Collectors.joining(", ")));
            }
            return names.isEmpty() ? "" : " " + String.join(" ", names);
        }

        private int line(Element e) {
            return SymbolResolver.line(cc, e);
        }

        private static String kind(Element e) {
            return switch (e.getKind()) {
                case ANNOTATION_TYPE -> "@interface";
                case ENUM_CONSTANT -> "enum constant";
                case RECORD_COMPONENT -> "record component";
                default -> e.getKind().name().toLowerCase(Locale.ROOT);
            };
        }

        String finish() {
            if (omitted > 0) {
                sb.append("\n... ").append(omitted).append(" more members not shown, raise 'limit' to see them\n");
            }
            return sb.toString();
        }
    }
}
