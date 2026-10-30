package com.gakdo.trace.report.ping;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PingController {

    @GetMapping("/api/v1/reports/ping")
    public Map<String, String> ping() {
        return Map.of("service", "report-server", "status", "ok");
    }
}
