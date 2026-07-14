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
public class SchemaDoc {

    private String description;
    /** OpenAPI type when this schema is a scalar (for example a Java enum as string). */
    private String type;
    /** Enum constants when this schema represents a Java enum. */
    private List<String> enumValues = new ArrayList<>();
    /**
     * Fully-qualified name of the concrete Java superclass whose fields this type inherits.
     * Own {@link #properties} are only those declared on this type; enrichers walk the chain.
     */
    private String parentFqcn;
    private Map<String, PropertyDoc> properties = new LinkedHashMap<>();
    /**
     * Java bean property names that must not appear in OpenAPI (for example
     * {@code @XmlTransient} / {@code @JsonIgnore} members that CXF still emits).
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> suppressedProperties = new ArrayList<>();

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<String> getEnumValues() {
        return enumValues;
    }

    public void setEnumValues(List<String> enumValues) {
        this.enumValues = enumValues != null ? enumValues : new ArrayList<>();
    }

    public String getParentFqcn() {
        return parentFqcn;
    }

    public void setParentFqcn(String parentFqcn) {
        this.parentFqcn = parentFqcn;
    }

    public Map<String, PropertyDoc> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, PropertyDoc> properties) {
        this.properties = properties != null ? properties : new LinkedHashMap<>();
    }

    public List<String> getSuppressedProperties() {
        return suppressedProperties;
    }

    public void setSuppressedProperties(List<String> suppressedProperties) {
        this.suppressedProperties = suppressedProperties != null ? suppressedProperties : new ArrayList<>();
    }
}
