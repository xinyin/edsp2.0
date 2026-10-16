package com.jsnx.edsp.web;

import com.jsnx.edsp.model.ApiModels.SaveProviderRuntimeRequest;
import com.jsnx.edsp.provision.DataApplicationProvisionService;
import com.jsnx.edsp.provision.ProvisionModels;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class DataApplicationProvisionController {
    private final DataApplicationProvisionService service;
    public DataApplicationProvisionController(DataApplicationProvisionService service) { this.service = service; }
    @GetMapping("/data-application-provision/settings")
    public ProvisionModels.Settings settings() { return service.settings(); }
    @GetMapping("/providers/{id}/runtime/status")
    public ProvisionModels.Result status(@PathVariable long id) { return service.status(id); }
    @PutMapping("/providers/{id}/runtime")
    public ProvisionModels.Result save(@PathVariable long id, @Valid @RequestBody SaveProviderRuntimeRequest request) {
        return service.save(id, request);
    }
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public java.util.Map<String, String> invalid(RuntimeException ex) { return java.util.Map.of("message", ex.getMessage()); }
}
