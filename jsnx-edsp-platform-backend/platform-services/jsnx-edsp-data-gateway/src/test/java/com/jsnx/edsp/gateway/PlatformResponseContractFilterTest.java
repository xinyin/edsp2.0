package com.jsnx.edsp.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformResponseContractFilterTest {
    @Test
    void addsUnmappedContractFieldsAsNullAndPreservesExistingValues() throws Exception {
        PlatformResponseContractRegistry registry = mock(PlatformResponseContractRegistry.class);
        when(registry.fields()).thenReturn(List.of(
                Map.of("key", "code", "type", "STRING"),
                Map.of("key", "project_id", "type", "STRING"),
                Map.of("key", "data", "type", "OBJECT")));
        var filter = new PlatformResponseContractFilter(new ObjectMapper(), registry);

        byte[] actual = filter.enrich("{\"code\":\"0000\",\"data\":{\"id\":1}}".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> response = new ObjectMapper().readValue(actual, Map.class);

        assertThat(response).containsEntry("code", "0000")
                .containsEntry("project_id", null)
                .containsEntry("data", Map.of("id", 1));
    }

    @Test
    void leavesNonObjectJsonResponsesUntouched() {
        PlatformResponseContractRegistry registry = mock(PlatformResponseContractRegistry.class);
        when(registry.fields()).thenReturn(List.of(Map.of("key", "project_id")));
        var filter = new PlatformResponseContractFilter(new ObjectMapper(), registry);
        byte[] input = "[1,2]".getBytes(StandardCharsets.UTF_8);

        assertThat(filter.enrich(input)).isEqualTo(input);
    }
}
