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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.inoyu.openapi.enrich.model.DocPack;
import dev.inoyu.openapi.enrich.model.PropertyDoc;
import dev.inoyu.openapi.enrich.model.SchemaDoc;
import dev.inoyu.openapi.enrich.model.SchemaInheritance;

/**
 * Optional integration smoke against a sibling Unomi tree.
 * Skips when {@code server/unomi} sources are not present (portable CI).
 */
class JaxrsSourceScannerNestedTest {

    private static Path api;
    private static Path rest;
    private static Path tracing;

    @BeforeAll
    static void resolveUnomiSources() {
        Optional<Path> root = findUnomiRoot();
        assumeTrue(root.isPresent(), "sibling unomi sources not found — skipping Unomi IT");
        api = root.get().resolve("api/src/main/java");
        rest = root.get().resolve("rest/src/main/java");
        tracing = root.get().resolve("tracing/tracing-api/src/main/java");
        assumeTrue(Files.isDirectory(api) && Files.isDirectory(rest) && Files.isDirectory(tracing),
                "unomi api/rest/tracing trees incomplete");
    }

    @Test
    void indexesPersonalizationNestedTypesFromUnomiApi() throws Exception {
        DocPack pack = scanContextEndpoints();
        String key = "org.apache.unomi.api.services.PersonalizationService.PersonalizedContent";
        SchemaDoc content = pack.getSchemas().get(key);
        assertNotNull(content, "missing " + key);
        assertTrue(content.getProperties().containsKey("id"));
    }

    @Test
    void coversContextGraphLeavesIncludingTraceNodeAndInheritance() throws Exception {
        DocPack pack = scanContextEndpoints();

        SchemaDoc trace = pack.getSchemas().get("org.apache.unomi.tracing.api.TraceNode");
        assertNotNull(trace, "TraceNode must be extracted from tracing-api");
        assertTrue(trace.getProperties().containsKey("operationType"));
        assertTrue(trace.getProperties().containsKey("children"));

        String personaFqcn = "org.apache.unomi.api.Persona";
        SchemaDoc persona = pack.getSchemas().get(personaFqcn);
        assertNotNull(persona);
        assertEquals("org.apache.unomi.api.Profile", persona.getParentFqcn());
        Map<String, PropertyDoc> effective = SchemaInheritance.effectiveProperties(personaFqcn, pack);
        assertTrue(effective.containsKey("properties"));
        assertTrue(effective.containsKey("itemId"));

        SchemaDoc scope = pack.getSchemas().get("org.apache.unomi.api.Scope");
        assertNotNull(scope);
        assertNotNull(scope.getParentFqcn());
        Map<String, PropertyDoc> scopeEffective = SchemaInheritance.effectiveProperties(
                "org.apache.unomi.api.Scope", pack);
        assertTrue(scopeEffective.containsKey("metadata") || scopeEffective.containsKey("itemId"));

        SchemaDoc event = pack.getSchemas().get("org.apache.unomi.api.Event");
        assertNotNull(event);
        assertFalse(event.getProperties().containsKey("actionPostExecutors"));
        assertFalse(event.getProperties().containsKey("profile"));
        assertFalse(event.getProperties().containsKey("session"));
        assertFalse(pack.getSchemas().containsKey("org.apache.unomi.api.actions.ActionPostExecutor"));

        SchemaDoc condition = pack.getSchemas().get("org.apache.unomi.api.conditions.Condition");
        assertNotNull(condition);
        assertFalse(condition.getProperties().containsKey("conditionType"));
        // @XmlElement(name="type") on getter → wire key "type" (javaName=conditionTypeId)
        assertTrue(condition.getProperties().containsKey("type")
                || condition.getProperties().containsKey("conditionTypeId")
                || condition.getProperties().containsKey("parameterValues"));
        if (condition.getProperties().containsKey("type")) {
            assertEquals("conditionTypeId", condition.getProperties().get("type").getJavaName());
        }
        assertTrue(condition.getSuppressedProperties().contains("conditionType"));

        SchemaDoc action = pack.getSchemas().get("org.apache.unomi.api.actions.Action");
        assertNotNull(action);
        assertFalse(action.getProperties().containsKey("actionType"));
        assertTrue(action.getSuppressedProperties().contains("actionType"));
        if (action.getProperties().containsKey("type")) {
            assertEquals("actionTypeId", action.getProperties().get("type").getJavaName());
        }

        assertTrue(event.getSuppressedProperties().contains("profile"));
        assertTrue(event.getSuppressedProperties().contains("session"));
        assertTrue(event.getSuppressedProperties().contains("actionPostExecutors"));
    }

    private static DocPack scanContextEndpoints() throws Exception {
        JaxrsSourceScanner scanner = new JaxrsSourceScanner(
                List.of(rest), List.of(api, tracing), List.of("org.apache.unomi.rest.endpoints"),
                "api.status", "test", Map.of());
        return scanner.scan();
    }

    /** Walk up from CWD / this class to find {@code unomi/api/src/main/java}. */
    private static Optional<Path> findUnomiRoot() {
        Path start = Path.of("").toAbsolutePath().normalize();
        Path cursor = start;
        for (int i = 0; i < 8; i++) {
            Path candidate = cursor.resolve("unomi");
            if (Files.isDirectory(candidate.resolve("api/src/main/java"))) {
                return Optional.of(candidate);
            }
            candidate = cursor.resolve("server/unomi");
            if (Files.isDirectory(candidate.resolve("api/src/main/java"))) {
                return Optional.of(candidate);
            }
            if (cursor.getParent() == null) {
                break;
            }
            cursor = cursor.getParent();
        }
        return Optional.empty();
    }
}
