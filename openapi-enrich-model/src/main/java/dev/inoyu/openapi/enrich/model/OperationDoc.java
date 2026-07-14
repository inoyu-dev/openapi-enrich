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

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class OperationDoc {

    /** e.g. {@code GET /tenants/{tenantId}} */
    private String operationKey;
    private String className;
    private String methodName;
    private String httpMethod;
    private String path;
    private String summary;
    private String description;
    private Map<String, ParamDoc> parameters = new LinkedHashMap<>();
    private Map<String, ResponseDoc> responses = new LinkedHashMap<>();
    /** FQCN of the JSON request body schema when the operation accepts an entity body. */
    private String requestBodySchemaClass;
    /** Optional description for the request body. */
    private String requestBodyDescription;

    public String getOperationKey() {
        return operationKey;
    }

    public void setOperationKey(String operationKey) {
        this.operationKey = operationKey;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, ParamDoc> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, ParamDoc> parameters) {
        this.parameters = parameters != null ? parameters : new LinkedHashMap<>();
    }

    public Map<String, ResponseDoc> getResponses() {
        return responses;
    }

    public void setResponses(Map<String, ResponseDoc> responses) {
        this.responses = responses != null ? responses : new LinkedHashMap<>();
    }

    public String getRequestBodySchemaClass() {
        return requestBodySchemaClass;
    }

    public void setRequestBodySchemaClass(String requestBodySchemaClass) {
        this.requestBodySchemaClass = requestBodySchemaClass;
    }

    public String getRequestBodyDescription() {
        return requestBodyDescription;
    }

    public void setRequestBodyDescription(String requestBodyDescription) {
        this.requestBodyDescription = requestBodyDescription;
    }
}
