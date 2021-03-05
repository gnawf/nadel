package graphql.nadel;

import graphql.PublicApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

@PublicApi
public class ServiceExecutionResult {
    public static final List<ServiceExecutionResult> results = new ArrayList<>();

    public static void clear() {
        results.clear();
    }

    private final List<Map<String, Object>> errors;
    private final Map<String, Object> data;
    private final Map<String, Object> extensions;

    public ServiceExecutionResult(Map<String, Object> data, List<Map<String, Object>> errors, Map<String, Object> extensions) {
        this.data = data;
        this.errors = errors == null ? emptyList() : errors;
        this.extensions = extensions == null ? emptyMap() : extensions;

        results.add(this);
    }

    public ServiceExecutionResult(Map<String, Object> data, List<Map<String, Object>> errors) {
        this(data, errors, emptyMap());
    }

    public ServiceExecutionResult(Map<String, Object> data) {
        this(data, emptyList(), emptyMap());
    }

    public Map<String, Object> getData() {
        return data;
    }

    public List<Map<String, Object>> getErrors() {
        return errors;
    }

    public Map<String, Object> getExtensions() {
        return extensions;
    }
}
