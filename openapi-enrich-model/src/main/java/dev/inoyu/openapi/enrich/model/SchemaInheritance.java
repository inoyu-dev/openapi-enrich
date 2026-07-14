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
package dev.inoyu.openapi.enrich.model;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Helpers for {@link SchemaDoc#getParentFqcn()} chains in a {@link DocPack}.
 *
 * Callers: scanner/enricher tests leaf-coverage; EnrichingOpenApiCustomizer parent walks.
 * User instruction: implement clean inheritance + release-ready MVP plan.
 */
public final class SchemaInheritance {

    private SchemaInheritance() {
    }

    /**
     * Effective properties for {@code fqcn}: walk {@code parentFqcn} and merge with child-wins.
     * Used for leaf-coverage checks without flattening packs.
     */
    public static Map<String, PropertyDoc> effectiveProperties(String fqcn, DocPack pack) {
        if (pack == null || pack.getSchemas() == null || fqcn == null) {
            return Map.of();
        }
        Map<String, PropertyDoc> merged = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        String current = fqcn;
        while (current != null && visited.add(current)) {
            SchemaDoc schema = pack.getSchemas().get(current);
            if (schema == null) {
                break;
            }
            if (schema.getProperties() != null) {
                for (Map.Entry<String, PropertyDoc> entry : schema.getProperties().entrySet()) {
                    merged.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
            current = schema.getParentFqcn();
        }
        return Collections.unmodifiableMap(merged);
    }
}
