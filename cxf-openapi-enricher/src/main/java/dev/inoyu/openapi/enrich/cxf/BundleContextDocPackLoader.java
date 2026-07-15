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

    /**
     * Never throws: any failure enumerating bundles, or reading/parsing a single bundle's
     * pack, is logged and treated as "no pack from that source" rather than propagated, since
     * callers (e.g. an OSGi component's activation path) must not fail JAX-RS server startup
     * because a documentation-only enrichment pack is missing or malformed.
     */
    public DocPack loadMerged(BundleContext bundleContext) {
        if (bundleContext == null) {
            return safeClasspathLoad();
        }
        Bundle[] bundles;
        try {
            bundles = bundleContext.getBundles();
        } catch (RuntimeException e) {
            LOG.warn("Failed to enumerate OSGi bundles for OpenAPI doc packs; falling back to classpath loader", e);
            return safeClasspathLoad();
        }
        List<DocPack> packs = new ArrayList<>();
        for (Bundle bundle : bundles) {
            try {
                DocPack pack = loadFromBundle(bundle);
                if (pack != null) {
                    packs.add(pack);
                    LOG.info("Loaded OpenAPI doc pack from bundle {} (bundleId={})",
                            safeSymbolicName(bundle), pack.getBundleId());
                }
            } catch (IOException | RuntimeException e) {
                // IOException: unreadable/malformed pack content (Jackson parse failure, stream error).
                // RuntimeException: e.g. IllegalStateException from a bundle uninstalled mid-iteration.
                LOG.warn("Failed to read OpenAPI doc pack from bundle {}", safeSymbolicName(bundle), e);
            }
        }
        if (packs.isEmpty()) {
            LOG.debug("No OSGi OpenAPI doc packs found; falling back to classpath loader");
            return safeClasspathLoad();
        }
        return ClasspathDocPackLoader.merge(packs);
    }

    private DocPack loadFromBundle(Bundle bundle) throws IOException {
        int state = bundle.getState();
        if (state != Bundle.ACTIVE && state != Bundle.STARTING && state != Bundle.RESOLVED) {
            return null;
        }
        URL entry = bundle.getEntry("/" + DocPackResource.PATH);
        if (entry == null) {
            entry = bundle.getEntry(DocPackResource.PATH);
        }
        if (entry == null) {
            return null;
        }
        try (InputStream in = entry.openStream()) {
            return mapper.readValue(in, DocPack.class);
        }
    }

    private static String safeSymbolicName(Bundle bundle) {
        try {
            return bundle.getSymbolicName();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private DocPack safeClasspathLoad() {
        try {
            return new ClasspathDocPackLoader().loadMerged();
        } catch (RuntimeException e) {
            LOG.warn("Classpath OpenAPI doc pack loading failed; returning an empty doc pack", e);
            return ClasspathDocPackLoader.merge(java.util.Collections.emptyList());
        }
    }
}
