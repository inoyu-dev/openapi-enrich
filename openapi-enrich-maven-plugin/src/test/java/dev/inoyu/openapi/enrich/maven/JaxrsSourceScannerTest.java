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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.inoyu.openapi.enrich.model.DocPack;
import dev.inoyu.openapi.enrich.model.OperationDoc;
import dev.inoyu.openapi.enrich.model.ResponseDoc;
import dev.inoyu.openapi.enrich.model.PropertyDoc;
import dev.inoyu.openapi.enrich.model.SchemaDoc;

class JaxrsSourceScannerTest {

    @Test
    void scansPathResourceFromSourceString() {
        String source = ""
                + "package com.example.api;\n"
                + "\n"
                + "import javax.ws.rs.GET;\n"
                + "import javax.ws.rs.Path;\n"
                + "import javax.ws.rs.PathParam;\n"
                + "\n"
                + "@Path(\"/tenants\")\n"
                + "public class TenantResource {\n"
                + "\n"
                + "    /**\n"
                + "     * Get a tenant. Full description here.\n"
                + "     *\n"
                + "     * @param tenantId tenant identifier\n"
                + "     * @return the tenant\n"
                + "     * @api.status 200 com.example.api.Tenant\n"
                + "     * @api.status 404 empty\n"
                + "     */\n"
                + "    @GET\n"
                + "    @Path(\"/{tenantId}\")\n"
                + "    public Tenant getTenant(@PathParam(\"tenantId\") String tenantId) {\n"
                + "        return null;\n"
                + "    }\n"
                + "}\n"
                + "\n"
                + "class Tenant {}\n";

        JaxrsSourceScanner scanner = new JaxrsSourceScanner(
                List.of(), List.of(), List.of(), "api.status", "test-bundle", Map.of());
        DocPack pack = scanner.scanSource(source);

        assertEquals("test-bundle", pack.getBundleId());
        assertEquals(1, pack.getOperations().size());

        OperationDoc op = pack.getOperations().get(0);
        assertEquals("GET /tenants/{tenantId}", op.getOperationKey());
        assertEquals("com.example.api.TenantResource", op.getClassName());
        assertEquals("getTenant", op.getMethodName());
        assertEquals("GET", op.getHttpMethod());
        assertEquals("/tenants/{tenantId}", op.getPath());
        assertEquals("Get a tenant.", op.getSummary());
        assertTrue(op.getDescription() != null && op.getDescription().contains("Full description"));
        assertNotNull(op.getParameters().get("tenantId"));
        assertEquals("tenant identifier", op.getParameters().get("tenantId").getDescription());

        ResponseDoc ok = op.getResponses().get("200");
        assertNotNull(ok);
        assertEquals("the tenant", ok.getDescription());
        assertEquals("com.example.api.Tenant", ok.getSchemaClass());

        ResponseDoc missing = op.getResponses().get("404");
        assertNotNull(missing);
        assertTrue(missing.getSchemaClass() == null || missing.getSchemaClass().isBlank());
        assertFalse(pack.getOperations().isEmpty());
    }


    @Test
    void stripsJaxrsPathParamRegexesToMatchOpenApi() throws Exception {
        Path dir = Files.createTempDirectory("openapi-doc-scan-regex");
        Path pkg = dir.resolve("com/example/api");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("GeoResource.java"), ""
                + "package com.example.api;\n"
                + "\n"
                + "import javax.ws.rs.GET;\n"
                + "import javax.ws.rs.Path;\n"
                + "import javax.ws.rs.PathParam;\n"
                + "\n"
                + "@Path(\"/geonames\")\n"
                + "public class GeoResource {\n"
                + "    /** Cities under hierarchy.\n"
                + "     * @api.status 200 empty Partial list of cities.\n"
                + "     * @api.example {\"list\":[{\"name\":\"Paris\"}]}\n"
                + "     */\n"
                + "    @GET\n"
                + "    @Path(\"/cities/{items:.*}\")\n"
                + "    public Object cities(@PathParam(\"items\") String items) { return null; }\n"
                + "\n"
                + "    /** Metrics for a property.\n"
                + "     * @api.status 200 empty Metric map.\n"
                + "     * @api.example {\"sum\":10.0}\n"
                + "     */\n"
                + "    @GET\n"
                + "    @Path(\"/metrics/{metricTypes:((sum|avg)/?)*}\")\n"
                + "    public Object metrics(@PathParam(\"metricTypes\") String metricTypes) { return null; }\n"
                + "}\n");

        JaxrsSourceScanner scanner = new JaxrsSourceScanner(
                List.of(dir), List.of(), List.of(), "api.status", "test-bundle", Map.of());
        DocPack pack = scanner.scan();
        assertEquals(2, pack.getOperations().size());
        assertEquals("GET /geonames/cities/{items}", pack.getOperations().get(0).getOperationKey());
        assertEquals("/geonames/cities/{items}", pack.getOperations().get(0).getPath());
        assertEquals("GET /geonames/metrics/{metricTypes}", pack.getOperations().get(1).getOperationKey());
        assertEquals("/geonames/metrics/{metricTypes}", pack.getOperations().get(1).getPath());
    }

    @Test
    void scansSchemaFieldsEnumsAndExamples() throws Exception {
        Path dir = Files.createTempDirectory("openapi-doc-scan");
        Path pkg = dir.resolve("com/example/api");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Health.java"), ""
                + "package com.example.api;\n"
                + "\n"
                + "/** Health payload. */\n"
                + "public class Health {\n"
                + "    /** Provider name.\n"
                + "     * @api.example elasticsearch\n"
                + "     */\n"
                + "    private String name;\n"
                + "    /** Check outcome. */\n"
                + "    private Status status;\n"
                + "    public enum Status { DOWN, UP, LIVE, ERROR }\n"
                + "}\n");
        Files.writeString(pkg.resolve("HealthServlet.java"), ""
                + "package com.example.api;\n"
                + "\n"
                + "import javax.servlet.http.HttpServlet;\n"
                + "\n"
                + "/**\n"
                + " * Health endpoint.\n"
                + " * @api.status 200 array com.example.api.Health All live.\n"
                + " * @api.status 206 array com.example.api.Health Partial.\n"
                + " * @api.example [{\"name\":\"karaf\",\"status\":\"LIVE\"}]\n"
                + " */\n"
                + "public class HealthServlet extends HttpServlet {}\n");

        JaxrsSourceScanner scanner = new JaxrsSourceScanner(
                List.of(dir), List.of(), List.of(), "api.status", "test-bundle",
                Map.of("com.example.api.HealthServlet", "/health/check"));
        DocPack pack = scanner.scan();

        assertEquals(1, pack.getServletOperations().size());
        var op = pack.getServletOperations().get(0);
        assertEquals("/health/check", op.getPath());
        assertTrue(op.getResponses().get("200").isArray());
        assertEquals("All live.", op.getResponses().get("200").getDescription());
        assertEquals("Partial.", op.getResponses().get("206").getDescription());
        assertNotNull(op.getResponses().get("200").getExample());

        var schema = pack.getSchemas().get("com.example.api.Health");
        assertNotNull(schema);
        assertEquals("Provider name.", schema.getProperties().get("name").getDescription());
        assertEquals("elasticsearch", schema.getProperties().get("name").getExample());
        assertEquals("string", schema.getProperties().get("status").getType());
        assertEquals(List.of("DOWN", "UP", "LIVE", "ERROR"), schema.getProperties().get("status").getEnumValues());
    }

    @Test
    void mapsJavadocParamToQueryParamName() {
        String source = ""
                + "package com.example.api;\n"
                + "\n"
                + "import javax.ws.rs.GET;\n"
                + "import javax.ws.rs.Path;\n"
                + "import javax.ws.rs.PathParam;\n"
                + "import javax.ws.rs.QueryParam;\n"
                + "\n"
                + "@Path(\"/tenants\")\n"
                + "public class TenantResource {\n"
                + "\n"
                + "    /**\n"
                + "     * Validate a key.\n"
                + "     *\n"
                + "     * @param tenantId tenant id\n"
                + "     * @param apiKey plaintext API key\n"
                + "     * @param type PUBLIC or PRIVATE\n"
                + "     */\n"
                + "    @GET\n"
                + "    @Path(\"/{tenantId}/apikeys/validate\")\n"
                + "    public void validate(@PathParam(\"tenantId\") String tenantId,\n"
                + "                        @QueryParam(\"key\") String apiKey,\n"
                + "                        @QueryParam(\"type\") String type) {}\n"
                + "}\n";

        JaxrsSourceScanner scanner = new JaxrsSourceScanner(
                List.of(), List.of(), List.of(), "api.status", "test-bundle", Map.of());
        DocPack pack = scanner.scanSource(source);
        OperationDoc op = pack.getOperations().get(0);

        assertEquals("tenant id", op.getParameters().get("tenantId").getDescription());
        assertEquals("plaintext API key", op.getParameters().get("key").getDescription());
        assertEquals("PUBLIC or PRIVATE", op.getParameters().get("type").getDescription());
        assertFalse(op.getParameters().containsKey("apiKey"));
    }


    @Test
    void scansInheritedHttpMethodsWithTypeVarBinding() throws Exception {
        Path dir = Files.createTempDirectory("jaxrs-openapi-inherit");
        Files.writeString(dir.resolve("BaseResource.java"), ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.GET;\n"
                + "import javax.ws.rs.Path;\n"
                + "import javax.ws.rs.PathParam;\n"
                + "public abstract class BaseResource<T> {\n"
                + "  /**\n"
                + "   * List items.\n"
                + "   * @api.status 200 array java.lang.String\n"
                + "   */\n"
                + "  @GET\n"
                + "  public java.util.List<T> list() { return null; }\n"
                + "  /**\n"
                + "   * Get one.\n"
                + "   * @param id id\n"
                + "   * @api.status 200 T\n"
                + "   */\n"
                + "  @GET\n"
                + "  @Path(\"{id}\")\n"
                + "  public T get(@PathParam(\"id\") String id) { return null; }\n"
                + "}\n");
        Files.writeString(dir.resolve("Widget.java"), ""
                + "package com.example.api;\n"
                + "/** A widget. */\n"
                + "public class Widget {\n"
                + "  /** Name. */\n"
                + "  private String name;\n"
                + "}\n");
        Files.writeString(dir.resolve("WidgetResource.java"), ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.Path;\n"
                + "@Path(\"/widgets\")\n"
                + "public class WidgetResource extends BaseResource<Widget> {\n"
                + "}\n");

        JaxrsSourceScanner scanner = new JaxrsSourceScanner(
                List.of(dir), List.of(dir), List.of("com.example.api"),
                "api.status", "test", Map.of());
        DocPack pack = scanner.scan();
        assertTrue(pack.getOperations().stream().anyMatch(o ->
                "com.example.api.WidgetResource".equals(o.getClassName())
                        && "GET /widgets".equals(o.getOperationKey())));
        assertTrue(pack.getOperations().stream().anyMatch(o ->
                "GET /widgets/{id}".equals(o.getOperationKey())
                        && "Widget".equals(simpleName(
                        o.getResponses().get("200").getSchemaClass()))));
    }

    @Test
    void partialListReturnSetsEnvelopeAndListItemsSchemaClass() throws Exception {
        Path dir = Files.createTempDirectory("jaxrs-openapi-pl");
        Files.writeString(dir.resolve("PartialList.java"), ""
                + "package com.example.api;\n"
                + "import java.util.List;\n"
                + "public class PartialList<T> {\n"
                + "  /** Page of items. */\n"
                + "  private List<T> list;\n"
                + "  private long totalSize;\n"
                + "}\n");
        Files.writeString(dir.resolve("Profile.java"), ""
                + "package com.example.api;\n"
                + "public class Profile { private String id; }\n");
        Files.writeString(dir.resolve("ProfilesResource.java"), ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.GET;\n"
                + "import javax.ws.rs.Path;\n"
                + "@Path(\"/profiles\")\n"
                + "public class ProfilesResource {\n"
                + "  @GET\n"
                + "  public PartialList<Profile> search() { return null; }\n"
                + "}\n");

        JaxrsSourceScanner scanner = new JaxrsSourceScanner(
                List.of(dir), List.of(dir), List.of("com.example.api"),
                "api.status", "test", Map.of());
        DocPack pack = scanner.scan();
        OperationDoc op = pack.getOperations().get(0);
        ResponseDoc ok = op.getResponses().get("200");
        assertNotNull(ok);
        assertFalse(ok.isArray());
        assertTrue(ok.getSchemaClass().endsWith(".PartialList"));
        SchemaDoc pl = pack.getSchemas().get("com.example.api.PartialList");
        assertNotNull(pl);
        assertEquals("com.example.api.Profile", pl.getProperties().get("list").getItemsSchemaClass());
        assertTrue(pack.getSchemas().containsKey("com.example.api.Profile"));
    }

    @Test
    void entityParamSetsRequestBodySchemaClass() {
        String source = ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.POST;\n"
                + "import javax.ws.rs.Path;\n"
                + "@Path(\"/widgets\")\n"
                + "public class WidgetWriteResource {\n"
                + "  @POST\n"
                + "  public void create(Widget body) {}\n"
                + "}\n"
                + "class Widget {}\n";
        JaxrsSourceScanner scanner = new JaxrsSourceScanner(
                List.of(), List.of(), List.of(), "api.status", "test", Map.of());
        DocPack pack = scanner.scanSource(source);
        assertEquals("com.example.api.Widget", pack.getOperations().get(0).getRequestBodySchemaClass());
    }

    private static String simpleName(String fqcn) {
        if (fqcn == null) {
            return null;
        }
        int i = fqcn.lastIndexOf('.');
        return i >= 0 ? fqcn.substring(i + 1) : fqcn;
    }


    @Test
    void starImportResolvesRequestBodySchema() throws Exception {
        Path dir = Files.createTempDirectory("openapi-enrich-star");
        Path api = dir.resolve("api");
        Path rest = dir.resolve("rest");
        Files.createDirectories(api.resolve("com/example/api"));
        Files.createDirectories(rest.resolve("com/example/rest"));
        Files.writeString(api.resolve("com/example/api/ContextRequest.java"), ""
                + "package com.example.api;\n"
                + "/** Context request body. */\n"
                + "public class ContextRequest {\n"
                + "  /** Session id. */\n"
                + "  private String sessionId;\n"
                + "}\n");
        Files.writeString(rest.resolve("com/example/rest/ContextResource.java"), ""
                + "package com.example.rest;\n"
                + "import javax.ws.rs.POST;\n"
                + "import javax.ws.rs.Path;\n"
                + "import com.example.api.*;\n"
                + "@Path(\"/context\")\n"
                + "public class ContextResource {\n"
                + "  /**\n"
                + "   * Post context.\n"
                + "   * @api.status 200 ContextRequest\n"
                + "   */\n"
                + "  @POST\n"
                + "  public ContextRequest post(ContextRequest body) { return body; }\n"
                + "}\n");

        JaxrsSourceScanner scanner = new JaxrsSourceScanner(
                List.of(rest), List.of(api), List.of("com.example.rest"),
                "api.status", "test", Map.of());
        DocPack pack = scanner.scan();
        assertTrue(pack.getSchemas().containsKey("com.example.api.ContextRequest"));
        OperationDoc op = pack.getOperations().get(0);
        assertEquals("com.example.api.ContextRequest", op.getRequestBodySchemaClass());
        assertEquals("com.example.api.ContextRequest", op.getResponses().get("200").getSchemaClass());
    }

    @Test
    void returnTagDoesNotInvent200When204Present() {
        String source = ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.OPTIONS;\n"
                + "import javax.ws.rs.Path;\n"
                + "@Path(\"/widgets\")\n"
                + "public class OptionsResource {\n"
                + "  /**\n"
                + "   * CORS preflight.\n"
                + "   * @return no content\n"
                + "   * @api.status 204 empty No body\n"
                + "   */\n"
                + "  @OPTIONS\n"
                + "  public void options() {}\n"
                + "}\n";
        DocPack pack = new JaxrsSourceScanner(
                List.of(), List.of(), List.of(), "api.status", "test", Map.of()).scanSource(source);
        OperationDoc op = pack.getOperations().get(0);
        assertNotNull(op.getResponses().get("204"));
        assertEquals("No body", op.getResponses().get("204").getDescription());
        // @return description may land on 204; must not invent a bare 200
        assertNull(op.getResponses().get("200"));
    }

    @Test
    void skipsTransientXmlTransientAndJsonIgnoreMembers() throws Exception {
        Path dir = Files.createTempDirectory("openapi-enrich-skip");
        Files.writeString(dir.resolve("Event.java"), ""
                + "package com.example.api;\n"
                + "import com.fasterxml.jackson.annotation.JsonIgnore;\n"
                + "import javax.xml.bind.annotation.XmlTransient;\n"
                + "import java.util.List;\n"
                + "/** An event. */\n"
                + "public class Event {\n"
                + "  /** Visible. */\n"
                + "  private String eventType;\n"
                + "  private transient Profile profile;\n"
                + "  private transient List<Hook> hooks;\n"
                + "  private Secret secret;\n"
                + "  /** Ignored field. */\n"
                + "  @JsonIgnore\n"
                + "  private String ignored;\n"
                + "  /**\n"
                + "   * Resolved type — not serialized.\n"
                + "   */\n"
                + "  @XmlTransient\n"
                + "  public Secret getSecret() { return secret; }\n"
                + "}\n"
                + "class Profile {}\n"
                + "class Hook {}\n"
                + "class Secret {}\n");
        Files.writeString(dir.resolve("EventsResource.java"), ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.GET;\n"
                + "import javax.ws.rs.Path;\n"
                + "@Path(\"/events\")\n"
                + "public class EventsResource {\n"
                + "  @GET\n"
                + "  public Event get() { return null; }\n"
                + "}\n");

        DocPack pack = new JaxrsSourceScanner(
                List.of(dir), List.of(dir), List.of("com.example.api"),
                "api.status", "test", Map.of()).scan();
        SchemaDoc event = pack.getSchemas().get("com.example.api.Event");
        assertNotNull(event);
        assertTrue(event.getProperties().containsKey("eventType"));
        assertFalse(event.getProperties().containsKey("profile"));
        assertFalse(event.getProperties().containsKey("hooks"));
        assertFalse(event.getProperties().containsKey("ignored"));
        assertFalse(event.getProperties().containsKey("secret"));
        assertFalse(pack.getSchemas().containsKey("com.example.api.Hook"));
        assertTrue(event.getSuppressedProperties().contains("secret"));
        assertTrue(event.getSuppressedProperties().contains("ignored"));
        assertTrue(event.getSuppressedProperties().contains("profile"));
        assertTrue(event.getSuppressedProperties().contains("hooks"));
    }

    @Test
    void complexQueryParamEnqueuesSchemaWithoutRequestBody() throws Exception {
        Path dir = Files.createTempDirectory("openapi-enrich-qparam");
        Files.writeString(dir.resolve("Payload.java"), ""
                + "package com.example.api;\n"
                + "/** Query payload. */\n"
                + "public class Payload { private String id; }\n");
        Files.writeString(dir.resolve("SearchResource.java"), ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.GET;\n"
                + "import javax.ws.rs.Path;\n"
                + "import javax.ws.rs.QueryParam;\n"
                + "@Path(\"/search\")\n"
                + "public class SearchResource {\n"
                + "  @GET\n"
                + "  public String search(@QueryParam(\"payload\") Payload payload) { return \"ok\"; }\n"
                + "}\n");
        DocPack pack = new JaxrsSourceScanner(
                List.of(dir), List.of(dir), List.of("com.example.api"),
                "api.status", "test", Map.of()).scan();
        assertTrue(pack.getSchemas().containsKey("com.example.api.Payload"));
        assertNull(pack.getOperations().get(0).getRequestBodySchemaClass());
    }

    @Test
    void objectPropertySetsSchemaClass() throws Exception {
        Path dir = Files.createTempDirectory("openapi-enrich-ref");
        Files.writeString(dir.resolve("Item.java"), ""
                + "package com.example.api;\n"
                + "public class Item { private String id; }\n");
        Files.writeString(dir.resolve("Wrapper.java"), ""
                + "package com.example.api;\n"
                + "/** Wrapper. */\n"
                + "public class Wrapper {\n"
                + "  /** Nested item. */\n"
                + "  private Item item;\n"
                + "}\n");
        Files.writeString(dir.resolve("WrapResource.java"), ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.GET;\n"
                + "import javax.ws.rs.Path;\n"
                + "@Path(\"/wrap\")\n"
                + "public class WrapResource {\n"
                + "  @GET\n"
                + "  public Wrapper get() { return null; }\n"
                + "}\n");
        DocPack pack = new JaxrsSourceScanner(
                List.of(dir), List.of(dir), List.of("com.example.api"),
                "api.status", "test", Map.of()).scan();
        SchemaDoc wrapper = pack.getSchemas().get("com.example.api.Wrapper");
        assertNotNull(wrapper);
        assertEquals("com.example.api.Item", wrapper.getProperties().get("item").getSchemaClass());
        assertTrue(pack.getSchemas().containsKey("com.example.api.Item"));
    }

    @Test
    void deprecatedTagFallbackForEmptyPropertyBody() throws Exception {
        Path dir = Files.createTempDirectory("openapi-enrich-depr");
        Files.writeString(dir.resolve("Legacy.java"), ""
                + "package com.example.api;\n"
                + "/** Legacy. */\n"
                + "public class Legacy {\n"
                + "  /**\n"
                + "   * @deprecated use newField instead\n"
                + "   */\n"
                + "  @Deprecated\n"
                + "  private String oldField;\n"
                + "}\n");
        Files.writeString(dir.resolve("LegacyResource.java"), ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.GET;\n"
                + "import javax.ws.rs.Path;\n"
                + "@Path(\"/legacy\")\n"
                + "public class LegacyResource {\n"
                + "  @GET\n"
                + "  public Legacy get() { return null; }\n"
                + "}\n");
        DocPack pack = new JaxrsSourceScanner(
                List.of(dir), List.of(dir), List.of("com.example.api"),
                "api.status", "test", Map.of()).scan();
        String desc = pack.getSchemas().get("com.example.api.Legacy")
                .getProperties().get("oldField").getDescription();
        assertTrue(desc != null && desc.toLowerCase().contains("deprecated"));
        assertTrue(desc.contains("newField"));
    }



    @Test
    void indexesOuterInnerNestedTypes() throws Exception {
        Path dir = Files.createTempDirectory("openapi-enrich-nested");
        Files.writeString(dir.resolve("PersonalizationService.java"), ""
                + "package com.example.api;\n"
                + "import java.util.List;\n"
                + "public class PersonalizationService {\n"
                + "  /** Content candidate. */\n"
                + "  public static class PersonalizedContent {\n"
                + "    /** Content id. */\n"
                + "    private String id;\n"
                + "    private List<Filter> filters;\n"
                + "  }\n"
                + "  /** Filter. */\n"
                + "  public static class Filter {\n"
                + "    /** Filter id. */\n"
                + "    private String id;\n"
                + "  }\n"
                + "}\n");
        Files.writeString(dir.resolve("ContextRequest.java"), ""
                + "package com.example.api;\n"
                + "import java.util.List;\n"
                + "/** Request. */\n"
                + "public class ContextRequest {\n"
                + "  /** Contents. */\n"
                + "  private List<PersonalizationService.PersonalizedContent> contents;\n"
                + "}\n");
        Files.writeString(dir.resolve("ContextResource.java"), ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.POST;\n"
                + "import javax.ws.rs.Path;\n"
                + "@Path(\"/context\")\n"
                + "public class ContextResource {\n"
                + "  @POST\n"
                + "  public ContextRequest post(ContextRequest body) { return body; }\n"
                + "}\n");
        DocPack pack = new JaxrsSourceScanner(
                List.of(dir), List.of(dir), List.of("com.example.api"),
                "api.status", "test", Map.of()).scan();
        String key = "com.example.api.PersonalizationService.PersonalizedContent";
        assertNotNull(pack.getSchemas().get(key));
        assertTrue(pack.getSchemas().get(key).getProperties().containsKey("id"));
        assertTrue(pack.getSchemas().containsKey("com.example.api.PersonalizationService.Filter"));
    }



    @Test
    void partialListApiStatusStillIndexesItemSchema() throws Exception {
        Path dir = Files.createTempDirectory("jaxrs-openapi-pl-status");
        Files.writeString(dir.resolve("PartialList.java"), ""
                + "package com.example.api;\n"
                + "import java.util.List;\n"
                + "public class PartialList<T> {\n"
                + "  /** Page of items. */\n"
                + "  private List<T> list;\n"
                + "}\n");
        Files.writeString(dir.resolve("ProfileAlias.java"), ""
                + "package com.example.api;\n"
                + "public class ProfileAlias {\n"
                + "  /** Canonical profile id. */\n"
                + "  private String profileID;\n"
                + "}\n");
        Files.writeString(dir.resolve("ProfilesResource.java"), ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.GET;\n"
                + "import javax.ws.rs.Path;\n"
                + "@Path(\"/profiles\")\n"
                + "public class ProfilesResource {\n"
                + "  /**\n"
                + "   * List aliases.\n"
                + "   * @api.status 200 com.example.api.PartialList Aliases page.\n"
                + "   */\n"
                + "  @GET @Path(\"/{id}/aliases\")\n"
                + "  public PartialList<ProfileAlias> list() { return null; }\n"
                + "}\n");
        DocPack pack = new JaxrsSourceScanner(
                List.of(dir), List.of(dir), List.of("com.example.api"),
                "api.status", "test", Map.of()).scan();
        assertTrue(pack.getSchemas().containsKey("com.example.api.ProfileAlias"),
                "item schema must be indexed even when @api.status names PartialList");
        assertEquals("com.example.api.ProfileAlias",
                pack.getSchemas().get("com.example.api.PartialList")
                        .getProperties().get("list").getItemsSchemaClass());
    }



    @Test
    void xmlElementOnGetterRenamesPropertyAndKeepsJavaName() throws Exception {
        Path dir = Files.createTempDirectory("openapi-enrich-xml-alias");
        Files.writeString(dir.resolve("Condition.java"), ""
                + "package com.example.api;\n"
                + "import javax.xml.bind.annotation.XmlElement;\n"
                + "import java.util.Map;\n"
                + "public class Condition {\n"
                + "  /** Identifier of the condition type. */\n"
                + "  private String conditionTypeId;\n"
                + "  private Map<String, Object> parameterValues;\n"
                + "  /**\n"
                + "   * Wire type id.\n"
                + "   * @api.example eventTypeCondition\n"
                + "   */\n"
                + "  @XmlElement(name = \"type\")\n"
                + "  public String getConditionTypeId() { return conditionTypeId; }\n"
                + "  public Map<String, Object> getParameterValues() { return parameterValues; }\n"
                + "}\n");
        Files.writeString(dir.resolve("Rules.java"), ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.GET;\n"
                + "import javax.ws.rs.Path;\n"
                + "@Path(\"/rules\")\n"
                + "public class Rules {\n"
                + "  @GET public Condition get() { return null; }\n"
                + "}\n");
        DocPack pack = new JaxrsSourceScanner(
                List.of(dir), List.of(dir), List.of("com.example.api"),
                "api.status", "test", Map.of()).scan();
        SchemaDoc schema = pack.getSchemas().get("com.example.api.Condition");
        assertNotNull(schema);
        assertTrue(schema.getProperties().containsKey("type"), schema.getProperties().keySet().toString());
        assertFalse(schema.getProperties().containsKey("conditionTypeId"));
        PropertyDoc type = schema.getProperties().get("type");
        assertEquals("conditionTypeId", type.getJavaName());
        // Field javadoc wins over thinner getter body for description when field set first —
        // field has "Identifier of the condition type." and getter has example.
        assertTrue(type.getDescription() != null && type.getDescription().toLowerCase().contains("condition"));
        assertEquals("eventTypeCondition", type.getExample());
        assertTrue(schema.getProperties().containsKey("parameterValues"));
    }

    @Test
    void jsonPropertyOnSetterRenamesProperty() throws Exception {
        Path dir = Files.createTempDirectory("openapi-enrich-json-alias");
        Files.writeString(dir.resolve("Payload.java"), ""
                + "package com.example.api;\n"
                + "import com.fasterxml.jackson.annotation.JsonProperty;\n"
                + "public class Payload {\n"
                + "  private String actionTypeId;\n"
                + "  public String getActionTypeId() { return actionTypeId; }\n"
                + "  /** Action type wire name. */\n"
                + "  @JsonProperty(\"type\")\n"
                + "  public void setActionTypeId(String actionTypeId) { this.actionTypeId = actionTypeId; }\n"
                + "}\n");
        Files.writeString(dir.resolve("Res.java"), ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.POST;\n"
                + "import javax.ws.rs.Path;\n"
                + "@Path(\"/x\")\n"
                + "public class Res {\n"
                + "  @POST public Payload post(Payload body) { return body; }\n"
                + "}\n");
        DocPack pack = new JaxrsSourceScanner(
                List.of(dir), List.of(dir), List.of("com.example.api"),
                "api.status", "test", Map.of()).scan();
        SchemaDoc schema = pack.getSchemas().get("com.example.api.Payload");
        assertNotNull(schema.getProperties().get("type"));
        assertEquals("actionTypeId", schema.getProperties().get("type").getJavaName());
        assertTrue(schema.getProperties().get("type").getDescription().contains("wire"));
    }

    @Test
    void recordsXmlTransientAlongsideSerializationAlias() throws Exception {
        Path dir = Files.createTempDirectory("openapi-enrich-suppress-alias");
        Files.writeString(dir.resolve("Condition.java"), ""
                + "package com.example.api;\n"
                + "import javax.xml.bind.annotation.XmlElement;\n"
                + "import javax.xml.bind.annotation.XmlTransient;\n"
                + "public class Condition {\n"
                + "  private Object conditionType;\n"
                + "  private String conditionTypeId;\n"
                + "  /** Resolved type — not on the wire. */\n"
                + "  @XmlTransient\n"
                + "  public Object getConditionType() { return conditionType; }\n"
                + "  /** Wire type id. */\n"
                + "  @XmlElement(name = \"type\")\n"
                + "  public String getConditionTypeId() { return conditionTypeId; }\n"
                + "}\n");
        Files.writeString(dir.resolve("Res.java"), ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.GET;\n"
                + "import javax.ws.rs.Path;\n"
                + "@Path(\"/c\")\n"
                + "public class Res { @GET public Condition get() { return null; } }\n");
        DocPack pack = new JaxrsSourceScanner(
                List.of(dir), List.of(dir), List.of("com.example.api"),
                "api.status", "test", Map.of()).scan();
        SchemaDoc schema = pack.getSchemas().get("com.example.api.Condition");
        assertNotNull(schema.getProperties().get("type"));
        assertEquals("conditionTypeId", schema.getProperties().get("type").getJavaName());
        assertFalse(schema.getProperties().containsKey("conditionType"));
        assertTrue(schema.getSuppressedProperties().contains("conditionType"));
    }

    @Test
    void fieldJavadocWinsOverGetterJavadoc() throws Exception {
        Path dir = Files.createTempDirectory("openapi-enrich-field-wins");
        Files.writeString(dir.resolve("Bean.java"), ""
                + "package com.example.api;\n"
                + "public class Bean {\n"
                + "  /** Field description.\n"
                + "   * @api.example from-field\n"
                + "   */\n"
                + "  private String name;\n"
                + "  /** Getter description.\n"
                + "   * @api.example from-getter\n"
                + "   */\n"
                + "  public String getName() { return name; }\n"
                + "}\n");
        Files.writeString(dir.resolve("Res.java"), ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.GET;\n"
                + "import javax.ws.rs.Path;\n"
                + "@Path(\"/b\")\n"
                + "public class Res { @GET public Bean get() { return null; } }\n");
        DocPack pack = new JaxrsSourceScanner(
                List.of(dir), List.of(dir), List.of("com.example.api"),
                "api.status", "test", Map.of()).scan();
        PropertyDoc name = pack.getSchemas().get("com.example.api.Bean").getProperties().get("name");
        assertEquals("Field description.", name.getDescription());
        assertEquals("from-field", name.getExample());
    }

    @Test
    void getterJavadocFillsWhenFieldHasNone() throws Exception {
        Path dir = Files.createTempDirectory("openapi-enrich-getter-fill");
        Files.writeString(dir.resolve("Bean.java"), ""
                + "package com.example.api;\n"
                + "public class Bean {\n"
                + "  private String name;\n"
                + "  /** From getter only.\n"
                + "   * @api.example getter-ex\n"
                + "   */\n"
                + "  public String getName() { return name; }\n"
                + "}\n");
        Files.writeString(dir.resolve("Res.java"), ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.GET;\n"
                + "import javax.ws.rs.Path;\n"
                + "@Path(\"/b\")\n"
                + "public class Res { @GET public Bean get() { return null; } }\n");
        DocPack pack = new JaxrsSourceScanner(
                List.of(dir), List.of(dir), List.of("com.example.api"),
                "api.status", "test", Map.of()).scan();
        PropertyDoc name = pack.getSchemas().get("com.example.api.Bean").getProperties().get("name");
        assertEquals("From getter only.", name.getDescription());
        assertEquals("getter-ex", name.getExample());
    }

    @Test
    void httpMethodOverloadsWithSameJavaNameAreBothIndexed() throws Exception {
        Path dir = Files.createTempDirectory("openapi-enrich-overload");
        Files.writeString(dir.resolve("Rules.java"), ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.GET;\n"
                + "import javax.ws.rs.POST;\n"
                + "import javax.ws.rs.Path;\n"
                + "@Path(\"/rules\")\n"
                + "public class Rules {\n"
                + "  @GET public String getRuleMetadatas() { return \"\"; }\n"
                + "  @POST @Path(\"/query\") public String getRuleMetadatas(String query) { return query; }\n"
                + "}\n");
        DocPack pack = new JaxrsSourceScanner(
                List.of(dir), List.of(dir), List.of("com.example.api"),
                "api.status", "test", Map.of()).scan();
        assertTrue(pack.getOperations().stream().anyMatch(o -> "GET /rules".equals(o.getOperationKey())));
        assertTrue(pack.getOperations().stream().anyMatch(o -> "POST /rules/query".equals(o.getOperationKey())),
                "overload with same Java method name must not be dropped");
    }


    @Test
    void xmlTransientOnFieldIsSuppressed() throws Exception {
        Path dir = Files.createTempDirectory("openapi-enrich-xt-field");
        Files.writeString(dir.resolve("Bean.java"), ""
                + "package com.example.api;\n"
                + "import javax.xml.bind.annotation.XmlTransient;\n"
                + "public class Bean {\n"
                + "  private String id;\n"
                + "  @XmlTransient\n"
                + "  private Object resolved;\n"
                + "  public String getId() { return id; }\n"
                + "  public Object getResolved() { return resolved; }\n"
                + "}\n");
        Files.writeString(dir.resolve("Res.java"), ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.GET;\n"
                + "import javax.ws.rs.Path;\n"
                + "@Path(\"/b\")\n"
                + "public class Res { @GET public Bean get() { return null; } }\n");
        DocPack pack = new JaxrsSourceScanner(
                List.of(dir), List.of(dir), List.of("com.example.api"),
                "api.status", "test", Map.of()).scan();
        SchemaDoc schema = pack.getSchemas().get("com.example.api.Bean");
        assertTrue(schema.getProperties().containsKey("id"));
        assertFalse(schema.getProperties().containsKey("resolved"));
        assertTrue(schema.getSuppressedProperties().contains("resolved"));
    }

    @Test
    void jsonIgnoreOnGetterIsSuppressed() throws Exception {
        Path dir = Files.createTempDirectory("openapi-enrich-ji-getter");
        Files.writeString(dir.resolve("Bean.java"), ""
                + "package com.example.api;\n"
                + "import com.fasterxml.jackson.annotation.JsonIgnore;\n"
                + "public class Bean {\n"
                + "  private String id;\n"
                + "  private String secret;\n"
                + "  public String getId() { return id; }\n"
                + "  @JsonIgnore\n"
                + "  public String getSecret() { return secret; }\n"
                + "}\n");
        Files.writeString(dir.resolve("Res.java"), ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.GET;\n"
                + "import javax.ws.rs.Path;\n"
                + "@Path(\"/b\")\n"
                + "public class Res { @GET public Bean get() { return null; } }\n");
        DocPack pack = new JaxrsSourceScanner(
                List.of(dir), List.of(dir), List.of("com.example.api"),
                "api.status", "test", Map.of()).scan();
        SchemaDoc schema = pack.getSchemas().get("com.example.api.Bean");
        assertTrue(schema.getProperties().containsKey("id"));
        assertFalse(schema.getProperties().containsKey("secret"));
        assertTrue(schema.getSuppressedProperties().contains("secret"));
    }

    @Test
    void xmlTransientOnSetterSuppressesProperty() throws Exception {
        Path dir = Files.createTempDirectory("openapi-enrich-xt-setter");
        Files.writeString(dir.resolve("Bean.java"), ""
                + "package com.example.api;\n"
                + "import javax.xml.bind.annotation.XmlTransient;\n"
                + "public class Bean {\n"
                + "  private String id;\n"
                + "  private Object internal;\n"
                + "  public String getId() { return id; }\n"
                + "  public Object getInternal() { return internal; }\n"
                + "  @XmlTransient\n"
                + "  public void setInternal(Object internal) { this.internal = internal; }\n"
                + "}\n");
        Files.writeString(dir.resolve("Res.java"), ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.GET;\n"
                + "import javax.ws.rs.Path;\n"
                + "@Path(\"/b\")\n"
                + "public class Res { @GET public Bean get() { return null; } }\n");
        DocPack pack = new JaxrsSourceScanner(
                List.of(dir), List.of(dir), List.of("com.example.api"),
                "api.status", "test", Map.of()).scan();
        SchemaDoc schema = pack.getSchemas().get("com.example.api.Bean");
        assertTrue(schema.getProperties().containsKey("id"));
        assertFalse(schema.getProperties().containsKey("internal"));
        assertTrue(schema.getSuppressedProperties().contains("internal"));
    }

    @Test
    void propertyTypeStyleResolvedValueTypeIsSuppressedAndAliased() throws Exception {
        Path dir = Files.createTempDirectory("openapi-enrich-value-type");
        Files.writeString(dir.resolve("PropertyType.java"), ""
                + "package com.example.api;\n"
                + "import javax.xml.bind.annotation.XmlElement;\n"
                + "import javax.xml.bind.annotation.XmlTransient;\n"
                + "public class PropertyType {\n"
                + "  private Object valueType;\n"
                + "  private String valueTypeId;\n"
                + "  @XmlTransient\n"
                + "  public Object getValueType() { return valueType; }\n"
                + "  /** Value type id on the wire. */\n"
                + "  @XmlElement(name = \"type\")\n"
                + "  public String getValueTypeId() { return valueTypeId; }\n"
                + "}\n");
        Files.writeString(dir.resolve("Res.java"), ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.POST;\n"
                + "import javax.ws.rs.Path;\n"
                + "@Path(\"/properties\")\n"
                + "public class Res { @POST public boolean set(PropertyType p) { return true; } }\n");
        DocPack pack = new JaxrsSourceScanner(
                List.of(dir), List.of(dir), List.of("com.example.api"),
                "api.status", "test", Map.of()).scan();
        SchemaDoc schema = pack.getSchemas().get("com.example.api.PropertyType");
        assertNotNull(schema.getProperties().get("type"));
        assertEquals("valueTypeId", schema.getProperties().get("type").getJavaName());
        assertFalse(schema.getProperties().containsKey("valueType"));
        assertFalse(schema.getProperties().containsKey("valueTypeId"));
        assertTrue(schema.getSuppressedProperties().contains("valueType"));
    }

    @Test
    void eventStyleRuntimeGraphMembersAreSuppressedWhileIdsRemain() throws Exception {
        Path dir = Files.createTempDirectory("openapi-enrich-event-graph");
        Files.writeString(dir.resolve("Event.java"), ""
                + "package com.example.api;\n"
                + "import javax.xml.bind.annotation.XmlTransient;\n"
                + "public class Event {\n"
                + "  private String eventType;\n"
                + "  private String profileId;\n"
                + "  private Object profile;\n"
                + "  private Object session;\n"
                + "  public String getEventType() { return eventType; }\n"
                + "  public String getProfileId() { return profileId; }\n"
                + "  @XmlTransient\n"
                + "  public Object getProfile() { return profile; }\n"
                + "  @XmlTransient\n"
                + "  public Object getSession() { return session; }\n"
                + "}\n");
        Files.writeString(dir.resolve("Res.java"), ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.POST;\n"
                + "import javax.ws.rs.Path;\n"
                + "@Path(\"/events\")\n"
                + "public class Res { @POST public Event post(Event e) { return e; } }\n");
        DocPack pack = new JaxrsSourceScanner(
                List.of(dir), List.of(dir), List.of("com.example.api"),
                "api.status", "test", Map.of()).scan();
        SchemaDoc schema = pack.getSchemas().get("com.example.api.Event");
        assertTrue(schema.getProperties().containsKey("eventType"));
        assertTrue(schema.getProperties().containsKey("profileId"));
        assertFalse(schema.getProperties().containsKey("profile"));
        assertFalse(schema.getProperties().containsKey("session"));
        assertTrue(schema.getSuppressedProperties().contains("profile"));
        assertTrue(schema.getSuppressedProperties().contains("session"));
    }

    @Test
    void xmlAttributeNameAliasIsHonored() throws Exception {
        Path dir = Files.createTempDirectory("openapi-enrich-xml-attr");
        Files.writeString(dir.resolve("Bean.java"), ""
                + "package com.example.api;\n"
                + "import javax.xml.bind.annotation.XmlAttribute;\n"
                + "public class Bean {\n"
                + "  private String itemId;\n"
                + "  @XmlAttribute(name = \"id\")\n"
                + "  public String getItemId() { return itemId; }\n"
                + "}\n");
        Files.writeString(dir.resolve("Res.java"), ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.GET;\n"
                + "import javax.ws.rs.Path;\n"
                + "@Path(\"/b\")\n"
                + "public class Res { @GET public Bean get() { return null; } }\n");
        DocPack pack = new JaxrsSourceScanner(
                List.of(dir), List.of(dir), List.of("com.example.api"),
                "api.status", "test", Map.of()).scan();
        SchemaDoc schema = pack.getSchemas().get("com.example.api.Bean");
        assertNotNull(schema.getProperties().get("id"));
        assertEquals("itemId", schema.getProperties().get("id").getJavaName());
        assertFalse(schema.getProperties().containsKey("itemId"));
    }


}
