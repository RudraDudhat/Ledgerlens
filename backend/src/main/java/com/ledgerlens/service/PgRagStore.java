package com.ledgerlens.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link RagStore} over the {@code rag_documents} table.
 *
 * <p>Written against JDBC rather than Spring AI's {@code PgVectorStore} because that store owns its
 * own table shape — id, content, metadata, embedding — and this one carries {@code batch_id} as a
 * real foreign-keyed column, not just a metadata key. The distinction matters: a metadata filter is
 * a string the query builder has to get right, while a column with a foreign key is enforced by the
 * database and can be re-checked after retrieval.
 */
@Service
@ConditionalOnProperty(name = "ledgerlens.rag.enabled", havingValue = "true", matchIfMissing = true)
public class PgRagStore implements RagStore {

    /** Gemini rejects a batch embedding request carrying more than this many texts. */
    private static final int EMBED_BATCH = 100;

    private final JdbcTemplate jdbc;
    private final ObjectProvider<EmbeddingModel> embeddingModels;

    public PgRagStore(JdbcTemplate jdbc, ObjectProvider<EmbeddingModel> embeddingModels) {
        this.jdbc = jdbc;
        this.embeddingModels = embeddingModels;
    }

    @Override
    public void replace(UUID batchId, List<Document> documents) {
        jdbc.update("DELETE FROM rag_documents WHERE batch_id = ?", batchId);
        if (documents.isEmpty()) {
            return;
        }
        EmbeddingModel model = requireEmbeddingModel();
        List<float[]> vectors = embedInBatches(model, documents.stream().map(Document::getText).toList());

        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            Map<String, Object> metadata = document.getMetadata();
            jdbc.update("""
                            INSERT INTO rag_documents (id, batch_id, record_id, record_type, content, embedding, metadata)
                            VALUES (?, ?, ?, ?, ?, CAST(? AS vector), CAST(? AS jsonb))
                            """,
                    UUID.fromString(document.getId()),
                    batchId,
                    String.valueOf(metadata.getOrDefault("recordId", "")),
                    String.valueOf(metadata.getOrDefault("recordType", "")),
                    document.getText(),
                    literal(vectors.get(index)),
                    toJson(metadata));
        }
    }

    @Override
    public List<Document> search(UUID batchId, SearchRequest request) {
        EmbeddingModel model = requireEmbeddingModel();
        String query = literal(model.embed(request.getQuery()));

        // The batch predicate is in the SQL itself, not in a filter expression assembled elsewhere.
        return jdbc.query("""
                        SELECT id, record_id, record_type, content, batch_id
                        FROM rag_documents
                        WHERE batch_id = ?
                        ORDER BY embedding <=> CAST(? AS vector)
                        LIMIT ?
                        """,
                (rs, row) -> new Document(
                        rs.getString("id"),
                        rs.getString("content"),
                        new LinkedHashMap<>(Map.of(
                                "recordId", rs.getString("record_id"),
                                "recordType", rs.getString("record_type"),
                                RagIndexer.BATCH_KEY, rs.getString("batch_id")))),
                batchId, query, request.getTopK());
    }

    @Override
    public int countFor(UUID batchId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM rag_documents WHERE batch_id = ?", Integer.class, batchId);
        return count == null ? 0 : count;
    }

    /**
     * Embeds in chunks, because the provider caps a batch request.
     *
     * <p>Gemini rejects a BatchEmbedContents call carrying more than a hundred texts, and a
     * three-hundred-order batch produces several times that. Sending them all at once failed the
     * whole index for one oversized request — silently, since the indexer swallows what it cannot do.
     */
    private static List<float[]> embedInBatches(EmbeddingModel model, List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += EMBED_BATCH) {
            vectors.addAll(model.embed(texts.subList(start, Math.min(start + EMBED_BATCH, texts.size()))));
        }
        return vectors;
    }

    private EmbeddingModel requireEmbeddingModel() {
        EmbeddingModel model = embeddingModels.getIfAvailable();
        if (model == null) {
            // Caller decides what to do; indexing swallows it, retrieval degrades to SQL only.
            throw new IllegalStateException("no embedding model is configured; set GEMINI_API_KEY to enable retrieval");
        }
        return model;
    }

    private static String literal(float[] embedding) {
        List<String> parts = new ArrayList<>(embedding.length);
        for (float value : embedding) {
            parts.add(Float.toString(value));
        }
        return "[" + String.join(",", parts) + "]";
    }

    private static String toJson(Map<String, Object> metadata) {
        return metadata.entrySet().stream()
                .map(entry -> "\"%s\":\"%s\"".formatted(escape(entry.getKey()), escape(String.valueOf(entry.getValue()))))
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
