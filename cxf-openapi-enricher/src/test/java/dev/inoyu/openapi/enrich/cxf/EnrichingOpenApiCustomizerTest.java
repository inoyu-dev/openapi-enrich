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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.inoyu.openapi.enrich.model.DocPack;
import dev.inoyu.openapi.enrich.model.OperationDoc;
import dev.inoyu.openapi.enrich.model.PropertyDoc;
import dev.inoyu.openapi.enrich.model.ResponseDoc;
import dev.inoyu.openapi.enrich.model.SchemaDoc;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import dev.inoyu.openapi.enrich.model.ServletOperationDoc;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Content;

class EnrichingOpenApiCustomizerTest {

    private static final String PERSONA = "org.apache.unomi.api.Persona";
    private static final String PROFILE = "org.apache.unomi.api.Profile";
    private static final String ITEM = "org.apache.unomi.api.Item";
    private static final String SEGMENT = "org.apache.unomi.api.segments.Segment";

    @Test
    void parentFqcnWalkAppliesInheritedPropertyOntoChildSchema() {
        DocPack pack = new DocPack();
        SchemaDoc persona = new SchemaDoc();
        persona.setDescription("A persona");
        persona.setParentFqcn(PROFILE);
        PropertyDoc personaOnly = new PropertyDoc();
        personaOnly.setDescription("Persona display name");
        personaOnly.setType("string");
        persona.getProperties().put("personaName", personaOnly);

        SchemaDoc profile = new SchemaDoc();
        profile.setParentFqcn(ITEM);
        PropertyDoc itemIdOnProfile = new PropertyDoc();
        itemIdOnProfile.setDescription("Unique identifier from Profile");
        itemIdOnProfile.setType("string");
        profile.getProperties().put("itemId", itemIdOnProfile);

        SchemaDoc item = new SchemaDoc();
        PropertyDoc itemIdOnItem = new PropertyDoc();
        itemIdOnItem.setDescription("Unique identifier from Item (should not win)");
        itemIdOnItem.setType("string");
        item.getProperties().put("itemId", itemIdOnItem);
        PropertyDoc itemType = new PropertyDoc();
        itemType.setDescription("Item type");
        itemType.setType("string");
        item.getProperties().put("itemType", itemType);

        pack.getSchemas().put(PERSONA, persona);
        pack.getSchemas().put(PROFILE, profile);
        pack.getSchemas().put(ITEM, item);

        OpenAPI oas = bareOpenApi();
        oas.getComponents().getSchemas().put("Persona", new Schema().type("object"));

        new EnrichingOpenApiCustomizer(pack).customize(oas);

        Schema personaSchema = oas.getComponents().getSchemas().get("Persona");
        assertNotNull(personaSchema.getProperties());
        Schema itemId = (Schema) personaSchema.getProperties().get("itemId");
        assertNotNull(itemId, "parentFqcn walk should project Profile.itemId onto Persona");
        assertEquals("Unique identifier from Profile", itemId.getDescription());
        assertEquals("string", itemId.getType());
        assertNotNull(personaSchema.getProperties().get("personaName"));
        assertNotNull(personaSchema.getProperties().get("itemType"));
        // OpenAPI allOf must not be rewritten for inheritance
        assertNull(personaSchema.getAllOf());
    }

    @Test
    void itemsSchemaClassCreatesArrayItemsRef() {
        DocPack pack = new DocPack();
        SchemaDoc holder = new SchemaDoc();
        PropertyDoc segments = new PropertyDoc();
        segments.setDescription("Matched segments");
        segments.setItemsSchemaClass(SEGMENT);
        holder.getProperties().put("segments", segments);
        pack.getSchemas().put("org.example.Holder", holder);

        OpenAPI oas = bareOpenApi();
        new EnrichingOpenApiCustomizer(pack).customize(oas);

        Schema holderSchema = oas.getComponents().getSchemas().get("Holder");
        assertNotNull(holderSchema);
        Schema segmentsSchema = (Schema) holderSchema.getProperties().get("segments");
        assertEquals("array", segmentsSchema.getType());
        assertNotNull(segmentsSchema.getItems());
        assertEquals("#/components/schemas/Segment", segmentsSchema.getItems().get$ref());
        assertNotNull(oas.getComponents().getSchemas().get("Segment"));
    }

    @Test
    void requestBodySchemaClassFillsMissingRequestBody() {
        DocPack pack = new DocPack();
        SchemaDoc body = new SchemaDoc();
        body.setDescription("Persona payload");
        pack.getSchemas().put(PERSONA, body);

        OperationDoc op = new OperationDoc();
        op.setPath("/personas");
        op.setHttpMethod("POST");
        op.setRequestBodySchemaClass(PERSONA);
        op.setRequestBodyDescription("Persona to create");
        pack.getOperations().add(op);

        OpenAPI oas = bareOpenApi();
        PathItem pathItem = new PathItem();
        Operation post = new Operation();
        post.setResponses(new ApiResponses().addApiResponse("200", new ApiResponse().description("OK")));
        pathItem.setPost(post);
        oas.getPaths().addPathItem("/personas", pathItem);

        new EnrichingOpenApiCustomizer(pack).customize(oas);

        assertNotNull(post.getRequestBody());
        assertEquals("Persona to create", post.getRequestBody().getDescription());
        assertEquals("#/components/schemas/Persona",
                post.getRequestBody().getContent().get("application/json").getSchema().get$ref());
        assertNotNull(oas.getComponents().getSchemas().get("Persona"));
    }

    @Test
    void responseSchemaRefStillWorks() {
        DocPack pack = new DocPack();
        SchemaDoc persona = new SchemaDoc();
        persona.setDescription("A persona");
        pack.getSchemas().put(PERSONA, persona);

        OperationDoc op = new OperationDoc();
        op.setPath("/personas/{id}");
        op.setHttpMethod("GET");
        ResponseDoc ok = new ResponseDoc();
        ok.setDescription("Found");
        ok.setSchemaClass(PERSONA);
        Map<String, ResponseDoc> responses = new LinkedHashMap<>();
        responses.put("200", ok);
        op.setResponses(responses);
        pack.getOperations().add(op);

        OpenAPI oas = bareOpenApi();
        PathItem pathItem = new PathItem();
        Operation get = new Operation();
        get.setResponses(new ApiResponses().addApiResponse("200", new ApiResponse().description("default response")));
        pathItem.setGet(get);
        oas.getPaths().addPathItem("/personas/{id}", pathItem);

        new EnrichingOpenApiCustomizer(pack).customize(oas);

        ApiResponse response = get.getResponses().get("200");
        assertEquals("Found", response.getDescription());
        assertEquals("#/components/schemas/Persona",
                response.getContent().get("application/json").getSchema().get$ref());
        assertNotNull(oas.getComponents().getSchemas().get("Persona"));
        assertEquals("A persona", oas.getComponents().getSchemas().get("Persona").getDescription());
    }

    
    @Test
    void schemaClassSetsPropertyRef() {
        DocPack pack = new DocPack();
        SchemaDoc wrapper = new SchemaDoc();
        PropertyDoc item = new PropertyDoc();
        item.setDescription("Nested item");
        item.setSchemaClass(PERSONA);
        wrapper.getProperties().put("persona", item);
        pack.getSchemas().put("org.example.Wrapper", wrapper);
        SchemaDoc persona = new SchemaDoc();
        persona.setDescription("A persona");
        pack.getSchemas().put(PERSONA, persona);

        OpenAPI oas = bareOpenApi();
        new EnrichingOpenApiCustomizer(pack).customize(oas);

        Schema prop = (Schema) oas.getComponents().getSchemas().get("Wrapper").getProperties().get("persona");
        assertEquals("#/components/schemas/Persona", prop.get$ref());
        assertEquals("Nested item", prop.getDescription());
        assertNotNull(oas.getComponents().getSchemas().get("Persona"));
    }

    @Test
    void responseArrayTrueSetsArrayItemsRef() {
        DocPack pack = new DocPack();
        pack.getSchemas().put(PERSONA, new SchemaDoc());

        OperationDoc op = new OperationDoc();
        op.setPath("/personas");
        op.setHttpMethod("GET");
        ResponseDoc ok = new ResponseDoc();
        ok.setDescription("List");
        ok.setSchemaClass(PERSONA);
        ok.setArray(true);
        Map<String, ResponseDoc> responses = new LinkedHashMap<>();
        responses.put("200", ok);
        op.setResponses(responses);
        pack.getOperations().add(op);

        OpenAPI oas = bareOpenApi();
        PathItem pathItem = new PathItem();
        Operation get = new Operation();
        get.setResponses(new ApiResponses().addApiResponse("200", new ApiResponse().description("OK")));
        pathItem.setGet(get);
        oas.getPaths().addPathItem("/personas", pathItem);

        new EnrichingOpenApiCustomizer(pack).customize(oas);

        Schema schema = get.getResponses().get("200").getContent().get("application/json").getSchema();
        assertEquals("array", schema.getType());
        assertEquals("#/components/schemas/Persona", schema.getItems().get$ref());
    }

    @Test
    void requestBodyDoesNotOverwriteExistingSchema() {
        DocPack pack = new DocPack();
        pack.getSchemas().put(PERSONA, new SchemaDoc());

        OperationDoc op = new OperationDoc();
        op.setPath("/personas");
        op.setHttpMethod("POST");
        op.setRequestBodySchemaClass(PERSONA);
        op.setRequestBodyDescription("from pack");
        pack.getOperations().add(op);

        OpenAPI oas = bareOpenApi();
        PathItem pathItem = new PathItem();
        Operation post = new Operation();
        post.setResponses(new ApiResponses().addApiResponse("200", new ApiResponse().description("OK")));
        RequestBody existing = new RequestBody().description("already set");
        existing.setContent(new Content().addMediaType("application/json",
                new MediaType().schema(new Schema().$ref("#/components/schemas/Existing"))));
        post.setRequestBody(existing);
        pathItem.setPost(post);
        oas.getPaths().addPathItem("/personas", pathItem);

        new EnrichingOpenApiCustomizer(pack).customize(oas);

        assertEquals("already set", post.getRequestBody().getDescription());
        assertEquals("#/components/schemas/Existing",
                post.getRequestBody().getContent().get("application/json").getSchema().get$ref());
    }

    @Test
    void servletOperationsAreInjectedIntoPaths() {
        DocPack pack = new DocPack();
        ServletOperationDoc servlet = new ServletOperationDoc();
        servlet.setPath("/health/check");
        servlet.setHttpMethod("GET");
        servlet.setSummary("Health");
        servlet.setClassName("com.example.HealthServlet");
        servlet.setMethodName("doGet");
        ResponseDoc ok = new ResponseDoc();
        ok.setDescription("Live");
        ok.setSchemaClass(PERSONA);
        ok.setArray(true);
        Map<String, ResponseDoc> responses = new LinkedHashMap<>();
        responses.put("200", ok);
        servlet.setResponses(responses);
        pack.getServletOperations().add(servlet);
        pack.getSchemas().put(PERSONA, new SchemaDoc());

        OpenAPI oas = bareOpenApi();
        new EnrichingOpenApiCustomizer(pack).customize(oas);

        assertNotNull(oas.getPaths().get("/health/check"));
        Operation get = oas.getPaths().get("/health/check").getGet();
        assertNotNull(get);
        assertEquals("Health", get.getSummary());
        Schema schema = get.getResponses().get("200").getContent().get("application/json").getSchema();
        assertEquals("array", schema.getType());
        assertEquals("#/components/schemas/Persona", schema.getItems().get$ref());
    }

    @Test
    void matchesNormalizedAlternatePathKey() {
        DocPack pack = new DocPack();
        OperationDoc op = new OperationDoc();
        op.setPath("/personas/{id}");
        op.setHttpMethod("GET");
        op.setSummary("Get persona");
        pack.getOperations().add(op);

        OpenAPI oas = bareOpenApi();
        PathItem pathItem = new PathItem();
        Operation get = new Operation();
        get.setResponses(new ApiResponses().addApiResponse("200", new ApiResponse().description("OK")));
        pathItem.setGet(get);
        // CXF-style path without leading slash normalization differences: same after normalize
        oas.getPaths().addPathItem("personas/{id}", pathItem);

        new EnrichingOpenApiCustomizer(pack).customize(oas);
        assertEquals("Get persona", get.getSummary());
    }

    @Test
    void coercesJsonExampleStrings() {
        DocPack pack = new DocPack();
        SchemaDoc schema = new SchemaDoc();
        PropertyDoc prop = new PropertyDoc();
        prop.setType("object");
        prop.setExample("{\"a\":1}");
        schema.getProperties().put("payload", prop);
        pack.getSchemas().put("org.example.Holder", schema);

        OpenAPI oas = bareOpenApi();
        new EnrichingOpenApiCustomizer(pack).customize(oas);

        Object example = ((Schema) oas.getComponents().getSchemas().get("Holder")
                .getProperties().get("payload")).getExample();
        assertTrue(example instanceof Map);
        assertEquals(1, ((Map<?, ?>) example).get("a"));
    }



    @Test
    void removesSuppressedPropertiesEmittedByCxf() {
        DocPack pack = new DocPack();
        SchemaDoc schema = new SchemaDoc();
        PropertyDoc type = new PropertyDoc();
        type.setDescription("Condition type id");
        type.setExample("eventTypeCondition");
        type.setType("string");
        type.setJavaName("conditionTypeId");
        schema.getProperties().put("type", type);
        schema.getSuppressedProperties().add("conditionType");
        pack.getSchemas().put("org.example.Condition", schema);

        OpenAPI oas = bareOpenApi();
        Schema cxf = new Schema();
        cxf.setProperties(new LinkedHashMap<>());
        Schema resolved = new Schema();
        resolved.set$ref("#/components/schemas/ConditionType");
        cxf.getProperties().put("conditionType", resolved);
        Schema typeProp = new Schema();
        typeProp.setType("string");
        cxf.getProperties().put("type", typeProp);
        oas.getComponents().getSchemas().put("Condition", cxf);

        new EnrichingOpenApiCustomizer(pack).customize(oas);

        assertNull(cxf.getProperties().get("conditionType"));
        assertNotNull(cxf.getProperties().get("type"));
        assertEquals("eventTypeCondition", ((Schema) cxf.getProperties().get("type")).getExample());
    }

    @Test
    void renamesCxfBeanPropertyToSerializationAlias() {
        DocPack pack = new DocPack();
        SchemaDoc schema = new SchemaDoc();
        PropertyDoc type = new PropertyDoc();
        type.setDescription("Condition type id");
        type.setExample("eventTypeCondition");
        type.setType("string");
        type.setJavaName("conditionTypeId");
        schema.getProperties().put("type", type);
        pack.getSchemas().put("org.example.Condition", schema);

        OpenAPI oas = bareOpenApi();
        Schema cxf = new Schema();
        cxf.setProperties(new LinkedHashMap<>());
        Schema cxfProp = new Schema();
        cxfProp.setType("string");
        cxf.getProperties().put("conditionTypeId", cxfProp);
        oas.getComponents().getSchemas().put("Condition", cxf);

        new EnrichingOpenApiCustomizer(pack).customize(oas);

        assertNull(cxf.getProperties().get("conditionTypeId"));
        assertNotNull(cxf.getProperties().get("type"));
        assertEquals("Condition type id", ((Schema) cxf.getProperties().get("type")).getDescription());
        assertEquals("eventTypeCondition", ((Schema) cxf.getProperties().get("type")).getExample());
    }


    private static OpenAPI bareOpenApi() {
        OpenAPI oas = new OpenAPI();
        oas.setComponents(new Components().schemas(new LinkedHashMap<>()));
        oas.setPaths(new Paths());
        return oas;
    }
    @Test
    void renamesAndRemovesSuppressedTogether() {
        DocPack pack = new DocPack();
        SchemaDoc schema = new SchemaDoc();
        PropertyDoc type = new PropertyDoc();
        type.setDescription("Wire type id");
        type.setExample("eventTypeCondition");
        type.setType("string");
        type.setJavaName("conditionTypeId");
        schema.getProperties().put("type", type);
        schema.getSuppressedProperties().add("conditionType");
        pack.getSchemas().put("org.example.Condition", schema);

        OpenAPI oas = bareOpenApi();
        Schema cxf = new Schema();
        cxf.setProperties(new LinkedHashMap<>());
        cxf.getProperties().put("conditionType", new Schema().$ref("#/components/schemas/ConditionType"));
        cxf.getProperties().put("conditionTypeId", new Schema().type("string"));
        cxf.getProperties().put("parameterValues", new Schema().type("object"));
        oas.getComponents().getSchemas().put("Condition", cxf);

        new EnrichingOpenApiCustomizer(pack).customize(oas);

        assertNull(cxf.getProperties().get("conditionType"));
        assertNull(cxf.getProperties().get("conditionTypeId"));
        assertNotNull(cxf.getProperties().get("type"));
        assertNotNull(cxf.getProperties().get("parameterValues"));
        assertEquals("eventTypeCondition", ((Schema) cxf.getProperties().get("type")).getExample());
    }

    @Test
    void removesSuppressedFromRequiredList() {
        DocPack pack = new DocPack();
        SchemaDoc schema = new SchemaDoc();
        schema.getSuppressedProperties().add("conditionType");
        PropertyDoc type = new PropertyDoc();
        type.setType("string");
        schema.getProperties().put("type", type);
        pack.getSchemas().put("org.example.Condition", schema);

        OpenAPI oas = bareOpenApi();
        Schema cxf = new Schema();
        cxf.setProperties(new LinkedHashMap<>());
        cxf.getProperties().put("conditionType", new Schema());
        cxf.getProperties().put("type", new Schema().type("string"));
        cxf.setRequired(new ArrayList<>(List.of("conditionType", "type")));
        oas.getComponents().getSchemas().put("Condition", cxf);

        new EnrichingOpenApiCustomizer(pack).customize(oas);

        assertNull(cxf.getProperties().get("conditionType"));
        assertFalse(cxf.getRequired().contains("conditionType"));
        assertTrue(cxf.getRequired().contains("type"));
    }

    @Test
    void removesSuppressedFromAllOfParts() {
        DocPack pack = new DocPack();
        SchemaDoc schema = new SchemaDoc();
        schema.getSuppressedProperties().add("conditionType");
        pack.getSchemas().put("org.example.Condition", schema);

        OpenAPI oas = bareOpenApi();
        Schema part = new Schema();
        part.setProperties(new LinkedHashMap<>());
        part.getProperties().put("conditionType", new Schema());
        part.getProperties().put("type", new Schema().type("string"));
        Schema cxf = new Schema();
        cxf.setAllOf(new ArrayList<>());
        cxf.getAllOf().add(part);
        oas.getComponents().getSchemas().put("Condition", cxf);

        new EnrichingOpenApiCustomizer(pack).customize(oas);

        assertNull(part.getProperties().get("conditionType"));
        assertNotNull(part.getProperties().get("type"));
    }

    @Test
    void parentSuppressedPropertiesRemovedFromChildSchema() {
        DocPack pack = new DocPack();
        SchemaDoc parent = new SchemaDoc();
        parent.getSuppressedProperties().add("scope");
        PropertyDoc meta = new PropertyDoc();
        meta.setType("object");
        parent.getProperties().put("metadata", meta);
        pack.getSchemas().put("org.example.MetadataItem", parent);

        SchemaDoc child = new SchemaDoc();
        child.setParentFqcn("org.example.MetadataItem");
        PropertyDoc cond = new PropertyDoc();
        cond.setType("object");
        child.getProperties().put("condition", cond);
        pack.getSchemas().put("org.example.Rule", child);

        OpenAPI oas = bareOpenApi();
        Schema cxf = new Schema();
        cxf.setProperties(new LinkedHashMap<>());
        cxf.getProperties().put("scope", new Schema().type("string"));
        cxf.getProperties().put("metadata", new Schema().type("object"));
        cxf.getProperties().put("condition", new Schema().type("object"));
        oas.getComponents().getSchemas().put("Rule", cxf);

        new EnrichingOpenApiCustomizer(pack).customize(oas);

        assertNull(cxf.getProperties().get("scope"));
        assertNotNull(cxf.getProperties().get("metadata"));
        assertNotNull(cxf.getProperties().get("condition"));
    }

    @Test
    void childSuppressWinsOverParentDocumentedProperty() {
        // Profile.scope is @XmlTransient but Item.scope is a real documented field.
        // Applying parent docs must not resurrect the suppressed child property.
        DocPack pack = new DocPack();
        SchemaDoc item = new SchemaDoc();
        PropertyDoc itemScope = new PropertyDoc();
        itemScope.setDescription("Scope that groups related items");
        itemScope.setType("string");
        item.getProperties().put("scope", itemScope);
        PropertyDoc itemId = new PropertyDoc();
        itemId.setType("string");
        item.getProperties().put("itemId", itemId);
        pack.getSchemas().put("org.example.Item", item);

        SchemaDoc profile = new SchemaDoc();
        profile.setParentFqcn("org.example.Item");
        profile.getSuppressedProperties().add("scope");
        profile.getSuppressedProperties().add("anonymousProfile");
        PropertyDoc props = new PropertyDoc();
        props.setType("object");
        profile.getProperties().put("properties", props);
        pack.getSchemas().put("org.example.Profile", profile);

        OpenAPI oas = bareOpenApi();
        Schema cxf = new Schema();
        cxf.setProperties(new LinkedHashMap<>());
        cxf.getProperties().put("scope", new Schema().type("string"));
        cxf.getProperties().put("itemId", new Schema().type("string"));
        cxf.getProperties().put("properties", new Schema().type("object"));
        cxf.getProperties().put("anonymousProfile", new Schema().type("boolean"));
        oas.getComponents().getSchemas().put("Profile", cxf);

        new EnrichingOpenApiCustomizer(pack).customize(oas);

        assertNull(cxf.getProperties().get("scope"));
        assertNull(cxf.getProperties().get("anonymousProfile"));
        assertNotNull(cxf.getProperties().get("itemId"));
        assertNotNull(cxf.getProperties().get("properties"));
        assertEquals("Scope that groups related items",
                ((Schema) oas.getComponents().getSchemas().get("Item").getProperties().get("scope")).getDescription());
    }

    @Test
    void packJsonRoundTripPreservesSuppressedAndAliasThenEnricherApplies() throws Exception {
        DocPack original = new DocPack();
        SchemaDoc schema = new SchemaDoc();
        PropertyDoc type = new PropertyDoc();
        type.setDescription("id");
        type.setExample("setPropertyAction");
        type.setType("string");
        type.setJavaName("actionTypeId");
        schema.getProperties().put("type", type);
        schema.getSuppressedProperties().add("actionType");
        original.getSchemas().put("org.example.Action", schema);

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(original);
        assertTrue(json.contains("suppressedProperties"));
        assertTrue(json.contains("actionType"));
        DocPack loaded = mapper.readValue(json, DocPack.class);
        assertEquals(List.of("actionType"), loaded.getSchemas().get("org.example.Action").getSuppressedProperties());
        assertEquals("actionTypeId",
                loaded.getSchemas().get("org.example.Action").getProperties().get("type").getJavaName());

        OpenAPI oas = bareOpenApi();
        Schema cxf = new Schema();
        cxf.setProperties(new LinkedHashMap<>());
        cxf.getProperties().put("actionType", new Schema());
        cxf.getProperties().put("actionTypeId", new Schema().type("string"));
        oas.getComponents().getSchemas().put("Action", cxf);

        new EnrichingOpenApiCustomizer(loaded).customize(oas);

        assertNull(cxf.getProperties().get("actionType"));
        assertNull(cxf.getProperties().get("actionTypeId"));
        assertNotNull(cxf.getProperties().get("type"));
        assertEquals("setPropertyAction", ((Schema) cxf.getProperties().get("type")).getExample());
    }


}
