package com.jsnx.edsp.observability;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal/events")
public class DemoEventController {
    private final DemoEventIngestionService events;

    public DemoEventController(DemoEventIngestionService events) { this.events = events; }

    @PostMapping("/call")
    public Map<String, Object> call(@RequestBody Map<String, Object> event) {
        events.consumeCall(event);
        return Map.of("accepted", true, "transport", "LOCAL_KAFKA_SIMULATION");
    }

    @PostMapping("/gateway")
    public Map<String, Object> gateway(@RequestBody Map<String, Object> event) {
        events.consumeGateway(event);
        return Map.of("accepted", true, "transport", "LOCAL_KAFKA_SIMULATION");
    }
}
