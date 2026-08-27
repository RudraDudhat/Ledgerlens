package com.ledgerlens.controller;

import com.ledgerlens.service.StatementPdfService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The statement download. Served as an attachment rather than inline: this is a document to keep
 * and forward, not a page to browse.
 */
@RestController
@RequestMapping("/api/reconcile")
public class StatementController {

    private final StatementPdfService statementPdfService;

    public StatementController(StatementPdfService statementPdfService) {
        this.statementPdfService = statementPdfService;
    }

    @GetMapping(value = "/{batchId}/statement.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> statement(@PathVariable UUID batchId) {
        StatementPdfService.Statement statement = statementPdfService.render(batchId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("ledgerlens-statement-%s.pdf".formatted(statement.period()))
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(statement.pdf());
    }
}
