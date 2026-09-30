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

import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.modules.java.mcp.server.Json;
import org.netbeans.modules.java.mcp.server.Tool;
import org.netbeans.modules.java.mcp.server.Workspace;
import org.netbeans.modules.refactoring.java.api.ChangeParametersRefactoring;
import org.netbeans.modules.refactoring.java.api.ChangeParametersRefactoring.ParameterInfo;
import org.netbeans.modules.refactoring.java.api.JavaRefactoringUtils;

/**
 * Changes parameters, name, return type or visibility of a method or
 * constructor and updates all callers and overriding methods.
 */
public final class ChangeSignatureTool implements Tool {

    private final Workspace workspace;

    public ChangeSignatureTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "change_signature";
    }

    @Override
    public String description() {
        return "Change the parameters (add, remove, reorder, rename, retype), name, return type or visibility of a "
                + "Java method or constructor, updating all callers and overriding/implementing methods, and write "
                + "the changes to disk. 'parameters' is the complete new parameter list in order: keep an existing "
                + "parameter with {\"from\":\"oldName\"} (optionally with a new \"name\" or \"type\"), add one with "
                + "{\"name\":\"n\",\"type\":\"int\",\"default\":\"0\"} where default is the expression passed by "
                + "existing callers; parameters not listed are removed. Omit 'parameters' to keep them. Symbol syntax: "
                + "com.acme.Foo#bar(String,int), com.acme.Foo#<init>(). Output: warnings, then a unified diff.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Json.obj(
                "type", "object",
                "properties", Json.obj(
                        "symbol", Json.obj("type", "string",
                                "description", "Fully qualified method or constructor, e.g. com.acme.Foo#bar(String,int)"),
                        "parameters", Json.obj("type", "array",
                                "description", "Complete new parameter list, in order",
                                "items", Json.obj("type", "object",
                                        "properties", Json.obj(
                                                "from", Json.obj("type", "string",
                                                        "description", "Name of the existing parameter to keep; omit for a new one"),
                                                "name", Json.obj("type", "string",
                                                        "description", "Parameter name; required for new parameters"),
                                                "type", Json.obj("type", "string",
                                                        "description", "Type as written in source, e.g. int or List<String>; required for new parameters"),
                                                "default", Json.obj("type", "string",
                                                        "description", "New parameters: expression existing callers pass, e.g. 0 or null")))),
                        "new_name", Json.obj("type", "string", "description", "New method name"),
                        "return_type", Json.obj("type", "string", "description", "New return type, e.g. Optional<Order>"),
                        "visibility", Json.obj("type", "string", "enum", List.of("public", "protected", "package", "private"),
                                "description", "New visibility"),
                        "max_diff_lines", Json.obj("type", "integer",
                                "description", "Maximum diff lines returned, default " + RefactoringRunner.DEFAULT_MAX_DIFF_LINES)),
                "required", List.of("symbol"));
    }

    /** A parameter of the method as declared: name and type as written in the source. */
    private record Declared(String name, String type) {
    }

    @Override
    public Result call(Map<?, ?> arguments) throws Exception {
        int maxDiffLines = Json.integer(arguments, "max_diff_lines", RefactoringRunner.DEFAULT_MAX_DIFF_LINES);
        String newName = Json.string(arguments, "new_name");
        String returnType = Json.string(arguments, "return_type");
        String visibility = Json.string(arguments, "visibility");
        Object parametersArg = arguments.get("parameters");
        if (parametersArg != null && !(parametersArg instanceof List)) {
            return Result.error("'parameters' must be an array");
        }
        if (newName != null && (!SourceVersion.isIdentifier(newName) || SourceVersion.isKeyword(newName))) {
            return Result.error("'" + newName + "' is not a valid Java identifier");
        }
        if (parametersArg == null && newName == null && returnType == null && visibility == null) {
            return Result.error("Nothing to change: give 'parameters', 'new_name', 'return_type' or 'visibility'.");
        }
        Resolution r = Resolution.of(workspace, arguments, Workspace.Freshness.CURRENT);
        if (r.error() != null) {
            return r.error();
        }
        SymbolResolver.Resolved symbol = r.symbol();
        boolean constructor = symbol.kind() == ElementKind.CONSTRUCTOR;
        if (symbol.kind() != ElementKind.METHOD && !constructor) {
            return Result.error("change_signature works on methods and constructors; " + symbol.signature()
                    + " is a " + symbol.kind().name().toLowerCase(Locale.ROOT) + ".");
        }
        if (constructor && (newName != null || returnType != null)) {
            return Result.error("A constructor has no name or return type of its own to change.");
        }

        List<Declared> declared = declaredParameters(symbol);
        if (declared == null) {
            return Result.error("Cannot read the parameters of " + symbol.signature());
        }
        ParameterInfo[] infos;
        if (parametersArg == null) {
            infos = new ParameterInfo[declared.size()];
            for (int i = 0; i < infos.length; i++) {
                infos[i] = new ParameterInfo(i, declared.get(i).name(), declared.get(i).type(), null);
            }
        } else {
            List<?> requested = (List<?>) parametersArg;
            infos = new ParameterInfo[requested.size()];
            Set<String> used = new HashSet<>();
            for (int i = 0; i < infos.length; i++) {
                if (!(requested.get(i) instanceof Map<?, ?> p)) {
                    return Result.error("parameters[" + i + "] must be an object");
                }
                String from = Json.string(p, "from");
                String name = Json.string(p, "name");
                String type = Json.string(p, "type");
                String defaultValue = Json.string(p, "default");
                if (from != null) {
                    int index = indexOf(declared, from);
                    if (index < 0) {
                        return Result.error("parameters[" + i + "]: " + symbol.signature() + " has no parameter '" + from
                                + "'. Its parameters are: " + names(declared));
                    }
                    if (!used.add(from)) {
                        return Result.error("parameters[" + i + "]: '" + from + "' is listed twice");
                    }
                    infos[i] = new ParameterInfo(index, name != null ? name : from,
                            type != null ? type : declared.get(index).type(), null);
                } else {
                    if (name == null || type == null || defaultValue == null) {
                        return Result.error("parameters[" + i + "] is new (no 'from'), so it needs 'name', 'type' "
                                + "and 'default' (the value existing callers pass).");
                    }
                    infos[i] = new ParameterInfo(-1, name, type, defaultValue);
                }
                if (!SourceVersion.isIdentifier(infos[i].getName()) || SourceVersion.isKeyword(infos[i].getName())) {
                    return Result.error("parameters[" + i + "]: '" + infos[i].getName() + "' is not a valid Java identifier");
                }
            }
        }

        ChangeParametersRefactoring refactoring = new ChangeParametersRefactoring(symbol.handle());
        refactoring.setParameterInfo(infos);
        if (newName != null) {
            refactoring.setMethodName(newName);
        }
        if (returnType != null) {
            refactoring.setReturnType(returnType);
        }
        if (visibility != null) {
            Set<Modifier> modifiers = EnumSet.noneOf(Modifier.class);
            switch (visibility) {
                case "public" -> modifiers.add(Modifier.PUBLIC);
                case "protected" -> modifiers.add(Modifier.PROTECTED);
                case "private" -> modifiers.add(Modifier.PRIVATE);
                case "package" -> {
                }
                default -> {
                    return Result.error("'visibility' must be public, protected, package or private");
                }
            }
            refactoring.setModifiers(modifiers);
        }
        refactoring.getContext().add(JavaRefactoringUtils.getClasspathInfoFor(symbol.file()));
        return new RefactoringRunner(workspace, r.roots(), refactoring, "change the signature of " + symbol.signature())
                .involving(symbol.file())
                .run(maxDiffLines);
    }

    private static List<Declared> declaredParameters(SymbolResolver.Resolved symbol) throws Exception {
        JavaSource js = JavaSource.forFileObject(symbol.file());
        if (js == null) {
            return null;
        }
        AtomicReference<List<Declared>> result = new AtomicReference<>();
        js.runUserActionTask(cc -> {
            cc.toPhase(JavaSource.Phase.RESOLVED);
            Element e = symbol.handle().resolveElement(cc);
            Tree tree = e != null ? cc.getTrees().getTree(e) : null;
            if (!(tree instanceof MethodTree mt)) {
                return;
            }
            List<Declared> params = new ArrayList<>();
            String text = cc.getText();
            for (VariableTree vt : mt.getParameters()) {
                // the type exactly as written, so the refactoring does not rewrite it
                int[] span = SymbolResolver.span(cc, vt.getType());
                String type = span != null ? text.substring(span[0], span[1]) : vt.getType().toString();
                params.add(new Declared(vt.getName().toString(), type));
            }
            result.set(params);
        }, true);
        return result.get();
    }

    private static int indexOf(List<Declared> declared, String name) {
        for (int i = 0; i < declared.size(); i++) {
            if (declared.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static String names(List<Declared> declared) {
        return declared.isEmpty() ? "(none)"
                : String.join(", ", declared.stream().map(d -> d.type() + " " + d.name()).toList());
    }
}
