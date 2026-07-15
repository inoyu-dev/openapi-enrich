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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import dev.inoyu.openapi.enrich.model.DocPack;

/**
 * Exercises {@link BundleContextDocPackLoader}'s failure isolation: bundle enumeration and
 * per-bundle pack reads must never let an exception escape {@code loadMerged}, since it runs on
 * an OSGi component's activation path where callers (like Unomi's RestServer) must keep starting
 * even if a documentation-only enrichment pack is missing, malformed, or a bundle is uninstalled
 * mid-scan.
 */
class BundleContextDocPackLoaderTest {

    @Test
    void loadMergedReturnsEmptyPackWhenNoBundleContext() {
        DocPack pack = new BundleContextDocPackLoader().loadMerged(null);
        assertNotNull(pack);
        assertTrue(pack.getOperations().isEmpty());
    }

    @Test
    void loadMergedSkipsBundleThatThrowsOnGetState() {
        Bundle broken = fakeBundle(name -> {
            if ("getState".equals(name)) {
                throw new IllegalStateException("bundle uninstalled mid-scan");
            }
            return null;
        });
        BundleContext context = fakeBundleContext(new Bundle[] { broken });

        DocPack pack = assertDoesNotThrow(() -> new BundleContextDocPackLoader().loadMerged(context));
        assertTrue(pack.getOperations().isEmpty());
    }

    @Test
    void loadMergedSkipsBundleWithMalformedPackJson() {
        Bundle malformed = validPackBundle("{ this is not valid json");
        BundleContext context = fakeBundleContext(new Bundle[] { malformed });

        DocPack pack = assertDoesNotThrow(() -> new BundleContextDocPackLoader().loadMerged(context));
        assertTrue(pack.getOperations().isEmpty());
    }

    @Test
    void loadMergedFallsBackWhenGetBundlesThrows() {
        BundleContext context = (BundleContext) Proxy.newProxyInstance(
                BundleContextDocPackLoaderTest.class.getClassLoader(),
                new Class<?>[] { BundleContext.class },
                (proxy, method, args) -> {
                    if ("getBundles".equals(method.getName())) {
                        throw new IllegalStateException("context invalidated");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        DocPack pack = assertDoesNotThrow(() -> new BundleContextDocPackLoader().loadMerged(context));
        assertNotNull(pack);
        assertTrue(pack.getOperations().isEmpty());
    }

    @Test
    void loadMergedReadsValidPackFromActiveBundle() {
        String json = "{"
                + "\"formatVersion\":\"1.1\","
                + "\"bundleId\":\"unomi.rest\","
                + "\"operations\":[{\"operationKey\":\"GET /rules\"}]"
                + "}";
        Bundle bundle = validPackBundle(json);
        BundleContext context = fakeBundleContext(new Bundle[] { bundle });

        DocPack pack = new BundleContextDocPackLoader().loadMerged(context);
        assertEquals(1, pack.getOperations().size());
        assertEquals("unomi.rest", pack.getBundleId());
    }

    @Test
    void loadMergedSkipsOneBadBundleButKeepsGoodOnes() {
        Bundle broken = fakeBundle(name -> {
            if ("getState".equals(name)) {
                throw new IllegalStateException("uninstalled");
            }
            return null;
        });
        String json = "{\"formatVersion\":\"1.1\",\"bundleId\":\"good\","
                + "\"operations\":[{\"operationKey\":\"GET /health\"}]}";
        Bundle good = validPackBundle(json);
        BundleContext context = fakeBundleContext(new Bundle[] { broken, good });

        DocPack pack = new BundleContextDocPackLoader().loadMerged(context);
        assertEquals(1, pack.getOperations().size());
        assertEquals("good", pack.getBundleId());
    }

    // --- test doubles -------------------------------------------------------------------

    private static Bundle validPackBundle(String packJson) {
        return fakeBundle(name -> {
            switch (name) {
                case "getState":
                    return Bundle.ACTIVE;
                case "getEntry":
                    return dataUrl(packJson);
                case "getSymbolicName":
                    return "test.bundle";
                default:
                    return null;
            }
        });
    }

    private static Bundle fakeBundle(Function<String, Object> behavior) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getState":
                case "getEntry":
                case "getSymbolicName":
                    return behavior.apply(method.getName());
                default:
                    return defaultValueFor(method);
            }
        };
        return (Bundle) Proxy.newProxyInstance(
                BundleContextDocPackLoaderTest.class.getClassLoader(), new Class<?>[] { Bundle.class }, handler);
    }

    private static BundleContext fakeBundleContext(Bundle[] bundles) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("getBundles".equals(method.getName())) {
                return bundles;
            }
            return defaultValueFor(method);
        };
        return (BundleContext) Proxy.newProxyInstance(
                BundleContextDocPackLoaderTest.class.getClassLoader(), new Class<?>[] { BundleContext.class },
                handler);
    }

    private static Object defaultValueFor(Method method) {
        Class<?> returnType = method.getReturnType();
        if (!returnType.isPrimitive() || returnType == void.class) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        return 0;
    }

    private static final Map<String, String> DATA_URLS = new HashMap<>();

    /**
     * Builds a fake URL backed by an in-memory map so {@code Bundle.getEntry(...)} can return
     * real, readable content without needing a JAR/bundle on disk.
     */
    private static URL dataUrl(String content) {
        String key = "pack-" + DATA_URLS.size();
        DATA_URLS.put(key, content);
        try {
            return new URL(null, "test-data:" + key, new URLStreamHandler() {
                @Override
                protected java.net.URLConnection openConnection(URL u) {
                    return new java.net.URLConnection(u) {
                        @Override
                        public void connect() {
                            // no-op: content is already resolved from DATA_URLS
                        }

                        @Override
                        public java.io.InputStream getInputStream() {
                            String data = DATA_URLS.get(u.getPath());
                            return new java.io.ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8));
                        }
                    };
                }
            });
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }
}
