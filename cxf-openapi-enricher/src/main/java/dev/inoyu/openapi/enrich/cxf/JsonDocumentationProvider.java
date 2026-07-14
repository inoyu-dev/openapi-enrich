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
package dev.inoyu.openapi.enrich.cxf;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cxf.jaxrs.model.ClassResourceInfo;
import org.apache.cxf.jaxrs.model.OperationResourceInfo;
import org.apache.cxf.jaxrs.model.Parameter;
import org.apache.cxf.jaxrs.model.doc.DocumentationProvider;

import dev.inoyu.openapi.enrich.model.DocPack;
import dev.inoyu.openapi.enrich.model.OperationDoc;
import dev.inoyu.openapi.enrich.model.ParamDoc;
import dev.inoyu.openapi.enrich.model.ResponseDoc;

/**
 * {@link DocumentationProvider} backed by a merged {@link DocPack}.
 * Indexed by {@code className#methodName} and by {@code HTTP path} (operationKey).
 */
public class JsonDocumentationProvider implements DocumentationProvider {

    private final Map<String, OperationDoc> byClassMethod = new HashMap<>();
    private final Map<String, OperationDoc> byOperationKey = new HashMap<>();

    public JsonDocumentationProvider(DocPack pack) {
        if (pack == null || pack.getOperations() == null) {
            return;
        }
        for (OperationDoc op : pack.getOperations()) {
            if (op.getClassName() != null && op.getMethodName() != null) {
                byClassMethod.put(op.getClassName() + "#" + op.getMethodName(), op);
            }
            if (op.getOperationKey() != null) {
                byOperationKey.put(op.getOperationKey(), op);
            }
            if (op.getHttpMethod() != null && op.getPath() != null) {
                byOperationKey.putIfAbsent(op.getHttpMethod() + " " + op.getPath(), op);
            }
        }
    }

    @Override
    public String getClassDoc(ClassResourceInfo cri) {
        return null;
    }

    @Override
    public String getMethodDoc(OperationResourceInfo ori) {
        OperationDoc op = find(ori);
        if (op == null) {
            return null;
        }
        if (op.getSummary() != null && !op.getSummary().isBlank()) {
            return op.getSummary();
        }
        return op.getDescription();
    }

    @Override
    public String getMethodResponseDoc(OperationResourceInfo ori) {
        OperationDoc op = find(ori);
        if (op == null || op.getResponses() == null) {
            return null;
        }
        ResponseDoc ok = op.getResponses().get("200");
        if (ok != null && ok.getDescription() != null && !ok.getDescription().isBlank()) {
            return ok.getDescription();
        }
        return op.getDescription();
    }

    @Override
    public String getMethodParameterDoc(OperationResourceInfo ori, int paramIndex) {
        OperationDoc op = find(ori);
        if (op == null || op.getParameters() == null || op.getParameters().isEmpty()) {
            return null;
        }
        List<Parameter> parameters = ori.getParameters();
        if (parameters != null && paramIndex >= 0 && paramIndex < parameters.size()) {
            String name = parameters.get(paramIndex).getName();
            ParamDoc doc = op.getParameters().get(name);
            if (doc != null) {
                return doc.getDescription();
            }
        }
        int i = 0;
        for (Map.Entry<String, ParamDoc> entry : op.getParameters().entrySet()) {
            if (i == paramIndex) {
                return entry.getValue().getDescription();
            }
            i++;
        }
        return null;
    }

    public OperationDoc findByOperationKey(String operationKey) {
        return byOperationKey.get(operationKey);
    }

    public OperationDoc findByClassMethod(String className, String methodName) {
        return byClassMethod.get(className + "#" + methodName);
    }

    private OperationDoc find(OperationResourceInfo ori) {
        if (ori == null) {
            return null;
        }
        Method method = ori.getAnnotatedMethod() != null ? ori.getAnnotatedMethod() : ori.getMethodToInvoke();
        if (method != null) {
            OperationDoc byMethod = byClassMethod.get(method.getDeclaringClass().getName() + "#" + method.getName());
            if (byMethod != null) {
                return byMethod;
            }
            ClassResourceInfo cri = ori.getClassResourceInfo();
            if (cri != null && cri.getServiceClass() != null) {
                byMethod = byClassMethod.get(cri.getServiceClass().getName() + "#" + method.getName());
                if (byMethod != null) {
                    return byMethod;
                }
            }
        }
        if (ori.getHttpMethod() != null && ori.getURITemplate() != null) {
            String path = ori.getURITemplate().getValue();
            ClassResourceInfo cri = ori.getClassResourceInfo();
            if (cri != null && cri.getURITemplate() != null) {
                path = join(cri.getURITemplate().getValue(), path);
            }
            return byOperationKey.get(ori.getHttpMethod() + " " + normalize(path));
        }
        return null;
    }

    private static String join(String classPath, String methodPath) {
        return normalize((classPath == null ? "" : classPath) + "/" + (methodPath == null ? "" : methodPath));
    }

    private static String normalize(String path) {
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
}
