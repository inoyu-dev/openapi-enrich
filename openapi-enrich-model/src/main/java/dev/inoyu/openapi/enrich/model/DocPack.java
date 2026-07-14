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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocPack {

    /**
     * Pack contract version. {@code 1.1} adds {@code SchemaDoc.parentFqcn},
     * property {@code schemaClass}/{@code itemsSchemaClass}, and operation requestBody fields.
     * Loaders accept older {@code 1.0} packs (missing fields deserialize as null).
     */
    public static final String CURRENT_FORMAT_VERSION = "1.1";

    private String formatVersion = CURRENT_FORMAT_VERSION;
    private String bundleId;
    private List<OperationDoc> operations = new ArrayList<>();
    private Map<String, SchemaDoc> schemas = new LinkedHashMap<>();
    private List<ServletOperationDoc> servletOperations = new ArrayList<>();

    public String getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(String formatVersion) {
        this.formatVersion = formatVersion;
    }

    public String getBundleId() {
        return bundleId;
    }

    public void setBundleId(String bundleId) {
        this.bundleId = bundleId;
    }

    public List<OperationDoc> getOperations() {
        return operations;
    }

    public void setOperations(List<OperationDoc> operations) {
        this.operations = operations != null ? operations : new ArrayList<>();
    }

    public Map<String, SchemaDoc> getSchemas() {
        return schemas;
    }

    public void setSchemas(Map<String, SchemaDoc> schemas) {
        this.schemas = schemas != null ? schemas : new LinkedHashMap<>();
    }

    public List<ServletOperationDoc> getServletOperations() {
        return servletOperations;
    }

    public void setServletOperations(List<ServletOperationDoc> servletOperations) {
        this.servletOperations = servletOperations != null ? servletOperations : new ArrayList<>();
    }
}
