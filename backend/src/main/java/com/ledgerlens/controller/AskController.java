package com.ledgerlens.controller;

import com.ledgerlens.dto.AskRequest;
import com.ledgerlens.dto.AskResponse;
import com.ledgerlens.service.QuestionAnswerer;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/ask")
public class AskController {

    private final QuestionAnswerer questionAnswerer;

    public AskController(QuestionAnswerer questionAnswerer) {
        this.questionAnswerer = questionAnswerer;
    }

    @PostMapping("/{batchId}")
    public AskResponse ask(@PathVariable UUID batchId, @Valid @RequestBody AskRequest request) {
        return questionAnswerer.ask(batchId, request.question());
    }
}
