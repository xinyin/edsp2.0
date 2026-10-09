package com.jsnx.edsp.observability;
import com.jsnx.edsp.service.InterfaceService;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api")
public class ObservabilityController {
  private final InterfaceService service;
  public ObservabilityController(InterfaceService service){this.service=service;}
  @GetMapping("/usage-records") public Object usage(){return service.usageRecords();}
  @GetMapping("/call-logs") public Object logs(@RequestParam(required=false) String status,@RequestParam(required=false) String interfaceCode,@RequestParam(required=false) String keyword){return service.callLogs(status,interfaceCode,keyword);}
  @GetMapping("/call-logs/{traceId}") public Object trace(@PathVariable String traceId){return service.callTrace(traceId);}
  @GetMapping("/monitor/summary") public Object summary(){return service.monitorSummary();}
  @GetMapping("/monitor/points") public Object points(){return service.monitorPoints();}
  @GetMapping("/monitor/alerts") public Object alerts(){return service.monitorAlerts();}
}
