package com.jsnx.edsp.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublishedPlanRegistryTest {
    @Test
    void atomicallyReplacesPublishedPlan() {
        PublishedPlanRegistry registry = new PublishedPlanRegistry();
        registry.activate(new PublishedPlanRegistry.PublishedPlan(1, "TEST", 1, List.of(), List.of(), "a", Instant.now()));
        registry.activate(new PublishedPlanRegistry.PublishedPlan(1, "TEST", 2, List.of(), List.of(), "b", Instant.now()));
        assertThat(registry.get(1).version()).isEqualTo(2);
        assertThat(registry.size()).isEqualTo(1);
    }
}
