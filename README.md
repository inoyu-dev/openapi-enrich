# openapi-enrich

Turn the Javadoc you already write on JAX-RS resources into richer OpenAPI docs — without duplicating everything in Swagger annotations.

**Coordinates:** `dev.inoyu.openapi:*:0.1.0-SNAPSHOT` · **License:** Apache-2.0 · **Requires:** Java 11+, Apache CXF (for the runtime enricher)

---

## Who is this for?

You have (or are building) a JAX-RS API served by **Apache CXF**, and CXF’s auto-generated OpenAPI / Swagger UI looks thin:

- Missing method summaries and parameter descriptions from Javadoc
- Only a generic `200` response instead of documented `404` / `204` / etc.
- Model fields without descriptions or examples
- No easy way to keep docs in sync with the source of truth (your Java code)

**openapi-enrich** fixes that with a small build-time + runtime pipeline that any CXF JAX-RS project can use.

**Recommended multi-module layout:** register `api.status` / `api.example` on the parent `maven-javadoc-plugin`; bind `openapi-enrich:generate` once in parent `pluginManagement`; each REST (or servlet) module that should contribute docs re-declares the plugin with `extraSourceRoots` / `servletPaths` as needed; at runtime merge packs (classpath or `BundleContextDocPackLoader` on OSGi) and attach `EnrichingOpenApiCustomizer` to CXF `OpenApiFeature`.

This layout is **validated in production by [Apache Unomi](https://unomi.apache.org/)**; the tutorial below stays generic so you can copy it into any project.

---

## What you get

| Piece | When it runs | What it does |
|-------|----------------|--------------|
| **Maven plugin** (`openapi-enrich-maven-plugin`) | Build (`generate-resources`) | Scans your Java sources, reads Javadoc + `@api.*` tags, writes a JSON **enrichment pack** into your JAR |
| **CXF enricher** (`cxf-openapi-enricher`) | Runtime | Loads packs (classpath or OSGi bundles) and merges them into CXF’s generated OpenAPI document |
| **Model** (`openapi-enrich-model`) | Both | Shared POJOs for the pack format |

![Build-time pack generation and runtime CXF enrichment pipeline](docs/images/pipeline.svg)

Each module that runs the Mojo gets its own `META-INF/openapi-enrich/openapi-doc.json` inside its JAR. At runtime the loader **merges** all packs it finds (for example one pack per feature JAR/bundle).

---

## Before / after

Same JAX-RS method, same CXF generator — only openapi-enrich is added. Teal lines in the diagram are fields merged from Javadoc / `@api.*` tags.

![Side-by-side OpenAPI excerpt before and after enrichment](docs/images/before-after.svg)

### Before (CXF alone)

```yaml
paths:
  /tenants/{tenantId}:
    get:
      operationId: getTenant
      parameters:
        - name: tenantId
          in: path
          required: true
          schema:
            type: string
      responses:
        "200":
          description: default response
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Tenant'
components:
  schemas:
    Tenant:
      type: object
      properties:
        id:
          type: string
        name:
          type: string
```

### After (CXF + EnrichingOpenApiCustomizer)

```yaml
paths:
  /tenants/{tenantId}:
    get:
      summary: Get a tenant by id.
      description: Returns the tenant if it exists.
      operationId: getTenant
      parameters:
        - name: tenantId
          in: path
          required: true
          description: tenant identifier
          schema:
            type: string
      responses:
        "200":
          description: Tenant found.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Tenant'
              example:
                id: acme
                name: Acme Corp
        "404":
          description: Tenant not found.
components:
  schemas:
    Tenant:
      type: object
      properties:
        id:
          type: string
          description: Stable tenant identifier.
          example: acme
        name:
          type: string
          description: Display name shown in the UI.
          example: Acme Corp
```

That shape matches the tutorial below (summary, `@param`, every realistic `@api.status`, JSON `@api.example` on the method, field docs on the model).

---

## Quick start tutorial

Follow these steps in order. All samples use generic `com.example.*` packages.

### 0. Prerequisites

- JDK 11 or newer
- Maven 3.8+
- JAX-RS (`javax.ws.rs` or `jakarta.ws.rs`) + Apache CXF `OpenApiFeature`

Until this library is on Maven Central, install it locally once:

```bash
git clone https://github.com/inoyu-dev/openapi-enrich.git
cd openapi-enrich
mvn clean install
```

Pin the version in your reactor, for example `${openapi.enrich.version}` → `0.1.0-SNAPSHOT`.

### 1. Document resources thoroughly

For the endpoints you care about, aim for:

- Method **summary** (first Javadoc sentence) + short description
- `@param` for public parameters (Java names; the scanner remaps to `@PathParam` / `@QueryParam` wire names)
- `@api.status` for every realistic 2xx / 4xx / 5xx
- `@api.example` on important success responses (JSON literals)
- Field Javadoc (+ `@api.example` where helpful) on request/response models

```java
package com.example.rest;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.example.api.Tenant;

@Path("/tenants")
public class TenantResource {

    /**
     * Get a tenant by id.
     *
     * @param tenantId tenant identifier
     * @return the tenant if found, otherwise 404
     * @api.status 200 com.example.api.Tenant Tenant found.
     * @api.status 404 empty Tenant not found.
     * @api.example {"id":"acme","name":"Acme Corp"}
     */
    @GET
    @Path("/{tenantId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTenant(@PathParam("tenantId") String tenantId) {
        Tenant tenant = /* lookup */ null;
        if (tenant == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(tenant).build();
    }
}
```

If models live in another module (for example `../api`), document fields there and point the Mojo at those sources with `extraSourceRoots` (step 3):

```java
package com.example.api;

public class Tenant {

    /**
     * Stable tenant identifier.
     * @api.example acme
     */
    private String id;

    /**
     * Display name shown in the UI.
     * @api.example Acme Corp
     */
    private String name;

    // getters / setters ...
}
```

**Tag cheat-sheet**

| Tag | Example | Meaning |
|-----|---------|---------|
| (first sentence) | `Get a tenant by id.` | OpenAPI **summary** |
| rest of Javadoc / `@return` | … | **description** (and `@return` can label an existing 2xx) |
| `@param` | `@param tenantId tenant identifier` | Parameter description |
| `@api.status` | `@api.status 404 empty Tenant not found.` | Named HTTP response (+ optional schema) |
| `@api.example` | `@api.example {"id":"acme","name":"Acme Corp"}` | Response body or property example |

Status line grammar:

```text
@api.status <httpCode> [array] <TypeFqcn|empty|{@link Type}> [optional description]
```

Examples:

```text
@api.status 200 com.example.api.Tenant Tenant found.
@api.status 200 array com.example.api.HealthStatus All checks OK.
@api.status 204 empty Tenant deleted.
@api.status 404 empty Tenant not found.
```

### 2. Register custom Javadoc tags (required)

`@api.status` and `@api.example` are **not** standard Javadoc tags. Without registering them, `mvn javadoc:javadoc` (and release javadoc JARs) fail with *unknown tag: api.status*.

Put this on the **parent** `maven-javadoc-plugin` (reactor-wide is simplest):

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-javadoc-plugin</artifactId>
  <configuration>
    <tags>
      <tag>
        <name>api.status</name>
        <placement>a</placement>
        <head>API Status:</head>
      </tag>
      <tag>
        <name>api.example</name>
        <placement>a</placement>
        <head>API Example:</head>
      </tag>
    </tags>
  </configuration>
</plugin>
```

- `placement` `a` = allowed on types, methods, and fields (matches how enrichment uses the tags).
- If you rename the status tag via the enrich plugin’s `statusTag`, register that same name here.
- `@api.example` is fixed in the enricher; always register `api.example`.

### 3. Bind the Maven plugin

**Parent `pluginManagement`** — version + execution once (do **not** put the plugin in the parent’s top-level `<plugins>` unless every module should generate a pack):

```xml
<pluginManagement>
  <plugins>
    <plugin>
      <groupId>dev.inoyu.openapi</groupId>
      <artifactId>openapi-enrich-maven-plugin</artifactId>
      <version>${openapi.enrich.version}</version>
      <executions>
        <execution>
          <id>generate-openapi-doc</id>
          <goals>
            <goal>generate</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</pluginManagement>
```

**REST module** that compiles `@Path` resources — re-declare the plugin **only** to add configuration. Inherit the execution from `pluginManagement` (do not restate `<executions>`). When DTOs live in another module, add `extraSourceRoots`:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>dev.inoyu.openapi</groupId>
      <artifactId>openapi-enrich-maven-plugin</artifactId>
      <configuration>
        <extraSourceRoots>
          <extraSourceRoot>${project.basedir}/../api/src/main/java</extraSourceRoot>
        </extraSourceRoots>
      </configuration>
    </plugin>
  </plugins>
</build>
```

List the plugin under `<plugins>` in **every** module that should contribute a pack (core REST, feature REST extensions, etc.). Modules that do not list it do not generate one.

Build:

```bash
mvn clean package
```

You should see:

```text
Wrote OpenAPI doc pack (N operations, M schemas, …) to
  target/generated-resources/openapi-enrich/META-INF/openapi-enrich/openapi-doc.json
```

```bash
jar tf target/your-rest-module-*.jar | grep openapi-doc.json
# → META-INF/openapi-enrich/openapi-doc.json
```

The Mojo registers the generated directory as a Maven resource root so JAR/OSGi packaging includes the pack automatically.

### 4. Add the runtime enricher dependency

On the module that creates CXF’s `OpenApiFeature`:

```xml
<dependency>
  <groupId>dev.inoyu.openapi</groupId>
  <artifactId>cxf-openapi-enricher</artifactId>
  <version>${openapi.enrich.version}</version>
</dependency>
<!-- Direct model dep helps if you embed JARs into an OSGi bundle. -->
<dependency>
  <groupId>dev.inoyu.openapi</groupId>
  <artifactId>openapi-enrich-model</artifactId>
  <version>${openapi.enrich.version}</version>
</dependency>
```

### 5. Wire CXF `OpenApiFeature`

#### Multi-module / OSGi (recommended when several JARs contribute packs)

Each producing module ships its own pack. Merge them from the `BundleContext`, then attach the customizer:

```java
import org.apache.cxf.jaxrs.openapi.OpenApiFeature;

import dev.inoyu.openapi.enrich.cxf.BundleContextDocPackLoader;
import dev.inoyu.openapi.enrich.cxf.EnrichingOpenApiCustomizer;
import dev.inoyu.openapi.enrich.model.DocPack;

OpenApiFeature openApiFeature = new OpenApiFeature();
openApiFeature.setScan(false);
openApiFeature.setUseContextBasedConfig(true);
// ... contact / license / Swagger UI config as you prefer ...

DocPack openApiDocPack = new BundleContextDocPackLoader().loadMerged(bundleContext);
EnrichingOpenApiCustomizer customizer = new EnrichingOpenApiCustomizer(openApiDocPack);
customizer.setDynamicBasePath(true);
openApiFeature.setCustomizer(customizer);

jaxrsServerFactoryBean.getFeatures().add(openApiFeature);
```

If `BundleContext` is null or no bundle packs are found, the loader falls back to the classpath.

#### Single JAR / plain classpath

```java
OpenApiFeature openApi = new OpenApiFeature();
openApi.setCustomizer(new EnrichingOpenApiCustomizer()); // ClasspathDocPackLoader merge
```

### 6. Optional: document an `HttpServlet`

Servlets are not JAX-RS `@Path` resources. Map them explicitly with `servletPaths`:

```xml
<plugin>
  <groupId>dev.inoyu.openapi</groupId>
  <artifactId>openapi-enrich-maven-plugin</artifactId>
  <configuration>
    <servletPaths>
      <servletPath>
        <className>com.example.health.HealthCheckServlet</className>
        <path>/health/check</path>
      </servletPath>
    </servletPaths>
  </configuration>
</plugin>
```

Put statuses / examples on the **servlet class**:

```java
/**
 * Aggregate health endpoint.
 *
 * @api.status 200 array com.example.health.HealthStatus All checks OK.
 * @api.status 206 array com.example.health.HealthStatus At least one check is degraded.
 * @api.status 403 empty Missing the required role.
 * @api.example [{"name":"database","status":"UP","latencyMs":12}]
 */
public class HealthCheckServlet extends HttpServlet { /* ... */ }
```

### 7. Verify

1. Confirm each documenting module wrote `target/generated-resources/openapi-enrich/META-INF/openapi-enrich/openapi-doc.json`.
2. Start the app and open CXF’s OpenAPI document (often `/openapi.json` or Swagger UI).
3. Check that `GET /tenants/{tenantId}` has summary, `tenantId` description, `200` + `404`, response example, and model field docs/examples.

If something is missing, see [Troubleshooting](#troubleshooting).

---

## Validated in production

[Apache Unomi](https://unomi.apache.org/) uses this toolkit end-to-end: parent javadoc tags + `pluginManagement`, REST modules with `extraSourceRoots`, extension modules and servlets with their own packs, and runtime merge via `BundleContextDocPackLoader` + `EnrichingOpenApiCustomizer`. Treat Unomi as a reference deployment — copy the generic patterns above into your own reactor.

---

## Modules (reference)

| Artifact | Role |
|----------|------|
| `openapi-enrich-model` | Pack POJOs (`DocPack`, `OperationDoc`, `SchemaDoc`, …) and path constant `META-INF/openapi-enrich/openapi-doc.json` |
| `openapi-enrich-maven-plugin` | Mojo `openapi-enrich:generate` — scan sources → write pack |
| `cxf-openapi-enricher` | `EnrichingOpenApiCustomizer`, `ClasspathDocPackLoader`, `BundleContextDocPackLoader` |

Pack contract version: **`1.1`** (loaders still accept older `1.0` packs).

---

## Plugin configuration reference

| Parameter | Default | Purpose |
|-----------|---------|---------|
| `extraSourceRoots` | *(none)* | Extra dirs for DTOs / abstract bases outside this module’s compile roots |
| `servletPaths` | *(none)* | Explicit `className` + `path` for `HttpServlet` endpoints |
| `sourceRoots` | project compile source roots | Override where `@Path` resources are scanned |
| `bundleId` | `${project.artifactId}` | Identifier written into the pack |
| `statusTag` | `api.status` | Javadoc tag name for status lines (`@` optional) |
| `generatedResourceDirectory` | `${project.build.directory}/generated-resources/openapi-enrich` | Registered as a Maven resource root |
| `outputFile` | `…/META-INF/openapi-enrich/openapi-doc.json` | Pack output path |
| `includePackages` | *(all)* | Optional package prefixes to limit scanning |

```xml
<configuration>
  <extraSourceRoots>
    <extraSourceRoot>${project.basedir}/../api/src/main/java</extraSourceRoot>
  </extraSourceRoots>
  <servletPaths>
    <servletPath>
      <className>com.example.health.HealthCheckServlet</className>
      <path>/health/check</path>
    </servletPath>
  </servletPaths>
</configuration>
```

```bash
mvn openapi-enrich:generate
```

> **Note:** `@api.example` is not renameable. Only `@api.status` can be renamed via `statusTag`.

---

## How enrichment works (short)

1. **Build** — the Mojo finds `@Path` classes (and configured servlets), collects HTTP methods (including inherited methods with type-variable binding), and reads Javadoc / `@api.*` tags. Referenced models become schema entries (own properties + `parentFqcn`).
2. **Package** — each module’s pack lands at `META-INF/openapi-enrich/openapi-doc.json` in its JAR/bundle.
3. **Runtime** — loaders merge packs; `EnrichingOpenApiCustomizer` applies summaries, params, named statuses, request/response schemas, property docs/`$ref`s, and servlet paths onto CXF’s OpenAPI model.

Property wire names honor `@JsonProperty` / `@XmlElement(name)` / `@XmlAttribute(name)` on getters, then setters, then fields. Field Javadoc wins for description/`@api.example` when both field and accessor have docs. `@XmlTransient` / `@JsonIgnore` / `transient` properties are recorded so the enricher can drop them if CXF still emits them.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| No pack file after build | Plugin not listed in that module’s `<plugins>` | Re-declare `openapi-enrich-maven-plugin` in the module that compiles the resources (execution comes from `pluginManagement`) |
| Pack exists but OpenAPI unchanged | Customizer not set, or packs not visible to the loader | Use `BundleContextDocPackLoader` in OSGi; ensure JAR/bundles containing packs are active / on the classpath |
| Extension/feature docs missing | That module never runs the Mojo | Add the plugin to **each** REST/extension module that should contribute a pack |
| Missing DTO / base-class docs | Sources outside compile roots | Add `extraSourceRoots` pointing at those source trees |
| Status tags ignored | Typo or wrong tag name | Use `@api.status …` (or match `statusTag`); rebuild |
| Stock `javadoc` fails on `@api.*` | Tags not registered | Parent `maven-javadoc-plugin` `<tags>` as in [step 2](#2-register-custom-javadoc-tags-required) |
| `LinkageError` on OpenAPI types (OSGi) | Embedder pulled in Jackson/CXF/Swagger | Embed only `cxf-openapi-enricher` + `openapi-enrich-model`; Import-Package the shared APIs |
| Two types collide in Swagger | Component keys use **simple** class names | Avoid duplicate simple names across packages |

---

## Known limitations

- OpenAPI component keys use the **simple** class name (`Tenant`, not FQCN) — collisions are possible across packages.
- Schema inheritance follows only the first `extends` superclass (interfaces are not walked).
- The enricher merges documentation onto CXF’s existing components; it does **not** rewrite CXF `allOf` composition.
- Sibling text next to JSON Schema `$ref` can be dropped by some OAS 3 tools/UIs (OpenAPI limitation, not unique to this project).

---

## Developing this repository

```bash
git clone https://github.com/inoyu-dev/openapi-enrich.git
cd openapi-enrich
mvn clean verify
mvn -Plicense-check verify   # Apache RAT (license headers)
```

Publishing to Maven Central: see [`PUBLISHING.md`](PUBLISHING.md).

---

## License

Apache License 2.0 — see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
