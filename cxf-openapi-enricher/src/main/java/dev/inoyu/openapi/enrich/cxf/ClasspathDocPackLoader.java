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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.inoyu.openapi.enrich.model.DocPack;
import dev.inoyu.openapi.enrich.model.DocPackResource;
import dev.inoyu.openapi.enrich.model.OperationDoc;
import dev.inoyu.openapi.enrich.model.SchemaDoc;
import dev.inoyu.openapi.enrich.model.ServletOperationDoc;

/**
 * Loads and merges all classpath enrichment packs at {@link DocPackResource#PATH}.
 */
public class ClasspathDocPackLoader {

    private static final Logger LOG = LoggerFactory.getLogger(ClasspathDocPackLoader.class);

    private final ObjectMapper mapper;
    private final ClassLoader classLoader;

    public ClasspathDocPackLoader() {
        this(ClasspathDocPackLoader.class.getClassLoader());
    }

    public ClasspathDocPackLoader(ClassLoader classLoader) {
        this(classLoader, new ObjectMapper());
    }

    public ClasspathDocPackLoader(ClassLoader classLoader, ObjectMapper mapper) {
        this.classLoader = classLoader != null ? classLoader : ClassLoader.getSystemClassLoader();
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    public DocPack loadMerged() {
        List<DocPack> packs = new ArrayList<>();
        try {
            Enumeration<URL> resources = classLoader.getResources(DocPackResource.PATH);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                try (InputStream in = url.openStream()) {
                    DocPack pack = mapper.readValue(in, DocPack.class);
                    packs.add(pack);
                    LOG.info("Loaded OpenAPI doc pack from {} (bundleId={})", url, pack.getBundleId());
                } catch (IOException e) {
                    LOG.warn("Failed to read OpenAPI doc pack from {}", url, e);
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to enumerate OpenAPI doc packs", e);
        }
        return merge(packs);
    }

    static DocPack merge(List<DocPack> packs) {
        DocPack merged = new DocPack();
        merged.setFormatVersion(DocPack.CURRENT_FORMAT_VERSION);
        merged.setSchemas(new LinkedHashMap<>());

        List<String> bundleIds = new ArrayList<>();
        for (DocPack pack : packs) {
            if (pack.getBundleId() != null) {
                bundleIds.add(pack.getBundleId());
            }
            if (pack.getOperations() != null) {
                for (OperationDoc op : pack.getOperations()) {
                    merged.getOperations().add(op);
                }
            }
            if (pack.getSchemas() != null) {
                for (Map.Entry<String, SchemaDoc> entry : pack.getSchemas().entrySet()) {
                    merged.getSchemas().put(entry.getKey(), entry.getValue());
                }
            }
            if (pack.getServletOperations() != null) {
                for (ServletOperationDoc op : pack.getServletOperations()) {
                    merged.getServletOperations().add(op);
                }
            }
        }
        if (!bundleIds.isEmpty()) {
            merged.setBundleId(String.join("+", bundleIds));
        }
        return merged;
    }
}
