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
import java.util.List;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.inoyu.openapi.enrich.model.DocPack;
import dev.inoyu.openapi.enrich.model.DocPackResource;

/**
 * Loads enrichment packs from all OSGi bundles that contain {@link DocPackResource#PATH}.
 * Falls back to the thread context / own classloader when no {@link BundleContext} is available.
 */
public class BundleContextDocPackLoader {

    private static final Logger LOG = LoggerFactory.getLogger(BundleContextDocPackLoader.class);

    private final ObjectMapper mapper;

    public BundleContextDocPackLoader() {
        this(new ObjectMapper());
    }

    public BundleContextDocPackLoader(ObjectMapper mapper) {
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    public DocPack loadMerged(BundleContext bundleContext) {
        if (bundleContext == null) {
            return new ClasspathDocPackLoader().loadMerged();
        }
        List<DocPack> packs = new ArrayList<>();
        for (Bundle bundle : bundleContext.getBundles()) {
            int state = bundle.getState();
            if (state != Bundle.ACTIVE && state != Bundle.STARTING && state != Bundle.RESOLVED) {
                continue;
            }
            URL entry = bundle.getEntry("/" + DocPackResource.PATH);
            if (entry == null) {
                entry = bundle.getEntry(DocPackResource.PATH);
            }
            if (entry == null) {
                continue;
            }
            try (InputStream in = entry.openStream()) {
                DocPack pack = mapper.readValue(in, DocPack.class);
                packs.add(pack);
                LOG.info("Loaded OpenAPI doc pack from bundle {} (bundleId={})",
                        bundle.getSymbolicName(), pack.getBundleId());
            } catch (IOException e) {
                LOG.warn("Failed to read OpenAPI doc pack from bundle {}", bundle.getSymbolicName(), e);
            }
        }
        if (packs.isEmpty()) {
            LOG.debug("No OSGi OpenAPI doc packs found; falling back to classpath loader");
            return new ClasspathDocPackLoader().loadMerged();
        }
        return ClasspathDocPackLoader.merge(packs);
    }
}
