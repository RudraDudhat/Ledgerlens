package com.ledgerlens.controller;

import com.ledgerlens.dto.IngestResponse;
import com.ledgerlens.service.CsvIngestService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    private final CsvIngestService csvIngestService;

    public IngestController(CsvIngestService csvIngestService) {
        this.csvIngestService = csvIngestService;
    }

    @PostMapping(value = "/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestResponse ingestCsv(@RequestPart("orders") MultipartFile orders,
                                    @RequestPart("settlement") MultipartFile settlement,
                                    @RequestPart("bank") MultipartFile bank) throws IOException {
        return csvIngestService.ingest(orders.getInputStream(), settlement.getInputStream(), bank.getInputStream());
    }
}
