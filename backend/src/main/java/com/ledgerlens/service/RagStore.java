package com.ledgerlens.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

import java.util.List;
import java.util.UUID;

/**
 * The vector side of Ask, narrowed so a batch cannot be forgotten.
 *
 * <p>Spring AI's {@code VectorStore} takes an optional filter expression, which means an unfiltered
 * search is one omitted call away and compiles fine. Here the batch is a required parameter on every
 * method, so leaking one merchant's rows into another's answer is not something a caller can do by
 * forgetting something — it would have to be written deliberately. The document and request types
 * are Spring AI's own.
 */
public interface RagStore {

    /** Replaces everything indexed for a batch. Re-running reconcile must not double the corpus. */
    void replace(UUID batchId, List<Document> documents);

    /** Top-k for this batch only. Implementations must never widen the search beyond it. */
    List<Document> search(UUID batchId, SearchRequest request);

    /** How many documents a batch currently has, for tests and for the indexer's audit line. */
    int countFor(UUID batchId);
}
