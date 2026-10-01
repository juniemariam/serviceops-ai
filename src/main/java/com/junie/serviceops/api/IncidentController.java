package com.junie.serviceops.api;

import com.junie.serviceops.model.ApprovalRequest;
import com.junie.serviceops.model.Diagnosis;
import com.junie.serviceops.model.Incident;
import com.junie.serviceops.model.Remediation;
import com.junie.serviceops.service.DiagnosisService;
import com.junie.serviceops.service.RemediationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/incidents")
public class IncidentController {

    private final DiagnosisService diagnosis;
    private final RemediationService remediation;

    public IncidentController(DiagnosisService diagnosis, RemediationService remediation) {
        this.diagnosis = diagnosis;
        this.remediation = remediation;
    }

    @PostMapping
    public Incident create(@Valid @RequestBody Incident incident) {
        return diagnosis.create(incident);
    }

    @GetMapping
    public List<Incident> recent(@RequestParam(defaultValue = "20") @Min(1) @Max(200) int limit) {
        return diagnosis.recent(limit);
    }

    @GetMapping("/{id}")
    public Incident get(@PathVariable String id) {
        return diagnosis.get(id);
    }

    @PostMapping("/{id}/diagnose")
    public Diagnosis diagnose(@PathVariable String id) {
        return diagnosis.diagnose(id);
    }

    @PostMapping("/{id}/remediations/{actionId}/approval")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Remediation approve(@PathVariable String id, @PathVariable String actionId,
                               @Valid @RequestBody ApprovalRequest approval) {
        return remediation.approveAndExecute(id, actionId, approval);
    }

    @GetMapping("/{id}/remediations")
    public List<Remediation> remediations(@PathVariable String id) {
        return remediation.history(id);
    }
}
