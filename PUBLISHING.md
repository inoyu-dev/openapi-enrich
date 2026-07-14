# Publishing to Maven Central

This reactor publishes under `dev.inoyu.openapi` (namespace rooted at [inoyu.dev](https://inoyu.dev)).

## Prerequisites (one-time)

1. **Namespace** — Register `dev.inoyu` (or `dev.inoyu.openapi`) in the [Sonatype Central Portal](https://central.sonatype.com/) using verified ownership of `inoyu.dev`.
2. **User token** — Create a portal user token and add to `~/.m2/settings.xml`:

```xml
<servers>
  <server>
    <id>central</id>
    <username><!-- token username --></username>
    <password><!-- token password --></password>
  </server>
</servers>
```

3. **GPG** — Create/import a signing key, publish it to a public keyserver, and configure Maven (`gpg.keyname` / agent).
4. **SCM URL** — Ensure the `scm` block in the root `pom.xml` matches the public Git repository before the first release.

## Local checks

```bash
mvn -q clean verify
mvn -q -Plicense-check verify
```

## Release deploy

```bash
mvn clean deploy -Prelease
```

Attaches sources, javadoc, GPG signatures, and uploads via `central-publishing-maven-plugin`.
Default: validate then **manual publish** in the portal UI. Use `-Dcentral.autoPublish=true` to auto-publish.

## Checklist

- [ ] POM has name, description, url, licenses, developers, scm
- [ ] Every module JAR has -sources.jar and -javadoc.jar
- [ ] GPG `.asc` for all artifacts + POMs
- [ ] LICENSE + NOTICE at repo root and in META-INF of JARs
- [ ] Apache-2.0 headers on sources
- [ ] Namespace claim approved for `dev.inoyu`
