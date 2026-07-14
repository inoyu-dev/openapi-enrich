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

**openapi-enrich** fixes that with a small build-time + runtime pipeline that any CXF JAX-RS project can use. It is **not** Unomi-specific — Unomi uses it, but nothing here requires Unomi.

---

## What you get

| Piece | When it runs | What it does |
|-------|----------------|--------------|
| **Maven plugin** (`openapi-enrich-maven-plugin`) | Build (`generate-resources`) | Scans your Java sources, reads Javadoc + a couple of custom tags, writes a JSON **enrichment pack** into your JAR |
| **CXF enricher** (`cxf-openapi-enricher`) | Runtime | Loads that pack from the classpath and merges it into CXF’s generated OpenAPI document |
| **Model** (`openapi-enrich-model`) | Both | Shared POJOs for the pack format |

```
  Your sources + Javadoc
           │
           ▼
  openapi-enrich:generate   ──writes──▶  META-INF/openapi-enrich/openapi-doc.json
           │                                      │
           │                              (packaged in your JAR)
           │                                      │
           ▼                                      ▼
  CXF OpenApiFeature  +  EnrichingOpenApiCustomizer  ──▶  richer /openapi.json
```

---

## Quick start tutorial

This walkthrough assumes a Maven module that already exposes JAX-RS resources through CXF’s `OpenApiFeature`. Follow the steps in order.

### 0. Prerequisites

- JDK 11 or newer
- Maven 3.8+
- A project using JAX-RS (`javax.ws.rs` or `jakarta.ws.rs`) and Apache CXF OpenAPI support

Until this library is on Maven Central, install it locally once from this repository:

```bash
git clone https://github.com/inoyu-dev/openapi-enrich.git
cd openapi-enrich
mvn clean install
```

That installs the `0.1.0-SNAPSHOT` artifacts into your local `~/.m2` repository.

### 1. Document a resource with Javadoc

You mostly use normal Javadoc. Two optional tags give the enricher status codes and examples:

```java
package com.example.api;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

@Path("/tenants")
@Produces(MediaType.APPLICATION_JSON)
public class TenantResource {

    /**
     * Get a tenant by id.
     *
     * <p>Returns the tenant if it exists.</p>
     *
     * @param tenantId tenant identifier
     * @return the matching tenant
     * @api.status 200 com.example.api.Tenant Success
     * @api.status 404 empty Tenant not found
     */
    @GET
    @Path("/{tenantId}")
    public Tenant getTenant(@PathParam("tenantId") String tenantId) {
        // ...
        return null;
    }
}
```

Document model fields the same way:

```java
package com.example.api;

public class Tenant {

    /**
     * Stable tenant identifier.
     * @api.example acme-corp
     */
    private String id;

    /**
     * Display name shown in the UI.
     * @api.example Acme Corporation
     */
    private String name;

    // getters / setters ...
}
```

**Tag cheat-sheet**

| Tag | Example | Meaning |
|-----|---------|---------|
| (first sentence) | `Get a tenant by id.` | OpenAPI **summary** |
| rest of Javadoc | `<p>Returns…</p>` | OpenAPI **description** |
| `@param` | `@param tenantId …` | Parameter description (name is remapped to `@PathParam` / `@QueryParam` wire name) |
| `@return` | `@return the matching tenant` | Description on an existing 2xx response |
| `@api.status` | `@api.status 404 empty Not found` | Named HTTP response (+ optional schema) |
| `@api.example` | `@api.example {"id":"acme"}` | Example for a response body or a property |

Status line grammar:

```text
@api.status <httpCode> [array] <TypeFqcn|empty|{@link Type}> [optional description]
```

Examples:

```text
@api.status 200 com.example.api.Tenant
@api.status 200 array com.example.api.Health All live
@api.status 204 empty No content
@api.status 404 empty
```

### 2. Add the Maven plugin to your API module

In the `pom.xml` of the module that **compiles** your JAX-RS resources:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>dev.inoyu.openapi</groupId>
      <artifactId>openapi-enrich-maven-plugin</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <executions>
        <execution>
          <goals>
            <goal>generate</goal>
          </goals>
        </execution>
      </executions>
      <configuration>
        <!-- Optional: limit scanning to your API packages -->
        <includePackages>
          <includePackage>com.example.api</includePackage>
        </includePackages>
      </configuration>
    </plugin>
  </plugins>
</build>
```

Build the module:

```bash
mvn clean package
```

You should see a log line like:

```text
Wrote OpenAPI doc pack (N operations, M schemas, …) to
  target/generated-resources/openapi-enrich/META-INF/openapi-enrich/openapi-doc.json
```

Confirm the file exists and is packaged inside the JAR:

```bash
jar tf target/your-module-*.jar | grep openapi-doc.json
# → META-INF/openapi-enrich/openapi-doc.json
```

The plugin registers that directory as a Maven resource root automatically, so OSGi/JAR packaging picks it up with no extra config.

### 3. Add the runtime enricher dependency

Wherever you create CXF’s `OpenApiFeature` (same module or a runtime module that depends on the API JAR):

```xml
<dependency>
  <groupId>dev.inoyu.openapi</groupId>
  <artifactId>cxf-openapi-enricher</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

`openapi-enrich-model` is pulled in transitively.

### 4. Wire CXF `OpenApiFeature`

**Classpath / plain Java** (most apps):

```java
import org.apache.cxf.jaxrs.openapi.OpenApiFeature;
import dev.inoyu.openapi.enrich.cxf.EnrichingOpenApiCustomizer;

OpenApiFeature openApi = new OpenApiFeature();
openApi.setPrettyPrint(true);
// Loads every META-INF/openapi-enrich/openapi-doc.json found on the classpath and merges them
openApi.setCustomizer(new EnrichingOpenApiCustomizer());
```

Register `openApi` with your CXF JAX-RS server the same way you already do for Swagger/OpenAPI.

**OSGi / multi-bundle** (e.g. Karaf): prefer loading packs from active bundles so each feature can contribute its own docs:

```java
import org.osgi.framework.BundleContext;
import dev.inoyu.openapi.enrich.cxf.BundleContextDocPackLoader;
import dev.inoyu.openapi.enrich.cxf.EnrichingOpenApiCustomizer;
import dev.inoyu.openapi.enrich.model.DocPack;

DocPack pack = new BundleContextDocPackLoader().loadMerged(bundleContext);
EnrichingOpenApiCustomizer customizer = new EnrichingOpenApiCustomizer(pack);
customizer.setDynamicBasePath(true); // optional, CXF helper for reverse proxies
openApi.setCustomizer(customizer);
```

If `BundleContext` is null or no bundle packs are found, the loader falls back to the classpath.

### 5. Verify

1. Start your application.
2. Open CXF’s OpenAPI endpoint (often `/openapi.json` or Swagger UI).
3. Check that:
   - `GET /tenants/{tenantId}` has a **summary** / **description** from Javadoc
   - Parameter `tenantId` has its `@param` text
   - Responses include **200** (with `Tenant` schema) and **404**
   - `Tenant` properties show field descriptions / examples when CXF already emits those schemas

If something is missing, jump to [Troubleshooting](#troubleshooting).

---

## Modules (reference)

| Artifact | Role |
|----------|------|
| `openapi-enrich-model` | Pack POJOs (`DocPack`, `OperationDoc`, `SchemaDoc`, …) and path constant `META-INF/openapi-enrich/openapi-doc.json` |
| `openapi-enrich-maven-plugin` | Mojo `openapi-enrich:generate` — scan sources → write pack |
| `cxf-openapi-enricher` | `EnrichingOpenApiCustomizer` + classpath / OSGi loaders |

Pack contract version: **`1.1`** (loaders still accept older `1.0` packs).

---

## Plugin configuration reference

| Parameter | Default | Purpose |
|-----------|---------|---------|
| `sourceRoots` | project compile source roots | Where to look for `@Path` resources |
| `includePackages` | *(all)* | Optional package prefixes to scan |
| `statusTag` | `api.status` | Javadoc tag name for status lines (`@` optional) |
| `generatedResourceDirectory` | `${project.build.directory}/generated-resources/openapi-enrich` | Registered as a Maven resource root |
| `outputFile` | `…/META-INF/openapi-enrich/openapi-doc.json` | Pack output path |
| `bundleId` | `${project.artifactId}` | Identifier written into the pack |
| `extraSourceRoots` | *(none)* | Extra dirs for DTOs / abstract JAX-RS bases living outside this module |
| `servletPaths` | *(none)* | Explicit `className` + `path` for `HttpServlet` endpoints |

Example with extras and a servlet:

```xml
<configuration>
  <includePackages>
    <includePackage>com.example.api</includePackage>
  </includePackages>
  <extraSourceRoots>
    <!-- Shared DTOs / abstract resource bases in another module -->
    <extraSourceRoot>${project.basedir}/../api-model/src/main/java</extraSourceRoot>
  </extraSourceRoots>
  <servletPaths>
    <servletPath>
      <className>com.example.HealthServlet</className>
      <path>/health/check</path>
    </servletPath>
  </servletPaths>
</configuration>
```

You can also run the goal manually:

```bash
mvn openapi-enrich:generate
```

> **Note:** The example tag name `@api.example` is fixed in code (not configurable). Only `@api.status` can be renamed via `statusTag`.

---

## How enrichment works (short)

1. **Build** — the Mojo finds classes with `@Path`, collects HTTP methods (including inherited methods from abstract bases, with type-variable binding), and reads Javadoc / `@api.*` tags. Referenced model types become schema entries (each type stores **its own** properties plus a `parentFqcn` link for inheritance).
2. **Package** — the JSON pack lands at `META-INF/openapi-enrich/openapi-doc.json` inside your JAR/bundle.
3. **Runtime** — `EnrichingOpenApiCustomizer` merges packs into the OpenAPI model: operation summaries, parameter docs, named status responses, request/response schemas, property descriptions/`$ref`s, and optional absolute servlet paths.

Property wire names honor `@JsonProperty` / `@XmlElement(name)` / `@XmlAttribute(name)` on getters, then setters, then fields. Field Javadoc wins for description/`@api.example` when both field and accessor have docs. Properties marked `@XmlTransient`, `@JsonIgnore`, or `transient` are recorded so the enricher can drop them if CXF still emits them.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| No pack file after build | Plugin not bound / wrong module | Add the plugin to the module that **compiles** the `@Path` classes; run `mvn clean package` |
| Pack exists but OpenAPI unchanged | Enricher not registered, or API JAR not on classpath | Call `setCustomizer(new EnrichingOpenApiCustomizer())`; ensure the JAR containing the pack is a runtime dependency |
| Missing base-class methods / DTO docs | Sources outside compile roots | Add them with `extraSourceRoots` |
| Status tags ignored | Typo or wrong tag name | Use `@api.status …` (or match your `statusTag`); rebuild |
| Stock `javadoc` goal fails on `@api.*` | Custom tags unknown to JDK javadoc | Register `api.status` and `api.example` as custom tags in the maven-javadoc-plugin (or skip javadoc for that module) |
| Two types collide in Swagger | OpenAPI component keys use **simple** class names | Avoid same simple name in different packages, or rename |

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
