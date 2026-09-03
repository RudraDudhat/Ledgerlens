package com.ledgerlens.service;

import com.ledgerlens.dto.ExceptionView;
import com.ledgerlens.dto.MatchView;
import com.ledgerlens.rules.StatusGlossary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a reconciled batch into text worth searching.
 *
 * <p>Two kinds of document and nothing else: one per exception, and one per matched order. Raw bank
 * rows and individual settlement lines are deliberately left out — they carry no sentence a question
 * could match, and indexing them would bury the findings that matter under thousands of near
 * identical rows.
 *
 * <p>Indexing runs on its own thread once reconcile has committed, and is not allowed to break it.
 * Embedding several hundred documents takes far longer than the reconciliation itself, and a
 * merchant waiting on a POST should not be paying for search they have not asked for yet. A batch
 * that failed to index reconciles exactly as it would have; the only thing lost is fuzzy search over
 * it, and the exact-lookup path never touched this table.
 *
 * <p>The consequence to know about: for a few seconds after a large reconcile, a question with no
 * exact anchor finds nothing. It refuses rather than answering from a half-built index.
 */
@Service
public class RagIndexer {

    /** Metadata key holding the batch, mirroring the column. Both are written, both are checked. */
    public static final String BATCH_KEY = "batchId";

    private static final Logger log = LoggerFactory.getLogger(RagIndexer.class);


    private final ObjectProvider<RagStore> stores;
    // Lazily resolved: reconcile calls the indexer, so injecting it directly would be a cycle.
    private final ObjectProvider<ReconciliationService> reconciliation;
    private final boolean enabled;
    private final int maxDocuments;

    public RagIndexer(ObjectProvider<RagStore> stores,
                      ObjectProvider<ReconciliationService> reconciliation,
                      @Value("${ledgerlens.rag.enabled:true}") boolean enabled,
                      @Value("${ledgerlens.rag.max-documents:90}") int maxDocuments) {
        this.stores = stores;
        this.reconciliation = reconciliation;
        this.enabled = enabled;
        this.maxDocuments = maxDocuments;
    }

    /**
     * Re-indexes a batch, replacing anything indexed for it before.
     *
     * <p>Never throws. A reconciliation that succeeded must not be reported as failed because an
     * embedding call timed out.
     */
    @Async
    public void index(UUID batchId) {
        RagStore store = enabled ? stores.getIfAvailable() : null;
        if (store == null) {
            return;
        }
        try {
            List<Document> documents = new ArrayList<>();
            ReconciliationService service = reconciliation.getObject();
            // Exceptions first, always. They are what a question with no exact anchor is asking
            // about — the suspicious records, the problems, the money that did not arrive — and if
            // the budget only stretches to one kind of document, it should stretch to these.
            service.exceptions(batchId).forEach(view -> documents.add(exceptionDocument(batchId, view)));

            int remaining = Math.max(0, maxDocuments - documents.size());
            if (remaining > 0) {
                service.matches(batchId, PageRequest.of(0, remaining)).getContent()
                        .forEach(view -> documents.add(matchDocument(batchId, view)));
            }

            store.replace(batchId, documents);
            log.info("indexed {} documents for batch {}", documents.size(), batchId);
        } catch (RuntimeException e) {
            log.warn("could not index batch {} for search; reconciliation is unaffected", batchId, e);
        }
    }

    private static Document exceptionDocument(UUID batchId, ExceptionView view) {
        String content = """
                Exception %s on %s. %s Reason: %s Confidence %s, decided by %s. Amount %s. Evidence rows: %s.
                """.formatted(
                view.status(),
                view.entityRef(),
                StatusGlossary.meaningOf(view.status()),
                view.reason(),
                view.confidence(),
                view.origin().toLowerCase(java.util.Locale.ROOT),
                view.amount() == null ? "not resolved" : view.amount().toPlainString(),
                view.sourceRowIds()).trim();
        return new Document(UUID.randomUUID().toString(), content,
                metadata(batchId, view.entityRef(), "EXCEPTION"));
    }

    private static Document matchDocument(UUID batchId, MatchView view) {
        String content = """
                Order %s for %s by %s. Settlement %s on %s. Bank credit %s on %s. Match type %s.
                """.formatted(
                view.orderId() == null ? "unknown" : view.orderId(),
                view.amount() == null ? "unknown amount" : view.amount().toPlainString(),
                view.method() == null ? "unknown method" : view.method(),
                view.utr() == null ? "none" : view.utr(),
                view.settledOn() == null ? "not settled" : view.settledOn(),
                view.bankAmount() == null ? "none" : view.bankAmount().toPlainString(),
                view.bankDate() == null ? "not credited" : view.bankDate(),
                view.matchType()).trim();
        return new Document(UUID.randomUUID().toString(), content,
                metadata(batchId, view.orderId() == null ? String.valueOf(view.id()) : view.orderId(), "MATCH"));
    }

    private static Map<String, Object> metadata(UUID batchId, String recordId, String recordType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(BATCH_KEY, batchId.toString());
        metadata.put("recordId", recordId);
        metadata.put("recordType", recordType);
        return metadata;
    }
}
