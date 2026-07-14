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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.inoyu.openapi.enrich.model.DocPack;

class OpenApiDocGenerateMojoTest {

    @Test
    void executeWritesPackWithAtLeastDeclaredHttpMethods() throws Exception {
        Path dir = Files.createTempDirectory("openapi-enrich-mojo");
        Path outDir = Files.createTempDirectory("openapi-enrich-mojo-out");
        Files.writeString(dir.resolve("Widgets.java"), ""
                + "package com.example.api;\n"
                + "import javax.ws.rs.GET;\n"
                + "import javax.ws.rs.POST;\n"
                + "import javax.ws.rs.Path;\n"
                + "@Path(\"/widgets\")\n"
                + "public class Widgets {\n"
                + "  @GET public String list() { return \"\"; }\n"
                + "  @POST public void create(String body) {}\n"
                + "  @GET @Path(\"/{id}\") public String get() { return \"\"; }\n"
                + "}\n");

        File outputFile = outDir.resolve("META-INF/openapi-enrich/openapi-doc.json").toFile();
        File generatedRoot = outDir.toFile();

        MavenProject project = new MavenProject();
        project.setArtifactId("demo-rest");

        OpenApiDocGenerateMojo mojo = new OpenApiDocGenerateMojo();
        mojo.setLog(new SystemStreamLog());
        set(mojo, "project", project);
        set(mojo, "sourceRoots", List.of(dir.toFile()));
        set(mojo, "extraSourceRoots", List.of());
        set(mojo, "includePackages", List.of("com.example.api"));
        set(mojo, "statusTag", "api.status");
        set(mojo, "bundleId", "demo-rest");
        set(mojo, "generatedResourceDirectory", generatedRoot);
        set(mojo, "outputFile", outputFile);
        set(mojo, "servletPaths", List.of());

        mojo.execute();

        assertTrue(outputFile.isFile());
        JsonNode json = new ObjectMapper().readTree(outputFile);
        assertEquals(DocPack.CURRENT_FORMAT_VERSION, json.get("formatVersion").asText());
        assertEquals("demo-rest", json.get("bundleId").asText());
        assertTrue(json.get("operations").size() >= 3,
                "pack should include at least the 3 declared HTTP methods, got "
                        + json.get("operations").size());
        assertTrue(project.getResources().stream()
                .anyMatch(r -> generatedRoot.getAbsolutePath().equals(r.getDirectory())));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = OpenApiDocGenerateMojo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
