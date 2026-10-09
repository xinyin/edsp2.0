package com.jsnx.edsp.plan;

import com.jsnx.edsp.crypto.SecretReferenceResolver;
import java.util.List;
import java.util.Map;

/** SPI implemented by built-in algorithms and optional external extension JARs. */
public interface SecurityAlgorithm {
    record Parameter(String name, String label, String type, boolean required,
                     String defaultValue, List<String> options, String placeholder) {}
    record Descriptor(String code, String name, String kind, String category,
                      String description, List<Parameter> parameters, boolean builtIn) {}

    Descriptor descriptor();
    String execute(String value, Map<String, Object> config, SecretReferenceResolver secrets);
}
