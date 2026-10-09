package com.jsnx.edsp.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class PublishedRouteRegistryTest {
    private final PublishedRouteRegistry registry = new PublishedRouteRegistry(WebClient.builder(), "http://127.0.0.1:1");

    @Test
    void resolvesAnArbitraryPublishedPathWithoutHardCodedBusinessPrefix() {
        var route = new PublishedRouteRegistry.PublishedRoute(11, "LHZX_QUERY_01_SERVICE_01",
                "/api/v1/lhzx-query-01/01", "POST", 3, 9,
                "LHZX_QUERY_01", "jsnx-edsp-data-source-lhzx-query-01", "http://127.0.0.1:8191");
        registry.install(List.of(route));

        assertThat(registry.resolve("LHZX_QUERY_01_SERVICE_01", "POST", "/api/v1/lhzx-query-01/01"))
                .contains(route);
        assertThat(registry.resolve(null, "POST", "/api/v1/lhzx-query-01/01")).isEmpty();
    }

    @Test
    void rejectsApiCodePathOrMethodMismatch() {
        var first = route(1, "API_ONE", "/api/v1/arbitrary/one");
        var second = route(2, "API_TWO", "/api/v1/arbitrary/two");
        registry.install(List.of(first, second));

        assertThat(registry.resolve("API_ONE", "POST", second.servicePath())).isEmpty();
        assertThat(registry.resolve("API_ONE", "GET", first.servicePath())).isEmpty();
        assertThat(registry.resolve("UNKNOWN", "POST", first.servicePath())).isEmpty();
    }

    @Test
    void duplicateCodeOrMethodPathCannotReplaceTheLastGoodSnapshot() {
        var valid = route(1, "API_ONE", "/api/v1/arbitrary/one");
        registry.install(List.of(valid));
        assertThatThrownBy(() -> registry.install(List.of(valid,
                route(2, "API_ONE", "/api/v1/arbitrary/two")))).hasMessageContaining("重复");
        assertThat(registry.resolve("API_ONE", "POST", valid.servicePath())).contains(valid);
    }

    @Test
    void routeMayRemainVisibleWhileRuntimeAddressIsNotYetProvisioned() {
        var route = new PublishedRouteRegistry.PublishedRoute(1, "API_ONE", "/api/v1/a/one",
                "POST", 1, 1, "A", "jsnx-edsp-data-a", "");
        registry.install(List.of(route));
        assertThat(registry.resolve("API_ONE", "POST", route.servicePath())).contains(route);
        assertThat(route.runtimeUrl()).isBlank();
    }

    @Test
    void installingANewReleaseAtomicallyReplacesThePreviousRouteSnapshot() {
        var version3 = new PublishedRouteRegistry.PublishedRoute(1, "API_ONE", "/service/v3", "POST",
                3, 1, "SOURCE_1", "jsnx-edsp-data-1", "http://127.0.0.1:8181");
        var version4 = new PublishedRouteRegistry.PublishedRoute(1, "API_ONE", "/service/v4", "POST",
                4, 1, "SOURCE_1", "jsnx-edsp-data-1", "http://127.0.0.1:8181");

        registry.install(List.of(version3));
        assertThat(registry.resolve("API_ONE", "POST", "/service/v3")).contains(version3);

        registry.install(List.of(version4));
        assertThat(registry.resolve("API_ONE", "POST", "/service/v3")).isEmpty();
        assertThat(registry.resolve("API_ONE", "POST", "/service/v4")).contains(version4);
        assertThat(registry.snapshot().byCode()).hasSize(1);
    }

    private PublishedRouteRegistry.PublishedRoute route(long id, String code, String path) {
        return new PublishedRouteRegistry.PublishedRoute(id, code, path, "POST", 1, id,
                "SOURCE_" + id, "jsnx-edsp-data-" + id, "http://127.0.0.1:8181");
    }
}
