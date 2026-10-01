package com.junie.serviceops.api;

import com.junie.serviceops.service.DependencyGraphService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/services")
public class ServiceController {
    private final DependencyGraphService graph;
    public ServiceController(DependencyGraphService graph) { this.graph = graph; }
    @GetMapping("/{service}/dependencies")
    public List<String> dependencies(@PathVariable String service) { return graph.dependenciesOf(service); }
}
