# openapi-enrich

Generic **JAX-RS → OpenAPI enrichment** toolkit (UNOMI-963). Nothing in this project is Unomi-branded: the Maven plugin works for any JAX-RS codebase, and the enricher works with any CXF `OpenApiFeature`.

**Artifact version:** `0.1.0-SNAPSHOT` · **Pack contract:** `formatVersion` **`1.1`** (loaders accept `1.0`).

## Modules

| Module | Role |
|--------|------|
| `openapi-enrich-model` | JSON-friendly POJOs (`DocPack`, `OperationDoc`, …) and classpath constant `META-INF/openapi-enrich/openapi-doc.json` |
| `openapi-enrich-maven-plugin` | Mojo `generate` — scans `@Path` resources (and best-effort servlets) and writes an enrichment pack |
| `cxf-openapi-enricher` | Runtime CXF `OpenApiCustomizer` that merges packs into the generated OpenAPI document |

## Architecture

```
┌──────────────────────────┐     openapi-doc.json      ┌─────────────────────────────┐
│  openapi-enrich:      │  ─────────────────────▶   │  Application (CXF)           │
│  generate (build time)   │   META-INF/.../json       │  OpenApiFeature +            │
│  JavaParser + Javadoc    │                           │  EnrichingOpenApiCustomizer  │
└──────────────────────────┘                           └─────────────────────────────┘
```

1. **Build time** — the Mojo walks compile source roots, finds classes with `@Path` (`javax.ws.rs` or `jakarta.ws.rs`), extracts HTTP methods (including inherited methods from abstract bases with type-variable binding), path joins, Javadoc summary/description/`@param`/`@return`, custom `@api.status` / `@api.example` tags. Model types become schemas with **own** properties only plus `parentFqcn` for inheritance. Servlet subclasses can be mapped via `servletPaths` or best-effort path detection.
2. **Runtime** — `ClasspathDocPackLoader` loads every pack on the classpath and merges them. `EnrichingOpenApiCustomizer` walks `parentFqcn` for property docs, applies property/array `$ref`s, fills named status responses, merges `requestBody` when documented, and adds absolute servlet paths (optional `servers`).

### Pack format `1.1` (freeze)

| Field | Meaning |
|-------|---------|
| `SchemaDoc.parentFqcn` | Concrete Java superclass; properties are **not** flattened into the child |
| `PropertyDoc.schemaClass` | Object property `$ref` target (FQCN) |
| `PropertyDoc.itemsSchemaClass` | Array items `$ref` target (FQCN) |
| `OperationDoc.requestBodySchemaClass` / `requestBodyDescription` | Entity body schema for enricher merge |
| `ResponseDoc.schemaClass` + `array` | Response body; `PartialList<T>` is envelope (`array=false`) with `list.itemsSchemaClass=T` |

Use `SchemaInheritance.effectiveProperties(fqcn, pack)` for leaf-coverage checks (child-wins merge up the parent chain).

### Javadoc tags

| Tag | Where | Purpose |
|-----|--------|---------|
| `@api.status 200 MyType …` | resource method / servlet class | Named HTTP response + optional schema (`array` prefix for JSON arrays; `empty` for no body) |
| `@api.example …` | same, or on a field | Response body or property example (JSON literals preferred for bodies) |

Grammar for status: `@api.status <code> [array] <SchemaFqcn\|empty\|{@link Type}> [description…]`

### Inheritance model

- **Schemas:** own fields only + `parentFqcn`. Enricher applies ancestor property docs onto the OpenAPI component (does **not** rewrite CXF `allOf`). Property keys honor `@JsonProperty` / `@XmlElement(name)` / `@XmlAttribute(name)` on **getters, then setters, then fields** (Unomi-style: aliases usually live on accessors); `PropertyDoc.javaName` retains the bean name so the enricher can rename CXF props to the wire name. Field Javadoc wins over getter/setter for description/`@api.example`; accessors fill blanks. Properties marked `@XmlTransient` / `@JsonIgnore` / `transient` are listed in `SchemaDoc.suppressedProperties` so the enricher can remove them when CXF still emits them (e.g. Condition.conditionType vs wire `type`).
- **JAX-RS resources:** superclass `@GET`/`@POST`/… methods are emitted for the concrete `@Path` class; overrides (same method name or HTTP+path) win; `extends Base<Concrete>` binds type variables for returns/bodies/`@api.status`.

## Install

```bash
cd /Users/loom/projects/inoyu/cdp/server/openapi-enrich
mvn clean install
```

## Use the Maven plugin

```xml
<plugin>
  <groupId>dev.inoyu.openapi</groupId>
  <artifactId>openapi-enrich-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <executions>
    <execution>
      <goals><goal>generate</goal></goals>
    </execution>
  </executions>
  <configuration>
    <includePackages>
      <includePackage>com.example.api</includePackage>
    </includePackages>
    <statusTag>api.status</statusTag>
    <extraSourceRoots>
      <extraSourceRoot>${project.basedir}/../api/src/main/java</extraSourceRoot>
    </extraSourceRoots>
    <servletPaths>
      <servletPath>
        <className>com.example.HealthServlet</className>
        <path>/health/check</path>
      </servletPath>
    </servletPaths>
  </configuration>
</plugin>
```

Default output: `${project.build.directory}/generated-resources/openapi-enrich/META-INF/openapi-enrich/openapi-doc.json`
(registered as a Maven resource root so jar/OSGi packaging includes it).

Put abstract JAX-RS bases and shared DTOs on `extraSourceRoots` when they live outside the module’s compile roots.

## Wire the CXF enricher

```java
OpenApiFeature feature = new OpenApiFeature();
feature.setCustomizer(new EnrichingOpenApiCustomizer()); // loads & merges classpath packs
```

Or pass a pre-built `DocPack` to `new EnrichingOpenApiCustomizer(docPack)`.

## Known limitations

- OpenAPI component keys use the **simple** class name (`Persona`, not FQCN) — collisions possible across packages.
- Only the **first** `extends` superclass is followed (no interface inheritance for schemas).
- `PartialList` envelope item type is recorded per envelope FQCN (last `T` wins if multiple specializations appear in one pack).
- Does not rewrite CXF schema composition (`allOf`); docs/`$ref`s are merged onto existing components.
- Maven Central publish is documented but not required for local `0.1.0-SNAPSHOT` use — see `PUBLISHING.md`.

## License

Apache License 2.0 — see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).

## Publishing

See [`PUBLISHING.md`](PUBLISHING.md) for Maven Central (Central Portal) setup: namespace claim for `dev.inoyu`, GPG, and `mvn clean deploy -Prelease`.
