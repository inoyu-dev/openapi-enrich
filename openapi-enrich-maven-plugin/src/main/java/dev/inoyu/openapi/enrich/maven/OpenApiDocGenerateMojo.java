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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.model.Resource;
import org.apache.maven.project.MavenProject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import dev.inoyu.openapi.enrich.model.DocPack;

/**
 * Generates a JAX-RS OpenAPI enrichment pack JSON from source Javadoc.
 * Runs in {@code generate-resources} so the pack is included in jar/OSGi bundles.
 */
@Mojo(name = "generate",
        defaultPhase = LifecyclePhase.GENERATE_RESOURCES,
        requiresDependencyResolution = ResolutionScope.NONE,
        threadSafe = true)
public class OpenApiDocGenerateMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Source roots to scan for JAX-RS resources (defaults to project compile source roots). */
    @Parameter
    private List<File> sourceRoots;

    /** Optional package prefixes to include. Empty means all packages. */
    @Parameter
    private List<String> includePackages;

    /** Javadoc tag name for status/schema lines (default {@code api.status}). */
    @Parameter(defaultValue = "api.status")
    private String statusTag;

    /**
     * Directory registered as a Maven resource root (so OSGi/JAR packaging picks the pack up).
     */
    @Parameter(defaultValue = "${project.build.directory}/generated-resources/openapi-enrich")
    private File generatedResourceDirectory;

    /** Output file for the enrichment pack (under the generated resource directory). */
    @Parameter(defaultValue = "${project.build.directory}/generated-resources/openapi-enrich/META-INF/openapi-enrich/openapi-doc.json")
    private File outputFile;

    /** Bundle identifier written into the pack. */
    @Parameter(defaultValue = "${project.artifactId}")
    private String bundleId;

    /** Extra source roots scanned for schema/model Javadoc. */
    @Parameter
    private List<File> extraSourceRoots;

    /**
     * Optional servlet class → absolute path mappings.
     * Prefer list entries over a Map so FQCNs with dots are valid Maven XML.
     */
    @Parameter
    private List<ServletPathMapping> servletPaths;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            List<Path> roots = resolveSourceRoots();
            List<Path> extra = toPaths(extraSourceRoots);
            List<String> packages = includePackages != null ? includePackages : List.of();
            Map<String, String> servlets = toServletPathMap(servletPaths);

            JaxrsSourceScanner scanner = new JaxrsSourceScanner(
                    roots, extra, packages, statusTag, bundleId, servlets);
            DocPack pack = scanner.scan();

            Path out = outputFile.toPath();
            Files.createDirectories(out.getParent());
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(out.toFile(), pack);

            registerResourceRoot();

            getLog().info("Wrote OpenAPI doc pack (" + pack.getOperations().size()
                    + " operations, " + pack.getSchemas().size() + " schemas, "
                    + pack.getServletOperations().size() + " servlet ops) to " + out);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to generate OpenAPI doc pack", e);
        }
    }

    private void registerResourceRoot() {
        File dir = generatedResourceDirectory;
        if (dir == null) {
            return;
        }
        String absolute = dir.getAbsolutePath();
        for (Resource existing : project.getResources()) {
            if (absolute.equals(existing.getDirectory())) {
                return;
            }
        }
        Resource resource = new Resource();
        resource.setDirectory(absolute);
        resource.setFiltering(false);
        project.addResource(resource);
        getLog().debug("Registered OpenAPI doc resource directory: " + absolute);
    }

    private List<Path> resolveSourceRoots() {
        if (sourceRoots != null && !sourceRoots.isEmpty()) {
            return toPaths(sourceRoots);
        }
        return project.getCompileSourceRoots().stream()
                .map(Path::of)
                .collect(Collectors.toList());
    }

    private static List<Path> toPaths(List<File> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>();
        for (File file : files) {
            if (file != null) {
                paths.add(file.toPath());
            }
        }
        return paths;
    }

    private static Map<String, String> toServletPathMap(List<ServletPathMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (ServletPathMapping mapping : mappings) {
            if (mapping == null || mapping.getClassName() == null || mapping.getPath() == null) {
                continue;
            }
            map.put(mapping.getClassName().trim(), mapping.getPath().trim());
        }
        return map;
    }
}
