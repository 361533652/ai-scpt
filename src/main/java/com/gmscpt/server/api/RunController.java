package com.gmscpt.server.api;

import com.gmscpt.server.domain.RunRecord;
import com.gmscpt.server.kernel.runtime.ScriptHost;
import com.gmscpt.server.service.RunService;
import com.gmscpt.server.store.RunRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 运行控制接口。语义见 {@code docs/api-contract.md} 第 3 节。 */
@RestController
public class RunController {

    public record RunRequest(String trigger) {
    }

    private final RunService runService;
    private final RunRepository runRepository;

    public RunController(RunService runService, RunRepository runRepository) {
        this.runService = runService;
        this.runRepository = runRepository;
    }

    @PostMapping("/api/scripts/{id}/run")
    public ResponseEntity<Map<String, Object>> run(@PathVariable String id,
                                                   @RequestBody(required = false) RunRequest req) {
        String trigger = req == null || req.trigger() == null ? "manual" : req.trigger();
        ScriptHost host = runService.run(id, trigger);
        return ResponseEntity.accepted().body(Map.of(
                "runId", host.runId(),
                "scriptId", host.scriptId(),
                "status", "RUNNING"));
    }

    @PostMapping("/api/scripts/{id}/stop")
    public Map<String, Object> stop(@PathVariable String id) {
        return Map.of("stopped", runService.stop(id));
    }

    @PostMapping("/api/runs/stop-all")
    public Map<String, Object> stopAll() {
        return Map.of("stopped", runService.active().stream().map(RunService.ActiveRun::scriptId).toList());
    }

    @GetMapping("/api/runs/active")
    public Map<String, Object> active() {
        return Map.of("items", runService.active());
    }

    @GetMapping("/api/runs")
    public Map<String, Object> history(@RequestParam(required = false) String scriptId,
                                       @RequestParam(defaultValue = "50") int limit) {
        List<RunRecord> items = scriptId == null || scriptId.isBlank()
                ? runRepository.recent(limit)
                : runRepository.findByScript(scriptId, limit);
        return Map.of("items", items);
    }
}
