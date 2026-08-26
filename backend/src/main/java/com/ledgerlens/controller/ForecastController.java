package com.ledgerlens.controller;

import com.ledgerlens.dto.ForecastEntry;
import com.ledgerlens.service.ForecastService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/forecast")
public class ForecastController {

    private final ForecastService forecastService;

    public ForecastController(ForecastService forecastService) {
        this.forecastService = forecastService;
    }

    @GetMapping("/{batchId}")
    public List<ForecastEntry> forecast(@PathVariable UUID batchId) {
        return forecastService.forecast(batchId);
    }
}
