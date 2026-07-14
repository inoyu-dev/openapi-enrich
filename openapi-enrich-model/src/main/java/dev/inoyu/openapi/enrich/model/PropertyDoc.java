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
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class PropertyDoc {

    private String description;
    private String type;
    private String format;
    /** OpenAPI enum values when the Java type is an enum. */
    private List<String> enumValues = new ArrayList<>();
    /** Optional example for this property (string, number, boolean, etc.). */
    private Object example;
    /** FQCN of the object type this property references (OpenAPI {@code $ref}). */
    private String schemaClass;
    /** FQCN of the array item type when {@link #type} is {@code array}. */
    private String itemsSchemaClass;
    /**
     * Java bean property name when the pack map key is a serialization alias
     * (for example map key {@code type} with {@code javaName=conditionTypeId} from {@code @XmlElement(name="type")} on the getter).
     */
    private String javaName;

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

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public List<String> getEnumValues() {
        return enumValues;
    }

    public void setEnumValues(List<String> enumValues) {
        this.enumValues = enumValues != null ? enumValues : new ArrayList<>();
    }

    public Object getExample() {
        return example;
    }

    public void setExample(Object example) {
        this.example = example;
    }

    public String getSchemaClass() {
        return schemaClass;
    }

    public void setSchemaClass(String schemaClass) {
        this.schemaClass = schemaClass;
    }

    public String getItemsSchemaClass() {
        return itemsSchemaClass;
    }

    public void setItemsSchemaClass(String itemsSchemaClass) {
        this.itemsSchemaClass = itemsSchemaClass;
    }

    public String getJavaName() {
        return javaName;
    }

    public void setJavaName(String javaName) {
        this.javaName = javaName;
    }
}
