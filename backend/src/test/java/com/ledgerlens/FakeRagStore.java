package com.ledgerlens;

import com.ledgerlens.service.RagStore;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An in-memory {@link RagStore} so the suite needs neither an embedding key nor pgvector.
 *
 * <p>"Similarity" here is word overlap, which is enough to prove the two things that matter: that a
 * question reaches only the batch it was asked about, and that the wiring around retrieval works.
 * How good the embeddings are is a question for the provider, not for this codebase.
 */
public class FakeRagStore implements RagStore {

    private final Map<UUID, List<Document>> byBatch = new LinkedHashMap<>();
    private final List<UUID> searchedBatches = new ArrayList<>();
    private RuntimeException failure;

    /** Makes the next write blow up, so a caller's error handling can be exercised. */
    public void failOnNextWrite(RuntimeException toThrow) {
        this.failure = toThrow;
    }

    public List<UUID> searchedBatches() {
        return searchedBatches;
    }

    public void seed(UUID batchId, Document... documents) {
        byBatch.computeIfAbsent(batchId, key -> new ArrayList<>()).addAll(List.of(documents));
    }

    @Override
    public void replace(UUID batchId, List<Document> documents) {
        if (failure != null) {
            RuntimeException toThrow = failure;
            failure = null;
            throw toThrow;
        }
        byBatch.put(batchId, new ArrayList<>(documents));
    }

    @Override
    public List<Document> search(UUID batchId, SearchRequest request) {
        searchedBatches.add(batchId);
        List<String> terms = List.of(request.getQuery().toLowerCase().split("\\W+"));
        return byBatch.getOrDefault(batchId, List.of()).stream()
                .filter(document -> terms.stream().anyMatch(term ->
                        term.length() > 3 && document.getText().toLowerCase().contains(term)))
                .limit(request.getTopK())
                .toList();
    }

    @Override
    public int countFor(UUID batchId) {
        return byBatch.getOrDefault(batchId, List.of()).size();
    }
}
