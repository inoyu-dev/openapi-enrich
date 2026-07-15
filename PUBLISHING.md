# Publishing to Maven Central

This reactor publishes under `dev.inoyu.openapi` (namespace rooted at [inoyu.dev](https://inoyu.dev)).

## Prerequisites (one-time)

1. **Namespace** — Register and get **approved** `dev.inoyu` (or `dev.inoyu.openapi`) in the [Sonatype Central Portal](https://central.sonatype.com/) (DNS/`inoyu.dev` ownership verified).
2. **User token** — Portal user token in `~/.m2/settings.xml` with server id **`central`** (must match `publishingServerId`):

```xml
<servers>
  <server>
    <id>central</id>
    <username><!-- token username --></username>
    <password><!-- token password --></password>
  </server>
</servers>
```

3. **GPG** — Signing key on a public keyserver; Maven configured (`gpg.keyname` / agent). Release profile signs with pinentry loopback.
4. **SCM** — Use the pipe form `scm:git|https://github.com/inoyu-dev/openapi-enrich.git` for `connection` / `developerConnection` / release `<connectionUrl>`. The colon form `scm:git:https://...` breaks maven-scm parsing so `release:perform` runs `git clone --branch https://... checkout` and fails with `repository 'checkout' does not exist`.

### Why `deploy` of `*-SNAPSHOT` got HTTP 403

A `0.1.0-SNAPSHOT` deploy goes to  
`https://central.sonatype.com/repository/maven-snapshots/`, **not** the release publish API.

A correct `central` token is not enough: snapshot uploads need the namespace menu **Enable SNAPSHOTs** in the Central Portal. Without that, Maven gets `403 Forbidden` (looks like “bad auth”, usually isn’t).

For Maven Central library releases you normally publish a **non-SNAPSHOT** version instead (see below).

## Local checks

```bash
mvn -q clean verify
mvn -q -Plicense-check verify
```

## Recommended: maven-release-plugin

Configured to activate `-Prelease` (sources, javadoc, GPG) and `deploy` via `central-publishing-maven-plugin`.

**Before `release:prepare`:** working tree must be clean (commit `pom.xml` / docs first), and you must be able to `git push` to `origin` over HTTPS (or whatever `developerConnection` uses).

```bash
# Clean tree, on main, remote push access required
mvn release:prepare
mvn release:perform
```

If prepare finished manually (or `release.properties` was deleted), re-run perform with the tag:

```bash
mvn release:perform -Dtag=v0.1.0
# ensure GPG can prompt: export GPG_TTY=$(tty)
```
What that does:

1. **prepare** — asks for release version (e.g. `0.1.0`) and next SNAPSHOT (`0.1.1-SNAPSHOT`), runs tests, commits, tags `v0.1.0`, pushes tag + POMs.
2. **perform** — checks out the tag and runs `deploy` with `-Prelease` (non-SNAPSHOT → Central **release** validation, not `maven-snapshots`).

Portal default: validate the deployment, then **Publish** manually in the UI (`central.autoPublish=false`). Auto-publish:

```bash
mvn release:perform -Dcentral.autoPublish=true
```

Dry-run release without pushing/tagging:

```bash
mvn release:prepare -DdryRun=true
```

Rollback a failed prepare (before you push, or carefully after):

```bash
mvn release:rollback
```

## Manual release (no release plugin)

```bash
# set <version>0.1.0</version> in all modules, commit, tag
mvn clean deploy -Prelease
```

## Snapshots (optional)

1. Portal → Namespaces → `dev.inoyu…` → **Enable SNAPSHOTs**.
2. Keep `*-SNAPSHOT` and run:

```bash
mvn clean deploy -Prelease
```

Snapshots appear under the Central snapshots repository; they are not the same path as a published release.

## Checklist

- [ ] Namespace claim **approved** for `dev.inoyu`
- [ ] POM has name, description, url, licenses, developers, scm
- [ ] Release version is **not** `*-SNAPSHOT`
- [ ] Every module JAR has `-sources.jar` and `-javadoc.jar`
- [ ] GPG `.asc` for artifacts + POMs
- [ ] LICENSE + NOTICE at repo root and in META-INF of JARs
- [ ] Apache-2.0 headers on sources
- [ ] After publish: bump consumers (e.g. Unomi) off `0.1.0-SNAPSHOT`
