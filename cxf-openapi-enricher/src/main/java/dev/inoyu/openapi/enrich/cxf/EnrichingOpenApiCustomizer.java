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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.cxf.jaxrs.openapi.OpenApiCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.inoyu.openapi.enrich.model.DocPack;
import dev.inoyu.openapi.enrich.model.OperationDoc;
import dev.inoyu.openapi.enrich.model.PropertyDoc;
import dev.inoyu.openapi.enrich.model.ResponseDoc;
import dev.inoyu.openapi.enrich.model.SchemaDoc;
import dev.inoyu.openapi.enrich.model.ServletOperationDoc;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;

/**
 * CXF {@link OpenApiCustomizer} that merges classpath {@link DocPack} data into the OpenAPI document.
 */
public class EnrichingOpenApiCustomizer extends OpenApiCustomizer {

    private static final Logger LOG = LoggerFactory.getLogger(EnrichingOpenApiCustomizer.class);

    private final DocPack docPack;
    private final JsonDocumentationProvider documentationProvider;

    public EnrichingOpenApiCustomizer() {
        this(new ClasspathDocPackLoader().loadMerged());
    }

    public EnrichingOpenApiCustomizer(DocPack merged) {
        this.docPack = merged != null ? merged : new DocPack();
        this.documentationProvider = new JsonDocumentationProvider(this.docPack);
        setJavadocProvider(this.documentationProvider);
    }

    public DocPack getDocPack() {
        return docPack;
    }

    @Override
    public void customize(OpenAPI oas) {
        if (oas == null) {
            return;
        }
        if (cris != null) {
            super.customize(oas);
        } else {
            LOG.debug("ClassResourceInfos not set; skipping javadocProvider path merge from super.customize");
        }
        ensureSchemaStubsForRefs(oas);
        enrichSchemas(oas);
        enrichOperations(oas);
        enrichServletOperations(oas);
    }

    private void enrichSchemas(OpenAPI oas) {
        if (docPack.getSchemas() == null || docPack.getSchemas().isEmpty()) {
            return;
        }
        if (oas.getComponents() == null) {
            oas.setComponents(new Components());
        }
        if (oas.getComponents().getSchemas() == null) {
            oas.getComponents().setSchemas(new LinkedHashMap<>());
        }
        Map<String, Schema> schemas = oas.getComponents().getSchemas();
        for (Map.Entry<String, SchemaDoc> entry : docPack.getSchemas().entrySet()) {
            String key = simpleName(entry.getKey());
            SchemaDoc doc = entry.getValue();
            Schema existing = schemas.get(key);
            Schema target = existing != null ? existing : new Schema().type("object");
            if (doc.getDescription() != null && !doc.getDescription().isBlank()) {
                if (target.getDescription() == null || target.getDescription().isBlank()) {
                    target.setDescription(doc.getDescription());
                }
            }
            if (doc.getEnumValues() != null && !doc.getEnumValues().isEmpty()) {
                if (target.getEnum() == null || target.getEnum().isEmpty()) {
                    target.setEnum(new ArrayList<>(doc.getEnumValues()));
                }
                if (doc.getType() != null) {
                    target.setType(doc.getType());
                } else if (target.getType() == null || "object".equals(target.getType())) {
                    target.setType("string");
                }
            } else if (doc.getType() != null && target.getType() == null) {
                target.setType(doc.getType());
            }
            // Own properties first, then parentFqcn chain onto the same OpenAPI schema (no allOf rewrite).
            // Collect suppressions along the chain and apply once at the end so a parent's
            // documented property (e.g. Item.scope) cannot reappear after a child marked it
            // @XmlTransient (e.g. Profile/MetadataItem.scope).
            List<String> suppressed = new ArrayList<>();
            applyPropertiesOntoSchema(target, doc.getProperties(), oas);
            appendSuppressed(suppressed, doc.getSuppressedProperties());
            Set<String> visited = new HashSet<>();
            visited.add(entry.getKey());
            String parentFqcn = doc.getParentFqcn();
            while (parentFqcn != null && !parentFqcn.isBlank() && visited.add(parentFqcn)) {
                SchemaDoc parentDoc = docPack.getSchemas().get(parentFqcn);
                if (parentDoc == null) {
                    break;
                }
                applyPropertiesOntoSchema(target, parentDoc.getProperties(), oas);
                appendSuppressed(suppressed, parentDoc.getSuppressedProperties());
                parentFqcn = parentDoc.getParentFqcn();
            }
            removeSuppressedProperties(target, suppressed);
            if (existing == null) {
                schemas.put(key, target);
            }
        }
    }



    private static void appendSuppressed(List<String> target, List<String> more) {
        if (target == null || more == null) {
            return;
        }
        for (String name : more) {
            if (name != null && !name.isBlank() && !target.contains(name)) {
                target.add(name);
            }
        }
    }

    /**
     * Drop CXF-emitted properties that source marks as non-serialized
     * ({@code @XmlTransient}, {@code @JsonIgnore}, {@code transient}).
     */
    private void removeSuppressedProperties(Schema target, List<String> suppressed) {
        if (target == null || suppressed == null || suppressed.isEmpty()) {
            return;
        }
        if (target.getProperties() != null) {
            for (String name : suppressed) {
                if (name != null && !name.isBlank()) {
                    target.getProperties().remove(name);
                }
            }
        }
        if (target.getRequired() != null) {
            target.getRequired().removeIf(r -> r != null && suppressed.contains(r));
        }
        if (target.getAllOf() != null) {
            for (Object part : target.getAllOf()) {
                if (part instanceof Schema) {
                    removeSuppressedProperties((Schema) part, suppressed);
                }
            }
        }
    }

    /**
     * Creates missing property schemas and applies docs. Child descriptions already set (non-blank)
     * win over later parent docs because {@link #applyPropertyDoc} only fills blank/generated text.
     */
    private void applyPropertiesOntoSchema(Schema target, Map<String, PropertyDoc> properties, OpenAPI oas) {
        if (properties == null || properties.isEmpty()) {
            return;
        }
        if (target.getProperties() == null) {
            target.setProperties(new LinkedHashMap<>());
        }
        for (Map.Entry<String, PropertyDoc> prop : properties.entrySet()) {
            String wireName = prop.getKey();
            PropertyDoc pdoc = prop.getValue();
            String javaName = pdoc != null ? pdoc.getJavaName() : null;
            Schema propSchema = (Schema) target.getProperties().get(wireName);
            if (propSchema == null && javaName != null && !javaName.isBlank()) {
                propSchema = (Schema) target.getProperties().get(javaName);
                if (propSchema != null && !wireName.equals(javaName)) {
                    // Align CXF bean name with serialization alias (e.g. conditionTypeId → type).
                    target.getProperties().remove(javaName);
                    target.getProperties().put(wireName, propSchema);
                }
            }
            if (propSchema == null) {
                propSchema = new Schema();
                target.getProperties().put(wireName, propSchema);
            }
            applyPropertyDoc(propSchema, pdoc, oas);
        }
    }

    private void applyPropertyDoc(Schema propSchema, PropertyDoc pdoc, OpenAPI oas) {
        if (propSchema == null || pdoc == null) {
            return;
        }
        if (pdoc.getDescription() != null && !pdoc.getDescription().isBlank()
                && (propSchema.getDescription() == null || propSchema.getDescription().isBlank()
                || looksGenerated(propSchema.getDescription()))) {
            propSchema.setDescription(pdoc.getDescription());
        }
        if (pdoc.getSchemaClass() != null && !pdoc.getSchemaClass().isBlank()) {
            String name = simpleName(pdoc.getSchemaClass());
            ensureSchemaStub(oas, name);
            if (propSchema.get$ref() == null) {
                propSchema.set$ref("#/components/schemas/" + name);
            }
        } else if (pdoc.getItemsSchemaClass() != null && !pdoc.getItemsSchemaClass().isBlank()) {
            String name = simpleName(pdoc.getItemsSchemaClass());
            ensureSchemaStub(oas, name);
            propSchema.setType("array");
            if (propSchema.getItems() == null) {
                propSchema.setItems(new Schema().$ref("#/components/schemas/" + name));
            }
        } else {
            if (pdoc.getType() != null && (propSchema.getType() == null
                    || ("object".equals(propSchema.getType())
                    && pdoc.getEnumValues() != null && !pdoc.getEnumValues().isEmpty()))) {
                propSchema.setType(pdoc.getType());
            }
            if (pdoc.getFormat() != null && propSchema.getFormat() == null) {
                propSchema.setFormat(pdoc.getFormat());
            }
            if (pdoc.getEnumValues() != null && !pdoc.getEnumValues().isEmpty()
                    && (propSchema.getEnum() == null || propSchema.getEnum().isEmpty())) {
                propSchema.setEnum(new ArrayList<>(pdoc.getEnumValues()));
                if (propSchema.getType() == null) {
                    propSchema.setType("string");
                }
            }
        }
        if (pdoc.getExample() != null && propSchema.getExample() == null) {
            propSchema.setExample(coerceExample(pdoc.getExample()));
        }
    }

    private void enrichOperations(OpenAPI oas) {
        if (oas.getPaths() == null || docPack.getOperations() == null) {
            return;
        }
        for (OperationDoc opDoc : docPack.getOperations()) {
            if (opDoc.getPath() == null || opDoc.getHttpMethod() == null) {
                continue;
            }
            PathItem pathItem = oas.getPaths().get(opDoc.getPath());
            if (pathItem == null) {
                for (String key : oas.getPaths().keySet()) {
                    if (normalize(key).equals(normalize(opDoc.getPath()))) {
                        pathItem = oas.getPaths().get(key);
                        break;
                    }
                }
            }
            if (pathItem == null) {
                continue;
            }
            Operation operation = pathItem.readOperationsMap().get(PathItem.HttpMethod.valueOf(opDoc.getHttpMethod()));
            if (operation == null) {
                continue;
            }
            if (opDoc.getSummary() != null && (operation.getSummary() == null || operation.getSummary().isBlank())) {
                operation.setSummary(opDoc.getSummary());
            }
            if (opDoc.getDescription() != null
                    && (operation.getDescription() == null || operation.getDescription().isBlank())) {
                operation.setDescription(opDoc.getDescription());
            }
            mergeResponses(operation, opDoc);
            mergeRequestBody(operation, opDoc, oas);
        }
    }

    private void mergeRequestBody(Operation operation, OperationDoc opDoc, OpenAPI oas) {
        if (opDoc.getRequestBodySchemaClass() == null || opDoc.getRequestBodySchemaClass().isBlank()) {
            return;
        }
        String schemaName = simpleName(opDoc.getRequestBodySchemaClass());
        String ref = "#/components/schemas/" + schemaName;
        ensureSchemaStub(oas, schemaName);

        RequestBody requestBody = operation.getRequestBody();
        if (requestBody != null && !isBlankRequestBodySchema(requestBody)) {
            return;
        }
        if (requestBody == null) {
            requestBody = new RequestBody();
            operation.setRequestBody(requestBody);
        }
        if (opDoc.getRequestBodyDescription() != null && !opDoc.getRequestBodyDescription().isBlank()
                && (requestBody.getDescription() == null || requestBody.getDescription().isBlank())) {
            requestBody.setDescription(opDoc.getRequestBodyDescription());
        }
        if (requestBody.getContent() == null) {
            requestBody.setContent(new Content());
        }
        MediaType mt = requestBody.getContent().get("application/json");
        if (mt == null) {
            mt = new MediaType();
            requestBody.getContent().addMediaType("application/json", mt);
        }
        if (mt.getSchema() == null || isBlankSchema(mt.getSchema())) {
            mt.setSchema(new Schema().$ref(ref));
        }
    }

    private static boolean isBlankRequestBodySchema(RequestBody requestBody) {
        if (requestBody.getContent() == null || requestBody.getContent().isEmpty()) {
            return true;
        }
        MediaType mt = requestBody.getContent().get("application/json");
        if (mt == null) {
            return true;
        }
        return mt.getSchema() == null || isBlankSchema(mt.getSchema());
    }

    private static boolean isBlankSchema(Schema schema) {
        return schema.get$ref() == null
                && schema.getType() == null
                && (schema.getProperties() == null || schema.getProperties().isEmpty())
                && schema.getItems() == null;
    }

    private void mergeResponses(Operation operation, OperationDoc opDoc) {
        if (opDoc.getResponses() == null || opDoc.getResponses().isEmpty()) {
            return;
        }
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }
        for (Map.Entry<String, ResponseDoc> entry : opDoc.getResponses().entrySet()) {
            String code = entry.getKey();
            ResponseDoc doc = entry.getValue();
            ApiResponse response = operation.getResponses().get(code);
            if (response == null) {
                response = new ApiResponse();
                operation.getResponses().addApiResponse(code, response);
            }
            if (doc.getDescription() != null && !doc.getDescription().isBlank()
                    && (response.getDescription() == null || response.getDescription().isBlank()
                    || "default response".equalsIgnoreCase(response.getDescription()))) {
                response.setDescription(doc.getDescription());
            } else if (response.getDescription() == null) {
                response.setDescription(doc.getDescription() != null ? doc.getDescription() : "");
            }
            if (doc.getSchemaClass() != null && !doc.getSchemaClass().isBlank()) {
                String ref = "#/components/schemas/" + simpleName(doc.getSchemaClass());
                if (response.getContent() == null) {
                    response.setContent(new Content());
                }
                MediaType mt = response.getContent().get("application/json");
                if (mt == null) {
                    mt = new MediaType();
                    response.getContent().addMediaType("application/json", mt);
                }
                Schema existing = mt.getSchema();
                boolean needsSchema = existing == null
                        || (doc.isArray() && !"array".equals(existing.getType()))
                        || (!doc.isArray() && existing.get$ref() == null
                        && !"array".equals(existing.getType()));
                if (needsSchema) {
                    if (doc.isArray()) {
                        Schema item = new Schema().$ref(ref);
                        mt.setSchema(new Schema().type("array").items(item));
                    } else {
                        mt.setSchema(new Schema().$ref(ref));
                    }
                }
                if (doc.getExample() != null && mt.getExample() == null) {
                    mt.setExample(coerceExample(doc.getExample()));
                }
            } else if (doc.getExample() != null) {
                if (response.getContent() == null) {
                    response.setContent(new Content());
                }
                MediaType mt = response.getContent().get("application/json");
                if (mt == null) {
                    mt = new MediaType();
                    response.getContent().addMediaType("application/json", mt);
                }
                if (mt.getExample() == null) {
                    mt.setExample(coerceExample(doc.getExample()));
                }
            }
        }
    }

    private static Object coerceExample(Object example) {
        if (!(example instanceof String)) {
            return example;
        }
        String s = ((String) example).trim();
        if (!(s.startsWith("{") || s.startsWith("["))) {
            return example;
        }
        try {
            return new ObjectMapper().readValue(s, Object.class);
        } catch (Exception ignored) {
            return example;
        }
    }

    private static boolean looksGenerated(String description) {
        if (description == null) {
            return true;
        }
        String d = description.trim().toLowerCase(Locale.ROOT);
        return d.isEmpty() || "default response".equals(d);
    }

    private void ensureSchemaStub(OpenAPI oas, String simpleName) {
        if (simpleName == null || simpleName.isBlank()) {
            return;
        }
        if (oas.getComponents() == null) {
            oas.setComponents(new Components());
        }
        if (oas.getComponents().getSchemas() == null) {
            oas.getComponents().setSchemas(new LinkedHashMap<>());
        }
        oas.getComponents().getSchemas().putIfAbsent(simpleName, new Schema().type("object"));
    }

    private void ensureSchemaStubsForRefs(OpenAPI oas) {
        if (oas.getComponents() == null) {
            oas.setComponents(new Components());
        }
        if (oas.getComponents().getSchemas() == null) {
            oas.getComponents().setSchemas(new LinkedHashMap<>());
        }
        if (docPack.getOperations() != null) {
            for (OperationDoc op : docPack.getOperations()) {
                stubRequestBodySchema(oas, op);
                stubResponseSchemas(oas, op);
            }
        }
        if (docPack.getServletOperations() != null) {
            for (ServletOperationDoc op : docPack.getServletOperations()) {
                stubRequestBodySchema(oas, op);
                stubResponseSchemas(oas, op);
            }
        }
        if (docPack.getSchemas() != null) {
            for (SchemaDoc schemaDoc : docPack.getSchemas().values()) {
                if (schemaDoc.getProperties() == null) {
                    continue;
                }
                for (PropertyDoc propertyDoc : schemaDoc.getProperties().values()) {
                    if (propertyDoc.getSchemaClass() != null && !propertyDoc.getSchemaClass().isBlank()) {
                        ensureSchemaStub(oas, simpleName(propertyDoc.getSchemaClass()));
                    }
                    if (propertyDoc.getItemsSchemaClass() != null && !propertyDoc.getItemsSchemaClass().isBlank()) {
                        ensureSchemaStub(oas, simpleName(propertyDoc.getItemsSchemaClass()));
                    }
                }
            }
        }
    }

    private void stubRequestBodySchema(OpenAPI oas, OperationDoc op) {
        if (op.getRequestBodySchemaClass() != null && !op.getRequestBodySchemaClass().isBlank()) {
            ensureSchemaStub(oas, simpleName(op.getRequestBodySchemaClass()));
        }
    }

    private void stubResponseSchemas(OpenAPI oas, OperationDoc op) {
        if (op.getResponses() == null) {
            return;
        }
        for (ResponseDoc rd : op.getResponses().values()) {
            if (rd.getSchemaClass() == null || rd.getSchemaClass().isBlank()) {
                continue;
            }
            ensureSchemaStub(oas, simpleName(rd.getSchemaClass()));
        }
    }

    private void enrichServletOperations(OpenAPI oas) {
        List<ServletOperationDoc> servletOps = docPack.getServletOperations();
        if (servletOps == null || servletOps.isEmpty()) {
            return;
        }
        if (oas.getPaths() == null) {
            oas.setPaths(new Paths());
        }
        if (oas.getComponents() == null) {
            oas.setComponents(new Components());
        }
        for (ServletOperationDoc opDoc : servletOps) {
            String path = normalize(opDoc.getPath());
            PathItem pathItem = oas.getPaths().computeIfAbsent(path, p -> new PathItem());
            Operation operation = new Operation();
            operation.setSummary(opDoc.getSummary());
            operation.setDescription(opDoc.getDescription());
            operation.setOperationId(opDoc.getClassName() != null
                    ? simpleName(opDoc.getClassName()) + "_" + opDoc.getMethodName()
                    : opDoc.getOperationKey());
            if (opDoc.getServers() != null && !opDoc.getServers().isEmpty()) {
                for (String url : opDoc.getServers()) {
                    operation.addServersItem(new Server().url(url));
                }
            }
            if (opDoc.getResponses() != null && !opDoc.getResponses().isEmpty()) {
                mergeResponses(operation, opDoc);
            } else {
                ApiResponses responses = new ApiResponses();
                responses.addApiResponse("200", new ApiResponse().description("OK"));
                operation.setResponses(responses);
            }
            PathItem.HttpMethod method = PathItem.HttpMethod.valueOf(
                    opDoc.getHttpMethod() != null ? opDoc.getHttpMethod() : "GET");
            pathItem.operation(method, operation);
        }
    }

    private static String simpleName(String fqcn) {
        if (fqcn == null) {
            return null;
        }
        int idx = fqcn.lastIndexOf('.');
        return idx >= 0 ? fqcn.substring(idx + 1) : fqcn;
    }

    private static String normalize(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '/') {
                i++;
                continue;
            }
            if (c == '{') {
                int depth = 1;
                int start = i;
                i++;
                while (i < path.length() && depth > 0) {
                    char ch = path.charAt(i);
                    if (ch == '{') {
                        depth++;
                    } else if (ch == '}') {
                        depth--;
                    }
                    i++;
                }
                sb.append('/').append(stripPathParamRegex(path.substring(start, i)));
                continue;
            }
            int start = i;
            while (i < path.length() && path.charAt(i) != '/') {
                i++;
            }
            sb.append('/').append(path, start, i);
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private static String stripPathParamRegex(String segment) {
        if (segment.length() < 3 || segment.charAt(0) != '{' || segment.charAt(segment.length() - 1) != '}') {
            return segment;
        }
        int colon = segment.indexOf(':');
        if (colon <= 1) {
            return segment;
        }
        return "{" + segment.substring(1, colon) + "}";
    }
}
