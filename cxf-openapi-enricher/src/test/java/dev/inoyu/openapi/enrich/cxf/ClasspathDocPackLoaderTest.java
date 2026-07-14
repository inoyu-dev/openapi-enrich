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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.inoyu.openapi.enrich.model.DocPack;
import dev.inoyu.openapi.enrich.model.OperationDoc;
import dev.inoyu.openapi.enrich.model.SchemaDoc;
import dev.inoyu.openapi.enrich.model.ServletOperationDoc;

class ClasspathDocPackLoaderTest {

    @Test
    void mergeCombinesOperationsSchemasAndBundleIds() {
        DocPack a = new DocPack();
        a.setFormatVersion("1.0");
        a.setBundleId("bundle-a");
        OperationDoc opA = new OperationDoc();
        opA.setOperationKey("GET /a");
        a.getOperations().add(opA);
        SchemaDoc schemaA = new SchemaDoc();
        schemaA.setDescription("A");
        a.getSchemas().put("com.example.A", schemaA);

        DocPack b = new DocPack();
        b.setFormatVersion("1.0");
        b.setBundleId("bundle-b");
        OperationDoc opB = new OperationDoc();
        opB.setOperationKey("GET /b");
        b.getOperations().add(opB);
        SchemaDoc schemaB = new SchemaDoc();
        schemaB.setDescription("B");
        b.getSchemas().put("com.example.B", schemaB);
        ServletOperationDoc servlet = new ServletOperationDoc();
        servlet.setPath("/health");
        servlet.setHttpMethod("GET");
        b.getServletOperations().add(servlet);

        // second pack overwrites same schema key
        SchemaDoc overwrite = new SchemaDoc();
        overwrite.setDescription("A-from-b");
        b.getSchemas().put("com.example.A", overwrite);

        DocPack merged = ClasspathDocPackLoader.merge(List.of(a, b));
        assertEquals(DocPack.CURRENT_FORMAT_VERSION, merged.getFormatVersion());
        assertEquals("bundle-a+bundle-b", merged.getBundleId());
        assertEquals(2, merged.getOperations().size());
        assertEquals(1, merged.getServletOperations().size());
        assertEquals("A-from-b", merged.getSchemas().get("com.example.A").getDescription());
        assertTrue(merged.getSchemas().containsKey("com.example.B"));
    }

    @Test
    void mergeEmptyListYieldsCurrentFormatVersion() {
        DocPack merged = ClasspathDocPackLoader.merge(List.of());
        assertEquals(DocPack.CURRENT_FORMAT_VERSION, merged.getFormatVersion());
        assertTrue(merged.getOperations().isEmpty());
        assertTrue(merged.getSchemas().isEmpty());
    }
}
