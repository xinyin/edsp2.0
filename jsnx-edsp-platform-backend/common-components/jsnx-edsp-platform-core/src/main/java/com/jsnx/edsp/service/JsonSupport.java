package com.jsnx.edsp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class JsonSupport {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final ObjectMapper mapper;

    public JsonSupport(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> readMap(String json) {
        try {
            return json == null || json.isBlank() ? new LinkedHashMap<>() : mapper.readValue(json, MAP);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON configuration", e);
        }
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot serialize JSON configuration", e);
        }
    }
}
