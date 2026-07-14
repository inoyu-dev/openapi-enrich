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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class SchemaInheritanceTest {

    @Test
    void effectivePropertiesChildWinsAndWalksParents() {
        DocPack pack = new DocPack();

        SchemaDoc item = new SchemaDoc();
        PropertyDoc itemId = new PropertyDoc();
        itemId.setDescription("from Item");
        item.getProperties().put("itemId", itemId);
        item.getProperties().put("itemType", prop("type"));
        pack.getSchemas().put("com.example.Item", item);

        SchemaDoc profile = new SchemaDoc();
        profile.setParentFqcn("com.example.Item");
        PropertyDoc profileItemId = new PropertyDoc();
        profileItemId.setDescription("from Profile");
        profile.getProperties().put("itemId", profileItemId);
        profile.getProperties().put("properties", prop("map"));
        pack.getSchemas().put("com.example.Profile", profile);

        SchemaDoc persona = new SchemaDoc();
        persona.setParentFqcn("com.example.Profile");
        pack.getSchemas().put("com.example.Persona", persona);

        Map<String, PropertyDoc> effective = SchemaInheritance.effectiveProperties("com.example.Persona", pack);
        assertEquals("from Profile", effective.get("itemId").getDescription());
        assertTrue(effective.containsKey("itemType"));
        assertTrue(effective.containsKey("properties"));
        assertFalse(persona.getProperties().containsKey("itemId"));
    }

    @Test
    void effectivePropertiesHandlesMissingAndCycles() {
        DocPack pack = new DocPack();
        SchemaDoc a = new SchemaDoc();
        a.setParentFqcn("com.example.B");
        a.getProperties().put("a", prop("a"));
        SchemaDoc b = new SchemaDoc();
        b.setParentFqcn("com.example.A");
        b.getProperties().put("b", prop("b"));
        pack.getSchemas().put("com.example.A", a);
        pack.getSchemas().put("com.example.B", b);

        Map<String, PropertyDoc> effective = SchemaInheritance.effectiveProperties("com.example.A", pack);
        assertTrue(effective.containsKey("a"));
        assertTrue(effective.containsKey("b"));
        assertTrue(SchemaInheritance.effectiveProperties("com.example.Missing", pack).isEmpty());
    }

    private static PropertyDoc prop(String description) {
        PropertyDoc p = new PropertyDoc();
        p.setDescription(description);
        return p;
    }
}
