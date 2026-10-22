package com.jsnx.edsp.gateway;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal/response-contract")
public class PlatformResponseContractRefreshController {
    private final PlatformResponseContractRegistry registry;

    public PlatformResponseContractRefreshController(PlatformResponseContractRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        registry.refreshSafely();
        return Map.of("success", true, "fieldCount", registry.fields().size());
    }
}
