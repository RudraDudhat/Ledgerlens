package com.ledgerlens.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Fuzzy retrieval for questions with nothing exact to look up.
 *
 * <p>Two guards, deliberately belt and braces. The store filters by batch in SQL, and then every hit
 * is checked again here against the batch it claims in its metadata. One merchant's rows appearing
 * in another's answer is the failure this feature must not have, and a single filter is one bug away
 * from not being applied.
 *
 * <p>Returns an empty list rather than throwing when anything goes wrong. The caller falls back to
 * the SQL path, which is what Ask did before this existed.
 */
@Service
public class RagRetriever {

    public static final int TOP_K = 5;

    private static final Logger log = LoggerFactory.getLogger(RagRetriever.class);

    private final ObjectProvider<RagStore> stores;
    private final boolean enabled;

    public RagRetriever(ObjectProvider<RagStore> stores, @Value("${ledgerlens.rag.enabled:true}") boolean enabled) {
        this.stores = stores;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled && stores.getIfAvailable() != null;
    }

    public List<Document> search(String question, UUID batchId) {
        RagStore store = enabled ? stores.getIfAvailable() : null;
        if (store == null || question == null || question.isBlank()) {
            return List.of();
        }
        try {
            List<Document> hits = store.search(batchId,
                    SearchRequest.builder().query(question).topK(TOP_K).build());
            return hits.stream().filter(hit -> belongsTo(hit, batchId)).toList();
        } catch (RuntimeException e) {
            log.warn("vector search failed for batch {}; falling back to the exact-lookup path", batchId, e);
            return List.of();
        }
    }

    /** Defence in depth: a hit that cannot prove it belongs to this batch is dropped, loudly. */
    private static boolean belongsTo(Document hit, UUID batchId) {
        Object claimed = hit.getMetadata().get(RagIndexer.BATCH_KEY);
        boolean ours = batchId.toString().equals(String.valueOf(claimed));
        if (!ours) {
            log.warn("dropped a search hit belonging to batch {} while answering for batch {}", claimed, batchId);
        }
        return ours;
    }
}
