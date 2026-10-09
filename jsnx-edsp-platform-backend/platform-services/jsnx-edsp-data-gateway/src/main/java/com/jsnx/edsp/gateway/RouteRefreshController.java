package com.jsnx.edsp.gateway;

import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@RestController
@RequestMapping("/internal/routes")
public class RouteRefreshController {
    private final PublishedRouteRegistry routes;
    public RouteRefreshController(PublishedRouteRegistry routes) { this.routes = routes; }

    @PostMapping("/refresh")
    public Mono<Map<String, Object>> refresh() {
        // Registry refresh performs a bounded HTTP pull from Admin. Keep that
        // blocking boundary off the Netty event loop used by Gateway traffic.
        return Mono.fromCallable(routes::refreshSafely)
                .subscribeOn(Schedulers.boundedElastic())
                .map(snapshot -> Map.of("routeCount", (Object) snapshot.byCode().size(),
                        "loadedAt", snapshot.loadedAt().toString(),
                        "success", snapshot.lastError() == null,
                        "message", snapshot.lastError() == null ? "OK" : snapshot.lastError()));
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        var snapshot = routes.snapshot();
        return Map.of("routeCount", snapshot.byCode().size(), "loadedAt", snapshot.loadedAt().toString(),
                "healthy", snapshot.lastError() == null, "lastError", snapshot.lastError() == null ? "" : snapshot.lastError());
    }
}
