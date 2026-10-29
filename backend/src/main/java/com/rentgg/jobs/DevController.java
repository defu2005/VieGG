package com.rentgg.jobs;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Dev only: run every job once immediately (handy when trying the flow by hand). */
@RestController
@RequestMapping("/api/v1/dev/jobs")
@Profile("dev")
public class DevController {

    private final Jobs jobs;

    public DevController(Jobs jobs) {
        this.jobs = jobs;
    }

    @PostMapping("/run-all")
    @PreAuthorize("hasAuthority('dev:run_jobs')")
    public Map<String, String> runAll() {
        jobs.runAll();
        return Map.of("status", "OK");
    }
}
