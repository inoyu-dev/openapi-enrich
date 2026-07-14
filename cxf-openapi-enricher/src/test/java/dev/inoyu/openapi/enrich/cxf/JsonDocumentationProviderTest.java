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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;

import org.junit.jupiter.api.Test;

import dev.inoyu.openapi.enrich.model.DocPack;
import dev.inoyu.openapi.enrich.model.OperationDoc;
import dev.inoyu.openapi.enrich.model.ParamDoc;
import dev.inoyu.openapi.enrich.model.ResponseDoc;

class JsonDocumentationProviderTest {

    @Test
    void indexesByClassMethodAndOperationKey() {
        DocPack pack = new DocPack();
        OperationDoc op = new OperationDoc();
        op.setClassName("com.example.Widgets");
        op.setMethodName("get");
        op.setHttpMethod("GET");
        op.setPath("/widgets/{id}");
        op.setOperationKey("GET /widgets/{id}");
        op.setSummary("Get widget");
        op.setDescription("Longer description");
        ParamDoc id = new ParamDoc();
        id.setDescription("widget id");
        op.getParameters().put("id", id);
        ResponseDoc ok = new ResponseDoc();
        ok.setDescription("found");
        LinkedHashMap<String, ResponseDoc> responses = new LinkedHashMap<>();
        responses.put("200", ok);
        op.setResponses(responses);
        pack.getOperations().add(op);

        JsonDocumentationProvider provider = new JsonDocumentationProvider(pack);
        assertEquals(op, provider.findByClassMethod("com.example.Widgets", "get"));
        assertEquals(op, provider.findByOperationKey("GET /widgets/{id}"));
        assertNull(provider.findByClassMethod("com.example.Widgets", "missing"));
        assertNull(provider.getMethodDoc(null));
        assertNull(provider.getMethodResponseDoc(null));
        assertNull(provider.getMethodParameterDoc(null, 0));
        assertNull(provider.getClassDoc(null));
    }

    @Test
    void nullPackIsSafe() {
        JsonDocumentationProvider provider = new JsonDocumentationProvider(null);
        assertNull(provider.findByOperationKey("GET /x"));
    }
}
