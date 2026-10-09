package com.jsnx.edsp.web;

import com.jsnx.edsp.service.InterfaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/mock/vendor")
public class VendorMockController {
    private final InterfaceService service;

    public VendorMockController(InterfaceService service) {
        this.service = service;
    }

    @PostMapping("/{interfaceCode}")
    public ResponseEntity<Map<String, Object>> invoke(
            @PathVariable String interfaceCode,
            @RequestBody(required = false) Map<String, Object> request) {
        var config = service.vendorMockByCode(interfaceCode);
        return ResponseEntity.status(config.httpStatus())
                .header("X-EDSP-Mock", config.interfaceCode())
                .body(config.responseBody());
    }
}
