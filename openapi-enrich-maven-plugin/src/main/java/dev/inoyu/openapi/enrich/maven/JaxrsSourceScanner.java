/*
 * Copyright 2026 Inoyu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.inoyu.openapi.enrich.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.JavadocBlockTag;
import com.github.javaparser.javadoc.description.JavadocDescription;

import dev.inoyu.openapi.enrich.model.DocPack;
import dev.inoyu.openapi.enrich.model.OperationDoc;
import dev.inoyu.openapi.enrich.model.ParamDoc;
import dev.inoyu.openapi.enrich.model.PropertyDoc;
import dev.inoyu.openapi.enrich.model.ResponseDoc;
import dev.inoyu.openapi.enrich.model.SchemaDoc;
import dev.inoyu.openapi.enrich.model.ServletOperationDoc;

/**
 * Scans Java sources for JAX-RS resources and servlet endpoints and builds a {@link DocPack}.
 */
public class JaxrsSourceScanner {

    private static final Set<String> HTTP_METHODS = Set.of(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    private static final Pattern LINK_PATTERN = Pattern.compile("\\{@\\s*link\\s+([\\w.$]+)\\s*}");
    private static final Pattern STATUS_LINE = Pattern.compile("^(\\d{3})(?:\\s+(.+))?$");
    private static final Pattern JAVADOC_CODE = Pattern.compile("\\{@\\s*code\\s+([^}]+)\\s*}");
    private static final Pattern JAVADOC_LINK = Pattern.compile(
            "\\{@\\s*link(?:plain)?\\s+([\\w.$/#]+)(?:\\s+[^}]*)?\\s*}");
    private static final Pattern JAVADOC_LITERAL = Pattern.compile("\\{@\\s*literal\\s+([^}]+)\\s*}");
    private static final Pattern REGISTER_SERVLET = Pattern.compile("registerServlet\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern STRING_CONST_PATH = Pattern.compile(
            "(?:PATH|SERVLET_PATH|URL_PATTERN)\\s*=\\s*\"(/[^\"]*)\"");

    private final List<Path> sourceRoots;
    private final List<Path> extraSourceRoots;
    private final List<String> includePackages;
    private final String statusTag;
    private final String bundleId;
    private final Map<String, String> servletPaths;
    /** Populated during {@link #scan()} for wildcard-import resolution. */
    private Map<String, Path> typeIndex = Map.of();
    /**
     * Envelope types (e.g. PartialList&lt;T&gt;) → item FQCN recorded while inferring responses,
     * applied to the envelope schema's {@code list} property when extracting schemas.
     */
    private final Map<String, String> envelopeItemTypes = new LinkedHashMap<>();

    public JaxrsSourceScanner(List<Path> sourceRoots,
                              List<Path> extraSourceRoots,
                              List<String> includePackages,
                              String statusTag,
                              String bundleId,
                              Map<String, String> servletPaths) {
        this.sourceRoots = sourceRoots != null ? sourceRoots : List.of();
        this.extraSourceRoots = extraSourceRoots != null ? extraSourceRoots : List.of();
        this.includePackages = includePackages != null ? includePackages : List.of();
        this.statusTag = statusTag != null && !statusTag.isBlank() ? statusTag : "api.status";
        this.bundleId = bundleId;
        this.servletPaths = servletPaths != null ? servletPaths : Map.of();
    }

    public DocPack scan() throws IOException {
        DocPack pack = new DocPack();
        pack.setFormatVersion(DocPack.CURRENT_FORMAT_VERSION);
        pack.setBundleId(bundleId);
        envelopeItemTypes.clear();

        List<Path> allRoots = new ArrayList<>();
        allRoots.addAll(sourceRoots);
        allRoots.addAll(extraSourceRoots);

        Map<String, Path> typeIndex = indexJavaFiles(allRoots);
        this.typeIndex = typeIndex;
        Set<String> schemaRefs = new LinkedHashSet<>();

        for (Path root : sourceRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                List<Path> files = walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
                for (Path file : files) {
                    scanFile(file, pack, schemaRefs);
                }
            }
        }

        // Nested property types (ApiKey, enums, …) may add more refs while extracting.
        List<String> pending = new ArrayList<>(schemaRefs);
        Set<String> seen = new LinkedHashSet<>(schemaRefs);
        while (!pending.isEmpty()) {
            String fqcn = pending.remove(0);
            if (pack.getSchemas().containsKey(fqcn)) {
                continue;
            }
            Path source = typeIndex.get(fqcn);
            if (source == null) {
                continue;
            }
            Set<String> nestedRefs = new LinkedHashSet<>();
            SchemaDoc schema = extractSchema(source, fqcn, typeIndex, nestedRefs);
            if (schema != null) {
                pack.getSchemas().put(fqcn, schema);
            }
            for (String nested : nestedRefs) {
                if (seen.add(nested)) {
                    pending.add(nested);
                    schemaRefs.add(nested);
                }
            }
        }

        return pack;
    }

    /** Parse a single compilation unit from source text (unit tests). */
    public DocPack scanSource(String javaSource) {
        DocPack pack = new DocPack();
        pack.setFormatVersion(DocPack.CURRENT_FORMAT_VERSION);
        pack.setBundleId(bundleId);
        envelopeItemTypes.clear();
        Set<String> schemaRefs = new LinkedHashSet<>();
        CompilationUnit cu = StaticJavaParser.parse(javaSource);
        scanCompilationUnit(cu, pack, schemaRefs);
        return pack;
    }

    private void scanFile(Path file, DocPack pack, Set<String> schemaRefs) throws IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        CompilationUnit cu = StaticJavaParser.parse(source);
        if (!includePackages.isEmpty()) {
            String packageName = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            boolean allowed = includePackages.stream().anyMatch(packageName::startsWith);
            if (!allowed) {
                return;
            }
        }
        scanCompilationUnit(cu, pack, schemaRefs);
    }

    private void scanCompilationUnit(CompilationUnit cu, DocPack pack, Set<String> schemaRefs) {
        String packageName = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        for (ClassOrInterfaceDeclaration type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String fqcn = qualify(packageName, type.getNameAsString());
            if (hasPathAnnotation(type)) {
                scanResource(type, fqcn, pack, schemaRefs);
            }
            if (extendsHttpServlet(type)) {
                scanServlet(type, fqcn, cu, pack, schemaRefs);
            }
        }
    }

    private void scanResource(ClassOrInterfaceDeclaration type, String fqcn, DocPack pack, Set<String> schemaRefs) {
        String classPath = annotationPathValue(type).orElse("");
        Set<String> seenMethodNames = new LinkedHashSet<>();
        Set<String> seenOperationKeys = new LinkedHashSet<>();
        Map<String, String> localBindings = Map.of();
        for (MethodDeclaration method : type.getMethods()) {
            emitResourceMethod(method, fqcn, classPath, pack, schemaRefs, localBindings,
                    seenMethodNames, seenOperationKeys);
        }
        scanInheritedResourceMethods(type, fqcn, classPath, pack, schemaRefs,
                seenMethodNames, seenOperationKeys);
    }

    private void emitResourceMethod(MethodDeclaration method,
                                    String concreteFqcn,
                                    String classPath,
                                    DocPack pack,
                                    Set<String> schemaRefs,
                                    Map<String, String> typeVarBindings,
                                    Set<String> seenMethodNames,
                                    Set<String> seenOperationKeys) {
        Optional<String> http = httpMethod(method);
        if (http.isEmpty()) {
            return;
        }
        String methodName = method.getNameAsString();
        // Deduplicate by HTTP+path only so Java overloads (same method name, different paths) are kept.
        // Inheritance overrides with the same operationKey are skipped when the subclass already emitted them.
        seenMethodNames.add(methodName);
        String methodPath = annotationPathValue(method).orElse("");
        String path = joinPaths(classPath, methodPath);
        String operationKey = http.get() + " " + path;
        if (!seenOperationKeys.add(operationKey)) {
            return;
        }

        OperationDoc op = new OperationDoc();
        op.setClassName(concreteFqcn);
        op.setMethodName(methodName);
        op.setHttpMethod(http.get());
        op.setPath(path);
        op.setOperationKey(operationKey);

        CompilationUnit cu = method.findCompilationUnit().orElse(null);
        applyJavadoc(method, op, schemaRefs, typeVarBindings);
        ensureDefaultResponse(method, op, schemaRefs, typeVarBindings);
        collectRequestBodySchema(method, op, schemaRefs, typeVarBindings, cu);
        pack.getOperations().add(op);
    }

    /**
     * Walk {@code extends} chain and emit HTTP methods declared on concrete superclasses,
     * substituting type variables from {@code extends Base&lt;Concrete&gt;}.
     */
    private void scanInheritedResourceMethods(ClassOrInterfaceDeclaration type,
                                              String concreteFqcn,
                                              String classPath,
                                              DocPack pack,
                                              Set<String> schemaRefs,
                                              Set<String> seenMethodNames,
                                              Set<String> seenOperationKeys) {
        ClassOrInterfaceDeclaration current = type;
        CompilationUnit currentCu = type.findCompilationUnit().orElse(null);
        Map<String, String> bindings = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();

        while (!current.getExtendedTypes().isEmpty()) {
            ClassOrInterfaceType parentType = current.getExtendedTypes().get(0);
            String parentFqcn = resolveTypeName(currentCu, parentType.getNameAsString());
            if (parentFqcn == null || isJdkType(parentFqcn) || !visited.add(parentFqcn)) {
                break;
            }
            Path parentSource = typeIndex.get(parentFqcn);
            if (parentSource == null) {
                break;
            }
            CompilationUnit parentCu;
            try {
                parentCu = StaticJavaParser.parse(Files.readString(parentSource, StandardCharsets.UTF_8));
            } catch (IOException e) {
                break;
            }
            Optional<ClassOrInterfaceDeclaration> parentOpt = findNestedType(parentCu, parentFqcn);
            if (parentOpt.isEmpty() || parentOpt.get().isInterface()) {
                break;
            }
            ClassOrInterfaceDeclaration parent = parentOpt.get();
            Map<String, String> layer = bindTypeArguments(currentCu, parentType, parent, bindings);
            bindings.putAll(layer);

            for (MethodDeclaration method : parent.getMethods()) {
                emitResourceMethod(method, concreteFqcn, classPath, pack, schemaRefs, bindings,
                        seenMethodNames, seenOperationKeys);
            }

            current = parent;
            currentCu = parentCu;
        }
    }

    /**
     * Map parent type-parameter names to concrete type names from {@code extends Parent&lt;Arg&gt;}.
     */
    private Map<String, String> bindTypeArguments(CompilationUnit childCu,
                                                  ClassOrInterfaceType parentType,
                                                  ClassOrInterfaceDeclaration parentDecl,
                                                  Map<String, String> existingBindings) {
        Map<String, String> map = new LinkedHashMap<>();
        if (parentDecl.getTypeParameters().isEmpty() || parentType.getTypeArguments().isEmpty()) {
            return map;
        }
        java.util.List<com.github.javaparser.ast.type.TypeParameter> typeParams = parentDecl.getTypeParameters();
        java.util.List<Type> args = parentType.getTypeArguments().get();
        for (int i = 0; i < Math.min(typeParams.size(), args.size()); i++) {
            String paramName = typeParams.get(i).getNameAsString();
            Type arg = args.get(i);
            String argName = arg.isClassOrInterfaceType()
                    ? arg.asClassOrInterfaceType().getNameAsString()
                    : arg.asString();
            argName = substituteTypeVar(argName, existingBindings);
            String fqcn = resolveTypeName(childCu, argName);
            map.put(paramName, fqcn != null ? fqcn : argName);
        }
        return map;
    }

    private static String substituteTypeVar(String name, Map<String, String> bindings) {
        if (name == null || bindings == null || bindings.isEmpty()) {
            return name;
        }
        String cur = name;
        Set<String> seen = new HashSet<>();
        while (bindings.containsKey(cur) && seen.add(cur)) {
            cur = bindings.get(cur);
        }
        return cur;
    }

    private void collectRequestBodySchema(MethodDeclaration method,
                                          OperationDoc op,
                                          Set<String> schemaRefs,
                                          Map<String, String> typeVarBindings,
                                          CompilationUnit cu) {
        for (com.github.javaparser.ast.body.Parameter param : method.getParameters()) {
            boolean jaxRsInjected = hasParamAnnotation(param,
                    "PathParam", "HeaderParam", "CookieParam", "FormParam", "MatrixParam", "Context", "BeanParam");
            if (jaxRsInjected) {
                continue;
            }
            boolean queryParam = hasParamAnnotation(param, "QueryParam");
            Type type = param.getType();
            if (isResponseOrVoid(type) || type.isPrimitiveType()) {
                continue;
            }
            String simple = type.isClassOrInterfaceType()
                    ? type.asClassOrInterfaceType().getNameAsString()
                    : type.asString();
            simple = substituteTypeVar(simple, typeVarBindings);
            if (isJdkType(simple) || isJdkType(type.asString())) {
                continue;
            }
            String fqcn = resolveTypeNameWithBindings(cu, simple, typeVarBindings);
            if (fqcn == null || isJdkType(fqcn)) {
                continue;
            }
            schemaRefs.add(fqcn);
            // True entity body only (not @QueryParam or other JAX-RS param annotations).
            if (!queryParam && op.getRequestBodySchemaClass() == null) {
                op.setRequestBodySchemaClass(fqcn);
            }
        }
    }

    /**
     * Maps Java method parameter names to their public JAX-RS names
     * ({@code @PathParam}/{@code @QueryParam}/… value). When the annotation
     * value differs from the Java name (e.g. {@code @QueryParam("key") String apiKey}),
     * OpenAPI enrichment must key docs under the annotation value.
     */
    private static Map<String, String> javaToApiParamNames(MethodDeclaration method) {
        Map<String, String> map = new LinkedHashMap<>();
        for (com.github.javaparser.ast.body.Parameter param : method.getParameters()) {
            String javaName = param.getNameAsString();
            String apiName = jaxRsParamName(param).orElse(javaName);
            map.put(javaName, apiName);
        }
        return map;
    }

    private static Optional<String> jaxRsParamName(com.github.javaparser.ast.body.Parameter param) {
        for (AnnotationExpr ann : param.getAnnotations()) {
            String simple = simpleAnnotationName(ann);
            if (!Set.of("PathParam", "QueryParam", "HeaderParam", "CookieParam", "FormParam", "MatrixParam")
                    .contains(simple)) {
                continue;
            }
            Optional<String> value = annotationStringValue(ann);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> annotationStringValue(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr) {
            Expression member = ((SingleMemberAnnotationExpr) ann).getMemberValue();
            if (member instanceof StringLiteralExpr) {
                return Optional.of(((StringLiteralExpr) member).getValue());
            }
        } else if (ann instanceof NormalAnnotationExpr) {
            for (MemberValuePair pair : ((NormalAnnotationExpr) ann).getPairs()) {
                if ("value".equals(pair.getNameAsString()) && pair.getValue().isStringLiteralExpr()) {
                    return Optional.of(pair.getValue().asStringLiteralExpr().getValue());
                }
            }
        }
        return Optional.empty();
    }

    private static boolean hasParamAnnotation(com.github.javaparser.ast.body.Parameter param, String... names) {
        Set<String> want = Set.of(names);
        for (AnnotationExpr a : param.getAnnotations()) {
            if (want.contains(simpleAnnotationName(a))) {
                return true;
            }
        }
        return false;
    }

    private void ensureDefaultResponse(MethodDeclaration method, OperationDoc op, Set<String> schemaRefs) {
        ensureDefaultResponse(method, op, schemaRefs, Map.of());
    }

    private void ensureDefaultResponse(MethodDeclaration method,
                                       OperationDoc op,
                                       Set<String> schemaRefs,
                                       Map<String, String> typeVarBindings) {
        CompilationUnit cu = method.findCompilationUnit().orElse(null);
        InferredBody inferred = inferResponseBody(method.getType(), cu, typeVarBindings);

        if (op.getResponses().isEmpty()) {
            if (isResponseOrVoid(method.getType())) {
                return;
            }
            ResponseDoc ok = new ResponseDoc();
            ok.setDescription("");
            ok.setSchemaClass(inferred.fqcn);
            ok.setArray(inferred.array);
            if (inferred.fqcn != null) {
                schemaRefs.add(inferred.fqcn);
            }
            if (inferred.itemFqcn != null) {
                schemaRefs.add(inferred.itemFqcn);
            }
            op.getResponses().put("200", ok);
            return;
        }

        ResponseDoc ok = op.getResponses().get("200");
        if (ok != null && (ok.getSchemaClass() == null || ok.getSchemaClass().isBlank()) && inferred.fqcn != null) {
            ok.setSchemaClass(inferred.fqcn);
            ok.setArray(inferred.array);
            schemaRefs.add(inferred.fqcn);
        }
        // Even when @api.status already named PartialList (or similar), still register
        // the T item schema so nested fields/examples are generated.
        if (inferred.itemFqcn != null) {
            schemaRefs.add(inferred.itemFqcn);
        }
    }

    private static final class InferredBody {
        final String fqcn;
        final boolean array;
        final String itemFqcn;

        InferredBody(String fqcn, boolean array) {
            this(fqcn, array, null);
        }

        InferredBody(String fqcn, boolean array, String itemFqcn) {
            this.fqcn = fqcn;
            this.array = array;
            this.itemFqcn = itemFqcn;
        }
    }

    private InferredBody inferResponseBody(Type returnType, CompilationUnit cu) {
        return inferResponseBody(returnType, cu, Map.of());
    }

    private InferredBody inferResponseBody(Type returnType, CompilationUnit cu, Map<String, String> typeVarBindings) {
        if (returnType != null && returnType.isClassOrInterfaceType()) {
            ClassOrInterfaceType cit = returnType.asClassOrInterfaceType();
            String name = cit.getNameAsString();
            if ("PartialList".equals(name)) {
                String envelopeFqcn = resolveTypeNameWithBindings(cu, "PartialList", typeVarBindings);
                String itemFqcn = null;
                if (cit.getTypeArguments().isPresent() && !cit.getTypeArguments().get().isEmpty()) {
                    Type arg = cit.getTypeArguments().get().get(0);
                    String item = arg.isClassOrInterfaceType()
                            ? arg.asClassOrInterfaceType().getNameAsString()
                            : arg.asString();
                    item = substituteTypeVar(item, typeVarBindings);
                    itemFqcn = resolveTypeNameWithBindings(cu, item, typeVarBindings);
                }
                if (envelopeFqcn != null && itemFqcn != null) {
                    envelopeItemTypes.put(envelopeFqcn, itemFqcn);
                }
                return new InferredBody(envelopeFqcn, false, itemFqcn);
            }
            if (typeVarBindings != null && typeVarBindings.containsKey(name)
                    && cit.getTypeArguments().isEmpty()) {
                String bound = typeVarBindings.get(name);
                String fqcn = resolveTypeNameWithBindings(cu, bound, typeVarBindings);
                return new InferredBody(fqcn, false, null);
            }
        }

        String inferred = inferSchemaClass(returnType);
        if (inferred == null) {
            return new InferredBody(null, false, null);
        }
        inferred = substituteTypeVar(inferred, typeVarBindings);

        boolean array = false;
        String item = inferred;
        if ("List".equals(inferred) || "Set".equals(inferred) || "Collection".equals(inferred)
                || inferred.startsWith("List<") || inferred.startsWith("Set<") || inferred.startsWith("Collection<")) {
            array = true;
            if (returnType.isClassOrInterfaceType()) {
                ClassOrInterfaceType cit = returnType.asClassOrInterfaceType();
                if (cit.getTypeArguments().isPresent() && !cit.getTypeArguments().get().isEmpty()) {
                    Type arg = cit.getTypeArguments().get().get(0);
                    item = arg.isClassOrInterfaceType()
                            ? arg.asClassOrInterfaceType().getNameAsString()
                            : arg.asString();
                    item = substituteTypeVar(item, typeVarBindings);
                } else {
                    item = null;
                }
            } else {
                item = null;
            }
        }
        String fqcn = item == null ? null : resolveTypeNameWithBindings(cu, item, typeVarBindings);
        return new InferredBody(fqcn, array, null);
    }

    private void applyJavadoc(MethodDeclaration method, OperationDoc op, Set<String> schemaRefs) {
        applyJavadoc(method, op, schemaRefs, Map.of());
    }

    private void applyJavadoc(MethodDeclaration method,
                              OperationDoc op,
                              Set<String> schemaRefs,
                              Map<String, String> typeVarBindings) {
        Optional<Javadoc> javadoc = method.getJavadoc();
        if (javadoc.isEmpty()) {
            return;
        }
        Javadoc doc = javadoc.get();
        String body = descriptionText(doc.getDescription()).trim();
        if (!body.isEmpty()) {
            int sentenceEnd = firstSentenceEnd(body);
            if (sentenceEnd > 0 && sentenceEnd < body.length()) {
                op.setSummary(body.substring(0, sentenceEnd).trim());
                String rest = body.substring(sentenceEnd).trim();
                op.setDescription(rest.isEmpty() ? null : rest);
            } else {
                op.setSummary(body);
            }
        }

        // Javadoc @param uses the Java parameter name; OpenAPI/CXF use @QueryParam/@PathParam values.
        Map<String, String> javaToApiParam = javaToApiParamNames(method);

        String returnDescription = null;
        String normalizedStatusTag = statusTag.startsWith("@") ? statusTag.substring(1) : statusTag;
        for (JavadocBlockTag tag : doc.getBlockTags()) {
            String tagName = tag.getTagName();
            if ("param".equalsIgnoreCase(tagName)) {
                String javaName = tag.getName().orElse("").trim();
                if (javaName.isEmpty()) {
                    continue;
                }
                String apiName = javaToApiParam.getOrDefault(javaName, javaName);
                ParamDoc param = new ParamDoc();
                param.setDescription(descriptionText(tag.getContent()).trim());
                op.getParameters().put(apiName, param);
            } else if ("return".equalsIgnoreCase(tagName)) {
                returnDescription = descriptionText(tag.getContent()).trim();
            } else if (normalizedStatusTag.equals(tagName) || statusTag.equals(tagName)) {
                parseStatusTag(descriptionText(tag.getContent()).trim(), op, schemaRefs,
                        method.findCompilationUnit().orElse(null), typeVarBindings);
            } else if ("api.example".equals(tagName)) {
                applyOperationExample(op, descriptionText(tag.getContent()).trim());
            }
        }

        if (returnDescription != null && !returnDescription.isEmpty()) {
            ResponseDoc target = op.getResponses().get("200");
            if (target == null) {
                // Prefer an existing 2xx from @api.status (e.g. 204) over inventing 200.
                target = op.getResponses().entrySet().stream()
                        .filter(e -> e.getKey().startsWith("2"))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElse(null);
            }
            if (target == null) {
                target = op.getResponses().computeIfAbsent("200", code -> new ResponseDoc());
            }
            if (target.getDescription() == null || target.getDescription().isBlank()) {
                target.setDescription(returnDescription);
            }
        }
    }

    private void parseStatusTag(String content, OperationDoc op, Set<String> schemaRefs, CompilationUnit cu) {
        parseStatusTag(content, op, schemaRefs, cu, Map.of());
    }

    private void parseStatusTag(String content, OperationDoc op, Set<String> schemaRefs, CompilationUnit cu,
                                Map<String, String> typeVarBindings) {
        Matcher m = STATUS_LINE.matcher(content.trim());
        if (!m.matches()) {
            return;
        }
        String code = m.group(1);
        String rest = m.group(2) != null ? m.group(2).trim() : "";
        ResponseDoc response = op.getResponses().computeIfAbsent(code, c -> new ResponseDoc());

        if (rest.isEmpty()) {
            if (response.getDescription() == null) {
                response.setDescription("");
            }
            return;
        }

        boolean array = false;
        String remainder = rest;
        if (remainder.toLowerCase(Locale.ROOT).startsWith("array ")) {
            array = true;
            remainder = remainder.substring(6).trim();
        }

        if (remainder.isEmpty() || "empty".equalsIgnoreCase(remainder.split("\\s+")[0])) {
            String desc = descriptionAfterType(remainder, "empty");
            if (desc != null) {
                response.setDescription(desc);
            } else if (response.getDescription() == null) {
                response.setDescription("");
            }
            return;
        }

        Matcher link = LINK_PATTERN.matcher(remainder);
        String typeName;
        String afterType;
        if (link.find() && link.start() == 0) {
            typeName = link.group(1);
            afterType = remainder.substring(link.end()).trim();
        } else {
            String[] parts = remainder.split("\\s+", 2);
            typeName = parts[0];
            afterType = parts.length > 1 ? parts[1].trim() : "";
        }

        if ("empty".equalsIgnoreCase(typeName)) {
            if (!afterType.isEmpty()) {
                response.setDescription(afterType);
            } else if (response.getDescription() == null) {
                response.setDescription("");
            }
            return;
        }

        String fqcn = resolveTypeNameWithBindings(cu, typeName, typeVarBindings);
        response.setSchemaClass(fqcn);
        response.setArray(array);
        if (!afterType.isEmpty()) {
            response.setDescription(afterType);
        } else if (response.getDescription() == null) {
            response.setDescription("");
        }
        if (fqcn != null) {
            schemaRefs.add(fqcn);
        }
    }

    private static String descriptionAfterType(String remainder, String typeToken) {
        if (remainder == null || remainder.isBlank()) {
            return null;
        }
        String[] parts = remainder.split("\\s+", 2);
        if (parts.length < 2) {
            return null;
        }
        if (!typeToken.equalsIgnoreCase(parts[0])) {
            return null;
        }
        return parts[1].trim();
    }

    private void scanServlet(ClassOrInterfaceDeclaration type, String fqcn, CompilationUnit cu, DocPack pack,
                             Set<String> schemaRefs) {
        String path = servletPaths.get(fqcn);
        if (path == null) {
            path = servletPaths.get(type.getNameAsString());
        }
        if (path == null) {
            path = findServletPath(cu, type).orElse(null);
        }
        if (path == null) {
            return;
        }

        ServletOperationDoc op = new ServletOperationDoc();
        op.setClassName(fqcn);
        op.setMethodName("service");
        op.setHttpMethod("GET");
        op.setPath(normalizePath(path));
        op.setOperationKey("GET " + op.getPath());
        op.getServers().add("/");

        type.getJavadoc().ifPresent(doc -> {
            String body = descriptionText(doc.getDescription()).trim();
            if (!body.isEmpty()) {
                int sentenceEnd = firstSentenceEnd(body);
                if (sentenceEnd > 0 && sentenceEnd < body.length()) {
                    op.setSummary(body.substring(0, sentenceEnd).trim());
                    String rest = body.substring(sentenceEnd).trim();
                    op.setDescription(rest.isEmpty() ? null : rest);
                } else {
                    op.setSummary(body);
                }
            }
            String normalizedStatusTag = statusTag.startsWith("@") ? statusTag.substring(1) : statusTag;
            for (JavadocBlockTag tag : doc.getBlockTags()) {
                String tagName = tag.getTagName();
                if (normalizedStatusTag.equals(tagName) || statusTag.equals(tagName)) {
                    parseStatusTag(descriptionText(tag.getContent()).trim(), op, schemaRefs, cu);
                } else if ("api.example".equals(tagName)) {
                    applyOperationExample(op, descriptionText(tag.getContent()).trim());
                }
            }
        });

        pack.getServletOperations().add(op);
    }

    private Optional<String> findServletPath(CompilationUnit cu, ClassOrInterfaceDeclaration type) {
        String text = cu.toString();
        Matcher reg = REGISTER_SERVLET.matcher(text);
        if (reg.find()) {
            return Optional.of(reg.group(1));
        }
        Matcher constPath = STRING_CONST_PATH.matcher(text);
        if (constPath.find()) {
            return Optional.of(constPath.group(1));
        }
        for (FieldDeclaration field : type.getFields()) {
            for (VariableDeclarator var : field.getVariables()) {
                if (var.getInitializer().isPresent() && var.getInitializer().get().isStringLiteralExpr()) {
                    String value = var.getInitializer().get().asStringLiteralExpr().getValue();
                    if (value.startsWith("/")) {
                        String name = var.getNameAsString().toUpperCase(Locale.ROOT);
                        if (name.contains("PATH") || name.contains("URL") || name.contains("PATTERN")) {
                            return Optional.of(value);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private SchemaDoc extractSchema(Path source, String fqcn, Map<String, Path> typeIndex, Set<String> nestedRefs)
            throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(Files.readString(source, StandardCharsets.UTF_8));
        Optional<ClassOrInterfaceDeclaration> type = findNestedType(cu, fqcn);
        if (type.isEmpty()) {
            String simple = simpleName(fqcn);
            Optional<EnumDeclaration> enumType = cu.findAll(EnumDeclaration.class).stream()
                    .filter(e -> e.getNameAsString().equals(simple) || qualify(cu.getPackageDeclaration()
                            .map(p -> p.getNameAsString()).orElse(""), e.getNameAsString()).equals(fqcn)
                            || (cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("") + "." + simple).equals(fqcn)
                                    && e.getNameAsString().equals(simple))
                    .findFirst();
            if (enumType.isPresent()) {
                return extractEnumSchema(enumType.get());
            }
            // Nested enums: org.foo.Outer.Status
            int lastDot = fqcn.lastIndexOf('.');
            if (lastDot > 0) {
                String nested = fqcn.substring(lastDot + 1);
                Optional<EnumDeclaration> nestedEnum = cu.findAll(EnumDeclaration.class).stream()
                        .filter(e -> e.getNameAsString().equals(nested))
                        .findFirst();
                if (nestedEnum.isPresent()) {
                    return extractEnumSchema(nestedEnum.get());
                }
            }
            return null;
        }
        SchemaDoc schema = new SchemaDoc();
        type.get().getJavadoc().ifPresent(doc ->
                schema.setDescription(descriptionText(doc.getDescription()).trim()));

        String parentFqcn = resolveParentFqcn(type.get(), cu);
        if (parentFqcn != null) {
            schema.setParentFqcn(parentFqcn);
            nestedRefs.add(parentFqcn);
        }

        Map<String, PropertyDoc> props = new LinkedHashMap<>();
        List<String> suppressed = new ArrayList<>();
        collectDeclaredProperties(type.get(), cu, typeIndex, nestedRefs, props, suppressed);
        if (envelopeItemTypes.containsKey(fqcn) && props.containsKey("list")) {
            props.get("list").setItemsSchemaClass(envelopeItemTypes.get(fqcn));
        }
        schema.setProperties(props);
        if (!suppressed.isEmpty()) {
            schema.setSuppressedProperties(suppressed);
        }
        return schema;
    }

    /**
     * Collect properties declared on {@code type} itself (fields + documented getters).
     * Skips {@code transient}, {@code @JsonIgnore}, and {@code @XmlTransient} members.
     * <p>
     * Serialization aliases ({@code @JsonProperty}, {@code @XmlElement}/{@code @XmlAttribute} {@code name})
     * are read primarily from <strong>getters/setters</strong> (Unomi convention), then fields.
     * Pack map keys use the wire name when an alias is present; {@link PropertyDoc#getJavaName()}
     * keeps the Java bean name for enricher matching against CXF schemas that still use bean names.
     * Field Javadoc wins over getter Javadoc when both set description/example.
     */
    private void collectDeclaredProperties(ClassOrInterfaceDeclaration type,
                                           CompilationUnit cu,
                                           Map<String, Path> typeIndex,
                                           Set<String> nestedRefs,
                                           Map<String, PropertyDoc> props,
                                           List<String> suppressed) {
        for (FieldDeclaration field : type.getFields()) {
            if (field.isStatic()) {
                continue;
            }
            if (field.isTransient() || hasJsonIgnore(field.getAnnotations())
                    || hasXmlTransient(field.getAnnotations())) {
                for (VariableDeclarator var : field.getVariables()) {
                    addSuppressed(suppressed, var.getNameAsString());
                }
                continue;
            }
            for (VariableDeclarator var : field.getVariables()) {
                String javaName = var.getNameAsString();
                MethodDeclaration getter = findAccessor(type, javaName, true);
                MethodDeclaration setter = findAccessor(type, javaName, false);
                if (getterHasIgnoreAnnotation(type, javaName)
                        || (setter != null && (hasJsonIgnore(setter.getAnnotations())
                        || hasXmlTransient(setter.getAnnotations())))) {
                    addSuppressed(suppressed, javaName);
                    continue;
                }
                String wireName = resolveSerializationName(getter, setter, field.getAnnotations(), javaName);
                PropertyDoc prop = props.computeIfAbsent(wireName, n -> new PropertyDoc());
                if (!wireName.equals(javaName)) {
                    prop.setJavaName(javaName);
                }
                field.getJavadoc().ifPresent(doc -> {
                    String desc = propertyDescriptionFromJavadoc(doc);
                    if (desc != null && !desc.isBlank()) {
                        prop.setDescription(desc);
                    }
                    applyApiExampleFromJavadoc(doc, prop);
                });
                applyAccessorJavadocIfBlank(prop, setter);
                if (prop.getType() == null) {
                    mapJavaType(var.getType(), prop, type, cu, typeIndex, nestedRefs);
                }
            }
        }
        for (MethodDeclaration method : type.getMethods()) {
            String name = method.getNameAsString();
            if ((!name.startsWith("get") && !name.startsWith("is")) || !method.getParameters().isEmpty()) {
                continue;
            }
            if (hasJsonIgnore(method.getAnnotations()) || hasXmlTransient(method.getAnnotations())) {
                addSuppressed(suppressed, propertyNameFromGetter(name));
                continue;
            }
            String javaName = propertyNameFromGetter(name);
            MethodDeclaration setter = findAccessor(type, javaName, false);
            if (setter != null && (hasJsonIgnore(setter.getAnnotations()) || hasXmlTransient(setter.getAnnotations()))) {
                addSuppressed(suppressed, javaName);
                continue;
            }
            FieldDeclaration field = findField(type, javaName);
            if (field != null && (field.isTransient() || hasJsonIgnore(field.getAnnotations())
                    || hasXmlTransient(field.getAnnotations()))) {
                addSuppressed(suppressed, javaName);
                continue;
            }
            String wireName = resolveSerializationName(method, setter,
                    field != null ? field.getAnnotations() : List.of(), javaName);
            // If field pass already created under javaName, move to wire name.
            if (!wireName.equals(javaName) && props.containsKey(javaName)) {
                PropertyDoc orphan = props.remove(javaName);
                PropertyDoc prop = props.computeIfAbsent(wireName, n -> orphan != null ? orphan : new PropertyDoc());
                if (orphan != null && prop != orphan) {
                    mergePropertyDocs(prop, orphan);
                }
                prop.setJavaName(javaName);
            }
            PropertyDoc prop = props.computeIfAbsent(wireName, n -> new PropertyDoc());
            if (!wireName.equals(javaName)) {
                prop.setJavaName(javaName);
            }
            if (method.getJavadoc().isPresent()) {
                Javadoc doc = method.getJavadoc().get();
                if (prop.getDescription() == null || prop.getDescription().isBlank()) {
                    prop.setDescription(propertyDescriptionFromJavadoc(doc));
                }
                if (prop.getExample() == null) {
                    applyApiExampleFromJavadoc(doc, prop);
                }
            }
            applyAccessorJavadocIfBlank(prop, setter);
            if (prop.getType() == null) {
                mapJavaType(method.getType(), prop, type, cu, typeIndex, nestedRefs);
            }
        }
    }

    private static void addSuppressed(List<String> suppressed, String javaName) {
        if (suppressed == null || javaName == null || javaName.isBlank()) {
            return;
        }
        if (!suppressed.contains(javaName)) {
            suppressed.add(javaName);
        }
    }

    /**
     * Fill blank description/example from an accessor. Useful when
     * {@code @JsonProperty}/{@code @XmlElement} and Javadoc live on the setter.
     */
    private static void applyAccessorJavadocIfBlank(PropertyDoc prop, MethodDeclaration accessor) {
        if (prop == null || accessor == null || !accessor.getJavadoc().isPresent()) {
            return;
        }
        Javadoc doc = accessor.getJavadoc().get();
        if (prop.getDescription() == null || prop.getDescription().isBlank()) {
            String desc = propertyDescriptionFromJavadoc(doc);
            if (desc != null && !desc.isBlank()) {
                prop.setDescription(desc);
            }
        }
        if (prop.getExample() == null) {
            applyApiExampleFromJavadoc(doc, prop);
        }
    }

    private static void mergePropertyDocs(PropertyDoc target, PropertyDoc orphan) {
        if (orphan == null) {
            return;
        }
        if ((target.getDescription() == null || target.getDescription().isBlank())
                && orphan.getDescription() != null && !orphan.getDescription().isBlank()) {
            target.setDescription(orphan.getDescription());
        }
        if (target.getExample() == null && orphan.getExample() != null) {
            target.setExample(orphan.getExample());
        }
        if (target.getType() == null) {
            target.setType(orphan.getType());
            target.setFormat(orphan.getFormat());
        }
        if ((target.getEnumValues() == null || target.getEnumValues().isEmpty())
                && orphan.getEnumValues() != null) {
            target.setEnumValues(orphan.getEnumValues());
        }
        if (target.getSchemaClass() == null) {
            target.setSchemaClass(orphan.getSchemaClass());
        }
        if (target.getItemsSchemaClass() == null) {
            target.setItemsSchemaClass(orphan.getItemsSchemaClass());
        }
        if (target.getJavaName() == null) {
            target.setJavaName(orphan.getJavaName());
        }
    }

    /**
     * Prefer getter alias, then setter, then field annotations, else the Java bean name.
     */
    private static String resolveSerializationName(MethodDeclaration getter,
                                                   MethodDeclaration setter,
                                                   List<AnnotationExpr> fieldAnnotations,
                                                   String javaName) {
        Optional<String> fromGetter = getter != null
                ? serializationNameFromAnnotations(getter.getAnnotations()) : Optional.empty();
        if (fromGetter.isPresent()) {
            return fromGetter.get();
        }
        Optional<String> fromSetter = setter != null
                ? serializationNameFromAnnotations(setter.getAnnotations()) : Optional.empty();
        if (fromSetter.isPresent()) {
            return fromSetter.get();
        }
        return serializationNameFromAnnotations(fieldAnnotations).orElse(javaName);
    }

    private static Optional<String> serializationNameFromAnnotations(List<AnnotationExpr> annotations) {
        if (annotations == null) {
            return Optional.empty();
        }
        for (AnnotationExpr a : annotations) {
            String simple = simpleAnnotationName(a);
            if ("JsonProperty".equals(simple)) {
                Optional<String> value = annotationStringValue(a);
                if (value.isEmpty()) {
                    value = annotationNamedStringValue(a, "value");
                }
                if (value.isPresent() && !value.get().isBlank()) {
                    return value;
                }
            }
            if ("XmlElement".equals(simple) || "XmlAttribute".equals(simple)) {
                Optional<String> name = annotationNamedStringValue(a, "name");
                if (name.isPresent() && !name.get().isBlank() && !"##default".equals(name.get())) {
                    return name;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> annotationNamedStringValue(AnnotationExpr ann, String member) {
        if (ann instanceof NormalAnnotationExpr) {
            for (MemberValuePair pair : ((NormalAnnotationExpr) ann).getPairs()) {
                if (member.equals(pair.getNameAsString()) && pair.getValue().isStringLiteralExpr()) {
                    return Optional.of(pair.getValue().asStringLiteralExpr().getValue());
                }
            }
        }
        return Optional.empty();
    }

    private static MethodDeclaration findAccessor(ClassOrInterfaceDeclaration type, String javaName, boolean getter) {
        String capped = javaName.isEmpty() ? javaName
                : Character.toUpperCase(javaName.charAt(0)) + javaName.substring(1);
        for (MethodDeclaration method : type.getMethods()) {
            String name = method.getNameAsString();
            if (getter) {
                if (method.getParameters().isEmpty()
                        && (("get" + capped).equals(name) || ("is" + capped).equals(name))) {
                    return method;
                }
            } else if (method.getParameters().size() == 1 && ("set" + capped).equals(name)) {
                return method;
            }
        }
        return null;
    }

    private static FieldDeclaration findField(ClassOrInterfaceDeclaration type, String javaName) {
        for (FieldDeclaration field : type.getFields()) {
            for (VariableDeclarator var : field.getVariables()) {
                if (javaName.equals(var.getNameAsString())) {
                    return field;
                }
            }
        }
        return null;
    }

    private static String propertyNameFromSetter(String methodName) {
        if (methodName == null || !methodName.startsWith("set") || methodName.length() < 4) {
            return null;
        }
        String raw = methodName.substring(3);
        if (raw.isEmpty()) {
            return null;
        }
        return Character.toLowerCase(raw.charAt(0)) + raw.substring(1);
    }

    private static boolean getterHasIgnoreAnnotation(ClassOrInterfaceDeclaration type, String propName) {
        String capped = propName.isEmpty() ? propName
                : Character.toUpperCase(propName.charAt(0)) + propName.substring(1);
        for (MethodDeclaration method : type.getMethods()) {
            String name = method.getNameAsString();
            if (method.getParameters().isEmpty()
                    && (("get" + capped).equals(name) || ("is" + capped).equals(name))) {
                return hasJsonIgnore(method.getAnnotations()) || hasXmlTransient(method.getAnnotations());
            }
        }
        return false;
    }

    /** Prefer description body; fall back to {@code @deprecated} / {@code @return} when body is empty. */
    private static String propertyDescriptionFromJavadoc(Javadoc doc) {
        String body = descriptionText(doc.getDescription()).trim();
        if (!body.isEmpty()) {
            return body;
        }
        for (JavadocBlockTag tag : doc.getBlockTags()) {
            if ("deprecated".equalsIgnoreCase(tag.getTagName())) {
                String deprecated = descriptionText(tag.getContent()).trim();
                if (!deprecated.isEmpty()) {
                    return "Deprecated: " + deprecated;
                }
            }
        }
        for (JavadocBlockTag tag : doc.getBlockTags()) {
            if ("return".equalsIgnoreCase(tag.getTagName())) {
                String ret = descriptionText(tag.getContent()).trim();
                if (!ret.isEmpty()) {
                    return ret;
                }
            }
        }
        return "";
    }

    private static SchemaDoc extractEnumSchema(EnumDeclaration enumType) {
        SchemaDoc schema = new SchemaDoc();
        enumType.getJavadoc().ifPresent(doc ->
                schema.setDescription(descriptionText(doc.getDescription()).trim()));
        schema.setType("string");
        List<String> enums = new ArrayList<>();
        for (EnumConstantDeclaration constant : enumType.getEntries()) {
            enums.add(constant.getNameAsString());
        }
        schema.setEnumValues(enums);
        return schema;
    }

    private static void applyApiExampleFromJavadoc(Javadoc doc, PropertyDoc prop) {
        for (JavadocBlockTag tag : doc.getBlockTags()) {
            if ("api.example".equals(tag.getTagName())) {
                prop.setExample(parseExampleValue(descriptionText(tag.getContent()).trim()));
                return;
            }
        }
    }

    private static boolean hasJsonIgnore(List<AnnotationExpr> annotations) {
        for (AnnotationExpr a : annotations) {
            String n = a.getNameAsString();
            if ("JsonIgnore".equals(n) || n.endsWith(".JsonIgnore")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasXmlTransient(List<AnnotationExpr> annotations) {
        for (AnnotationExpr a : annotations) {
            String n = a.getNameAsString();
            if ("XmlTransient".equals(n) || n.endsWith(".XmlTransient")) {
                return true;
            }
        }
        return false;
    }

    private void mapJavaType(Type type, PropertyDoc prop, ClassOrInterfaceDeclaration enclosing, CompilationUnit cu,
                             Map<String, Path> typeIndex, Set<String> nestedRefs) {
        String t = type.asString();
        switch (t) {
            case "String":
            case "java.lang.String":
                prop.setType("string");
                break;
            case "int":
            case "Integer":
            case "java.lang.Integer":
                prop.setType("integer");
                prop.setFormat("int32");
                break;
            case "long":
            case "Long":
            case "java.lang.Long":
                prop.setType("integer");
                prop.setFormat("int64");
                break;
            case "boolean":
            case "Boolean":
            case "java.lang.Boolean":
                prop.setType("boolean");
                break;
            case "double":
            case "Double":
            case "float":
            case "Float":
                prop.setType("number");
                break;
            default:
                if (t.startsWith("Map") || (type.isClassOrInterfaceType()
                        && "Map".equals(type.asClassOrInterfaceType().getNameAsString()))) {
                    prop.setType("object");
                    String valueType = mapValueTypeName(type);
                    if (valueType != null && nestedRefs != null) {
                        String valueFqcn = resolveTypeName(cu, valueType);
                        if (valueFqcn != null && !isJdkType(valueFqcn) && !isJdkType(valueType)) {
                            nestedRefs.add(valueFqcn);
                        }
                    }
                } else if (t.startsWith("List") || t.startsWith("Set") || t.startsWith("Collection")
                        || (type.isClassOrInterfaceType() && Set.of("List", "Set", "Collection")
                        .contains(type.asClassOrInterfaceType().getNameAsString()))) {
                    prop.setType("array");
                    String itemType = collectionItemTypeName(type);
                    if (itemType != null) {
                        String itemFqcn = resolveTypeName(cu, itemType);
                        if (itemFqcn != null && !isJdkType(itemFqcn) && !isJdkType(itemType)) {
                            prop.setItemsSchemaClass(itemFqcn);
                            if (nestedRefs != null) {
                                nestedRefs.add(itemFqcn);
                            }
                        }
                    }
                } else {
                    applyEnumOrObjectProperty(prop, t, enclosing, cu, typeIndex, nestedRefs);
                }
                break;
        }
    }

    /** Best-effort value type for {@code Map<K,V>} from AST. */
    private static String mapValueTypeName(Type type) {
        if (type != null && type.isClassOrInterfaceType()) {
            ClassOrInterfaceType cit = type.asClassOrInterfaceType();
            if (cit.getTypeArguments().isPresent() && cit.getTypeArguments().get().size() >= 2) {
                return cit.getTypeArguments().get().get(1).asString();
            }
        }
        return null;
    }

    /** Best-effort item type for List/Set/Collection from AST or toString(). */
    private static String collectionItemTypeName(Type type) {
        if (type != null && type.isClassOrInterfaceType()) {
            ClassOrInterfaceType cit = type.asClassOrInterfaceType();
            if (cit.getTypeArguments().isPresent() && !cit.getTypeArguments().get().isEmpty()) {
                Type arg = cit.getTypeArguments().get().get(0);
                if (arg.isWildcardType() && arg.asWildcardType().getExtendedType().isPresent()) {
                    arg = arg.asWildcardType().getExtendedType().get();
                }
                return arg.asString();
            }
        }
        return extractGenericArgument(type != null ? type.asString() : null);
    }

    private static String extractGenericArgument(String typeString) {
        if (typeString == null) {
            return null;
        }
        int lt = typeString.indexOf('<');
        int gt = typeString.lastIndexOf('>');
        if (lt < 0 || gt <= lt) {
            return null;
        }
        String inner = typeString.substring(lt + 1, gt).trim();
        if (inner.contains(",")) {
            return null; // Map-like
        }
        if (inner.startsWith("? extends ")) {
            inner = inner.substring("? extends ".length()).trim();
        } else if (inner.startsWith("? super ")) {
            inner = inner.substring("? super ".length()).trim();
        }
        return inner; // keep Outer.Inner form
    }

    private void applyEnumOrObjectProperty(PropertyDoc prop, String typeName, ClassOrInterfaceDeclaration enclosing,
                                           CompilationUnit cu, Map<String, Path> typeIndex, Set<String> nestedRefs) {
        String simple = typeName.contains(".") ? typeName.substring(typeName.lastIndexOf('.') + 1) : typeName;
        Optional<EnumDeclaration> nested = Optional.empty();
        if (enclosing != null) {
            nested = enclosing.findAll(EnumDeclaration.class).stream()
                    .filter(e -> e.getNameAsString().equals(simple))
                    .findFirst();
        }
        if (nested.isEmpty() && cu != null) {
            nested = cu.findAll(EnumDeclaration.class).stream()
                    .filter(e -> e.getNameAsString().equals(simple))
                    .findFirst();
        }
        // Resolve Outer.Inner (e.g. PersonalizationService.PersonalizedContent) via type index.
        String fqcn = resolveTypeName(cu, typeName);
        if (nested.isEmpty() && typeIndex != null && fqcn != null) {
            Path enumSource = typeIndex.get(fqcn);
            if (enumSource != null) {
                try {
                    CompilationUnit enumCu = StaticJavaParser.parse(Files.readString(enumSource, StandardCharsets.UTF_8));
                    nested = enumCu.findAll(EnumDeclaration.class).stream()
                            .filter(e -> e.getNameAsString().equals(simple))
                            .findFirst();
                } catch (IOException ignored) {
                    // best-effort
                }
            }
        }
        if (nested.isPresent()) {
            prop.setType("string");
            List<String> enums = new ArrayList<>();
            List<String> valueDocs = new ArrayList<>();
            for (EnumConstantDeclaration constant : nested.get().getEntries()) {
                enums.add(constant.getNameAsString());
                String constantDoc = constant.getJavadoc()
                        .map(j -> descriptionText(j.getDescription()).trim())
                        .orElse("");
                if (!constantDoc.isEmpty()) {
                    valueDocs.add(constant.getNameAsString() + ": " + constantDoc);
                }
            }
            prop.setEnumValues(enums);
            if (!valueDocs.isEmpty()) {
                String valuesBlurb = "Values — " + String.join("; ", valueDocs);
                if (prop.getDescription() == null || prop.getDescription().isBlank()) {
                    prop.setDescription(valuesBlurb);
                } else if (!prop.getDescription().contains("Values —")) {
                    prop.setDescription(prop.getDescription().trim() + " " + valuesBlurb);
                }
            } else if ((prop.getDescription() == null || prop.getDescription().isBlank())
                    && nested.get().getJavadoc().isPresent()) {
                prop.setDescription(descriptionText(nested.get().getJavadoc().get().getDescription()).trim());
            }
            return;
        }
        prop.setType("object");
        if (fqcn != null && !isJdkType(fqcn) && !isJdkType(simple)) {
            prop.setSchemaClass(fqcn);
            if (nestedRefs != null) {
                nestedRefs.add(fqcn);
            }
        }
    }

    private static String propertyNameFromGetter(String methodName) {
        String raw;
        if (methodName.startsWith("is") && methodName.length() > 2) {
            raw = methodName.substring(2);
        } else if (methodName.startsWith("get") && methodName.length() > 3) {
            raw = methodName.substring(3);
        } else {
            return methodName;
        }
        if (raw.isEmpty()) {
            return methodName;
        }
        return Character.toLowerCase(raw.charAt(0)) + raw.substring(1);
    }

    private Map<String, Path> indexJavaFiles(List<Path> roots) throws IOException {
        Map<String, Path> index = new LinkedHashMap<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(file -> {
                    try {
                        CompilationUnit cu = StaticJavaParser.parse(file);
                        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
                        for (ClassOrInterfaceDeclaration type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                            index.putIfAbsent(nestedTypeFqcn(pkg, type), file);
                        }
                        for (EnumDeclaration type : cu.findAll(EnumDeclaration.class)) {
                            index.putIfAbsent(nestedTypeFqcn(pkg, type), file);
                        }
                    } catch (IOException ignored) {
                        // best-effort index
                    }
                });
            }
        }
        return index;
    }

    private static boolean hasPathAnnotation(ClassOrInterfaceDeclaration type) {
        return type.getAnnotations().stream().anyMatch(a -> "Path".equals(simpleAnnotationName(a)));
    }

    private static boolean extendsHttpServlet(ClassOrInterfaceDeclaration type) {
        return type.getExtendedTypes().stream()
                .anyMatch(t -> {
                    String n = t.getNameAsString();
                    return "HttpServlet".equals(n) || n.endsWith(".HttpServlet");
                });
    }

    private static Optional<String> httpMethod(MethodDeclaration method) {
        for (AnnotationExpr ann : method.getAnnotations()) {
            String name = simpleAnnotationName(ann).toUpperCase(Locale.ROOT);
            if (HTTP_METHODS.contains(name)) {
                return Optional.of(name);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> annotationPathValue(Node annotated) {
        List<AnnotationExpr> annotations;
        if (annotated instanceof ClassOrInterfaceDeclaration) {
            annotations = ((ClassOrInterfaceDeclaration) annotated).getAnnotations();
        } else if (annotated instanceof MethodDeclaration) {
            annotations = ((MethodDeclaration) annotated).getAnnotations();
        } else {
            return Optional.empty();
        }
        for (AnnotationExpr ann : annotations) {
            if (!"Path".equals(simpleAnnotationName(ann))) {
                continue;
            }
            if (ann instanceof SingleMemberAnnotationExpr) {
                Expression member = ((SingleMemberAnnotationExpr) ann).getMemberValue();
                if (member instanceof StringLiteralExpr) {
                    return Optional.of(((StringLiteralExpr) member).getValue());
                }
            } else if (ann instanceof NormalAnnotationExpr) {
                for (MemberValuePair pair : ((NormalAnnotationExpr) ann).getPairs()) {
                    if ("value".equals(pair.getNameAsString()) && pair.getValue().isStringLiteralExpr()) {
                        return Optional.of(pair.getValue().asStringLiteralExpr().getValue());
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static String simpleAnnotationName(AnnotationExpr ann) {
        String name = ann.getNameAsString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    private static String joinPaths(String classPath, String methodPath) {
        return normalizePath(
                (classPath == null ? "" : classPath) + "/" + (methodPath == null ? "" : methodPath));
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String[] segments = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (String segment : segments) {
            if (segment == null || segment.isEmpty()) {
                continue;
            }
            sb.append('/').append(segment);
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private static String inferSchemaClass(Type returnType) {
        if (isResponseOrVoid(returnType)) {
            return null;
        }
        Type unwrapped = unwrapWrapper(returnType);
        if (unwrapped.isPrimitiveType() || isJdkType(unwrapped.asString())) {
            return null;
        }
        if (unwrapped.isClassOrInterfaceType()) {
            return unwrapped.asClassOrInterfaceType().getNameAsString();
        }
        return unwrapped.asString();
    }

    private static Type unwrapWrapper(Type type) {
        if (!type.isClassOrInterfaceType()) {
            return type;
        }
        ClassOrInterfaceType cit = type.asClassOrInterfaceType();
        String name = cit.getNameAsString();
        if (("ResponseEntity".equals(name) || "Optional".equals(name) || "CompletionStage".equals(name)
                || "CompletableFuture".equals(name))
                && cit.getTypeArguments().isPresent()
                && !cit.getTypeArguments().get().isEmpty()) {
            return cit.getTypeArguments().get().get(0);
        }
        return type;
    }

    private static boolean isResponseOrVoid(Type type) {
        String name = type.asString();
        return "void".equals(name)
                || "Void".equals(name)
                || "Response".equals(name)
                || name.endsWith(".Response")
                || "javax.ws.rs.core.Response".equals(name)
                || "jakarta.ws.rs.core.Response".equals(name);
    }

    private static boolean isJdkType(String name) {
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jakarta.")
                || Set.of("String", "Integer", "Long", "Boolean", "Double", "Float", "Object").contains(name);
    }

    /**
     * Resolve a type name, substituting type-variable bindings first.
     */
    private String resolveTypeNameWithBindings(CompilationUnit cu, String typeName,
                                               Map<String, String> typeVarBindings) {
        if (typeName == null || typeName.isBlank()) {
            return null;
        }
        String name = substituteTypeVar(typeName, typeVarBindings);
        // Binding values may already be FQCNs.
        if (name.contains(".") && typeIndex != null && typeIndex.containsKey(name)) {
            return name;
        }
        if (name.contains(".") && !name.isEmpty() && Character.isLowerCase(name.charAt(0))) {
            return name;
        }
        return resolveTypeName(cu, name);
    }

    /**
     * First concrete superclass FQCN (skips interfaces / JDK / unresolved).
     */
    private String resolveParentFqcn(ClassOrInterfaceDeclaration type, CompilationUnit cu) {
        if (type.getExtendedTypes().isEmpty()) {
            return null;
        }
        ClassOrInterfaceType parentType = type.getExtendedTypes().get(0);
        String parentFqcn = resolveTypeName(cu, parentType.getNameAsString());
        if (parentFqcn == null || isJdkType(parentFqcn)) {
            return null;
        }
        Path parentSource = typeIndex.get(parentFqcn);
        if (parentSource == null) {
            return parentFqcn;
        }
        try {
            CompilationUnit parentCu = StaticJavaParser.parse(Files.readString(parentSource, StandardCharsets.UTF_8));
            Optional<ClassOrInterfaceDeclaration> parent = findNestedType(parentCu, parentFqcn);
            if (parent.isEmpty() || parent.get().isInterface()) {
                return null;
            }
        } catch (IOException ignored) {
            // still record resolved name
        }
        return parentFqcn;
    }

    private String resolveTypeName(CompilationUnit cu, String typeName) {
        if (typeName == null || typeName.isBlank() || "empty".equalsIgnoreCase(typeName)) {
            return null;
        }
        if (typeName.contains(".")) {
            // Already absolute FQCN in the index?
            if (typeIndex != null && typeIndex.containsKey(typeName)) {
                return typeName;
            }
            int firstDot = typeName.indexOf('.');
            String first = typeName.substring(0, firstDot);
            // Package FQCNs start with a lowercase segment (com., org., …); leave them alone.
            if (!first.isEmpty() && Character.isLowerCase(first.charAt(0))) {
                return typeName;
            }
            // Relative nested type: Outer.Inner (e.g. PersonalizationService.PersonalizedContent)
            String outerSimple = first;
            String rest = typeName.substring(firstDot + 1);
            String outerFqcn = resolveSimpleTypeName(cu, outerSimple);
            if (outerFqcn != null && !outerFqcn.equals(outerSimple)) {
                // Outer resolved to a real FQCN — return Outer.Inner even if the index
                // is still being built or nesting was discovered after the outer type.
                return outerFqcn + "." + rest;
            }
            if (cu != null) {
                List<String> starPackages = new ArrayList<>();
                cu.getImports().forEach(i -> {
                    if (!i.isStatic() && i.isAsterisk()) {
                        starPackages.add(i.getNameAsString());
                    }
                });
                for (String starPkg : starPackages) {
                    String candidate = starPkg + "." + typeName;
                    if (typeIndex != null && typeIndex.containsKey(candidate)) {
                        return candidate;
                    }
                }
            }
            return typeName;
        }
        if (cu == null) {
            return typeName;
        }
        return resolveSimpleTypeName(cu, typeName);
    }

    private String resolveSimpleTypeName(CompilationUnit cu, String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return null;
        }
        if (cu == null) {
            return typeName;
        }
        List<String> starPackages = new ArrayList<>();
        Set<String> imports = new HashSet<>();
        cu.getImports().forEach(i -> {
            if (i.isStatic()) {
                return;
            }
            if (i.isAsterisk()) {
                starPackages.add(i.getNameAsString());
            } else {
                imports.add(i.getNameAsString());
            }
        });
        for (String imp : imports) {
            if (imp.endsWith("." + typeName)) {
                return imp;
            }
        }
        // Nested types declared in this compilation unit (e.g. PersonalizationService.Filter).
        String pkgName = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        Optional<String> nestedInCu = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> c.getNameAsString().equals(typeName))
                .map(c -> nestedTypeFqcn(pkgName, c))
                .filter(fq -> fq.endsWith("." + typeName) && fq.contains("."))
                .max(Comparator.comparingInt(String::length));
        if (nestedInCu.isPresent()) {
            return nestedInCu.get();
        }
        nestedInCu = cu.findAll(EnumDeclaration.class).stream()
                .filter(e -> e.getNameAsString().equals(typeName))
                .map(e -> nestedTypeFqcn(pkgName, e))
                .filter(fq -> fq.endsWith("." + typeName) && fq.contains("."))
                .max(Comparator.comparingInt(String::length));
        if (nestedInCu.isPresent()) {
            return nestedInCu.get();
        }
        for (String starPkg : starPackages) {
            String candidate = starPkg + "." + typeName;
            if (typeIndex != null && typeIndex.containsKey(candidate)) {
                return candidate;
            }
        }
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        if (!pkg.isEmpty()) {
            String samePkg = pkg + "." + typeName;
            if (typeIndex == null || typeIndex.isEmpty() || typeIndex.containsKey(samePkg)
                    || starPackages.isEmpty()) {
                return samePkg;
            }
            return starPackages.get(0) + "." + typeName;
        }
        if (!starPackages.isEmpty()) {
            return starPackages.get(0) + "." + typeName;
        }
        return typeName;
    }

    private static String nestedTypeFqcn(String pkg, com.github.javaparser.ast.body.TypeDeclaration<?> type) {
        List<String> names = new ArrayList<>();
        com.github.javaparser.ast.body.TypeDeclaration<?> current = type;
        while (current != null) {
            names.add(0, current.getNameAsString());
            current = current.getParentNode()
                    .filter(n -> n instanceof com.github.javaparser.ast.body.TypeDeclaration)
                    .map(n -> (com.github.javaparser.ast.body.TypeDeclaration<?>) n)
                    .orElse(null);
        }
        String nested = String.join(".", names);
        return qualify(pkg, nested);
    }

    private static Optional<ClassOrInterfaceDeclaration> findNestedType(CompilationUnit cu, String fqcn) {
        String simple = simpleName(fqcn);
        // Prefer exact nested match: ...Outer.Inner
        Optional<ClassOrInterfaceDeclaration> nested = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> {
                    String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
                    return nestedTypeFqcn(pkg, c).equals(fqcn);
                })
                .findFirst();
        if (nested.isPresent()) {
            return nested;
        }
        return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> c.getNameAsString().equals(simple))
                .findFirst();
    }


    private static String qualify(String packageName, String simple) {
        return packageName == null || packageName.isEmpty() ? simple : packageName + "." + simple;
    }

    private static String simpleName(String fqcn) {
        int idx = fqcn.lastIndexOf('.');
        return idx >= 0 ? fqcn.substring(idx + 1) : fqcn;
    }

    private static String descriptionText(JavadocDescription description) {
        return normalizeJavadocInline(description != null ? description.toText() : "");
    }


    private static void applyOperationExample(OperationDoc op, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        Object example = parseExampleValue(raw);
        ResponseDoc target = op.getResponses().get("200");
        if (target == null) {
            target = op.getResponses().values().stream().findFirst().orElse(null);
        }
        if (target == null) {
            target = op.getResponses().computeIfAbsent("200", c -> new ResponseDoc());
        }
        if (target.getExample() == null) {
            target.setExample(example);
        }
    }

    private static Object parseExampleValue(String raw) {
        String s = raw.trim();
        if ((s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]"))
                || (s.startsWith("\"") && s.endsWith("\""))) {
            return s;
        }
        if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
            return Boolean.parseBoolean(s);
        }
        try {
            if (s.contains(".")) {
                return Double.parseDouble(s);
            }
            return Long.parseLong(s);
        } catch (NumberFormatException ignored) {
            return s;
        }
    }

    /**
     * Strip Javadoc inline tags so OpenAPI prose is readable (no raw {@code}/{@link}).
     */
    static String normalizeJavadocInline(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String s = text;
        s = JAVADOC_CODE.matcher(s).replaceAll("$1");
        Matcher linkMatcher = JAVADOC_LINK.matcher(s);
        StringBuffer linkBuf = new StringBuffer();
        while (linkMatcher.find()) {
            String target = linkMatcher.group(1);
            int hash = target.indexOf('#');
            if (hash >= 0) {
                target = target.substring(hash + 1);
                int slash = target.indexOf('/');
                if (slash >= 0) {
                    target = target.substring(0, slash);
                }
            } else {
                int dot = target.lastIndexOf('.');
                if (dot >= 0) {
                    target = target.substring(dot + 1);
                }
            }
            linkMatcher.appendReplacement(linkBuf, Matcher.quoteReplacement(target));
        }
        linkMatcher.appendTail(linkBuf);
        s = linkBuf.toString();
        s = JAVADOC_LITERAL.matcher(s).replaceAll("$1");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private static int firstSentenceEnd(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                if (i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1))) {
                    return i + 1;
                }
            }
        }
        return -1;
    }
}
