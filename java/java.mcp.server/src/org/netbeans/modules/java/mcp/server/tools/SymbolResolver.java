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

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Types;
import org.netbeans.api.java.source.CompilationInfo;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.TreePathHandle;
import org.openide.filesystems.FileObject;

/**
 * Resolves a {@link SymbolSpec} to a declaration in the workspace sources.
 */
final class SymbolResolver {

    /**
     * A resolved symbol.
     *
     * @param handle handle usable by the refactoring API
     * @param signature canonical symbol string, in the same syntax agents use as input
     * @param kind element kind, e.g. METHOD
     * @param file source file declaring the symbol
     * @param simpleName simple name of the element, e.g. {@code save} or {@code Order}
     * @param topLevelType whether the element is a top-level type (declared directly in its file)
     */
    record Resolved(TreePathHandle handle, String signature, ElementKind kind, FileObject file,
            String simpleName, boolean topLevelType) {
    }

    /** Resolution failed in a way the agent can fix; the message says how. */
    static final class ResolutionException extends Exception {

        ResolutionException(String message) {
            super(message);
        }
    }

    private SymbolResolver() {
    }

    static Resolved resolve(SymbolSpec spec, List<FileObject> sourceRoots) throws IOException, ResolutionException {
        FileObject file = findSourceFile(spec.typeName(), sourceRoots);
        if (file == null) {
            throw new ResolutionException("No source for type " + spec.typeName()
                    + " in the workspace. The symbol must be fully qualified and declared in the workspace "
                    + "sources (library types are not supported yet). Use find_symbol to look up the "
                    + "qualified name.");
        }
        JavaSource js = JavaSource.forFileObject(file);
        if (js == null) {
            throw new ResolutionException("Cannot parse " + file.getPath());
        }
        AtomicReference<Resolved> result = new AtomicReference<>();
        AtomicReference<String> problem = new AtomicReference<>();
        js.runUserActionTask(cc -> {
            cc.toPhase(JavaSource.Phase.RESOLVED);
            TypeElement type = cc.getElements().getTypeElement(spec.typeName());
            if (type == null) {
                problem.set("Type " + spec.typeName() + " not found in " + file.getNameExt());
                return;
            }
            Element target = type;
            if (spec.member() != null) {
                List<Element> candidates = new ArrayList<>();
                List<Element> sameName = new ArrayList<>();
                for (Element e : type.getEnclosedElements()) {
                    if (!nameMatches(spec, e)) {
                        continue;
                    }
                    sameName.add(e);
                    if (spec.parameterTypes() == null || parametersMatch(spec, e, cc.getTypes())) {
                        candidates.add(e);
                    }
                }
                if (candidates.isEmpty()) {
                    problem.set(sameName.isEmpty()
                            ? "No member '" + spec.member() + "' in " + spec.typeName()
                            : "No overload of '" + spec.member() + "' with parameters " + spec.parameterTypes()
                            + ". Candidates: " + signatures(sameName, cc));
                    return;
                }
                if (candidates.size() > 1) {
                    problem.set("'" + spec.member() + "' is ambiguous, add parameter types. Candidates: "
                            + signatures(candidates, cc));
                    return;
                }
                target = candidates.get(0);
            }
            result.set(new Resolved(TreePathHandle.create(target, cc), signature(target, cc), target.getKind(), file,
                    target.getSimpleName().toString(),
                    target instanceof TypeElement te && !te.getNestingKind().isNested()));
        }, true);
        if (result.get() == null) {
            throw new ResolutionException(problem.get() != null ? problem.get() : "Cannot resolve " + spec);
        }
        return result.get();
    }

    /**
     * Finds the file declaring a top-level or nested type by trying
     * {@code a/b/C.java}, then treating trailing segments as nested classes.
     */
    static FileObject findSourceFile(String typeName, List<FileObject> sourceRoots) {
        String[] segments = typeName.split("\\.");
        for (int i = segments.length; i >= 1; i--) {
            String path = String.join("/", Arrays.copyOfRange(segments, 0, i)) + ".java";
            for (FileObject root : sourceRoots) {
                FileObject fo = root.getFileObject(path);
                if (fo != null && fo.isData()) {
                    return fo;
                }
            }
        }
        return null;
    }

    private static boolean nameMatches(SymbolSpec spec, Element e) {
        if (spec.isConstructor()) {
            return e.getKind() == ElementKind.CONSTRUCTOR;
        }
        return switch (e.getKind()) {
            case METHOD, FIELD, ENUM_CONSTANT, RECORD_COMPONENT -> e.getSimpleName().contentEquals(spec.member());
            default -> false;
        };
    }

    private static boolean parametersMatch(SymbolSpec spec, Element e, Types types) {
        if (!(e instanceof ExecutableElement ee)) {
            return false;
        }
        List<? extends VariableElement> params = ee.getParameters();
        if (params.size() != spec.parameterTypes().size()) {
            return false;
        }
        for (int i = 0; i < params.size(); i++) {
            String erasure = types.erasure(params.get(i).asType()).toString();
            if (!SymbolSpec.parameterMatches(spec.parameterTypes().get(i), erasure)) {
                return false;
            }
        }
        return true;
    }

    private static String signatures(List<Element> elements, CompilationInfo info) {
        return elements.stream().map(e -> signature(e, info)).collect(Collectors.joining(", "));
    }

    /**
     * Line (1-based) where an element is declared in the compilation unit of
     * {@code info}, or 0 if it has no source position there.
     */
    // the replacement of getStartPosition(CompilationUnitTree, Tree) is not in the javac API we build against
    @SuppressWarnings("deprecation")
    static int line(CompilationInfo info, Element e) {
        Tree tree = info.getTrees().getTree(e);
        if (tree == null) {
            return 0;
        }
        CompilationUnitTree cu = info.getCompilationUnit();
        long pos = info.getTrees().getSourcePositions().getStartPosition(cu, tree);
        return pos < 0 ? 0 : (int) cu.getLineMap().getLineNumber(pos);
    }

    /**
     * Start and end offset of a tree in the compilation unit of {@code info},
     * or {@code null} if it has no source position.
     */
    // the replacements of the (CompilationUnitTree, Tree) methods are not in the javac API we build against
    @SuppressWarnings("deprecation")
    static int[] span(CompilationInfo info, Tree tree) {
        CompilationUnitTree cu = info.getCompilationUnit();
        long start = info.getTrees().getSourcePositions().getStartPosition(cu, tree);
        long end = info.getTrees().getSourcePositions().getEndPosition(cu, tree);
        return start < 0 || end < start ? null : new int[]{(int) start, (int) end};
    }

    /** Canonical symbol string of an element, in the syntax {@link SymbolSpec} parses. */
    static String signature(Element e, CompilationInfo info) {
        if (e instanceof TypeElement te) {
            return te.getQualifiedName().toString();
        }
        String owner = ((TypeElement) e.getEnclosingElement()).getQualifiedName().toString();
        if (e instanceof ExecutableElement ee) {
            String name = ee.getKind() == ElementKind.CONSTRUCTOR ? SymbolSpec.CONSTRUCTOR : ee.getSimpleName().toString();
            String params = ee.getParameters().stream()
                    .map(p -> info.getTypes().erasure(p.asType()).toString())
                    .collect(Collectors.joining(","));
            return owner + "#" + name + "(" + params + ")";
        }
        return owner + "#" + e.getSimpleName();
    }
}
