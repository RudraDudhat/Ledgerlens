package com.ledgerlens.service;

import com.ledgerlens.dto.AskResponse;
import com.ledgerlens.dto.Citation;
import com.ledgerlens.dto.ModelAnswer;
import com.ledgerlens.entity.BankEntry;
import com.ledgerlens.entity.ExceptionRecord;
import com.ledgerlens.entity.MerchantOrder;
import com.ledgerlens.entity.SettlementBatch;
import com.ledgerlens.repository.BankEntryRepository;
import com.ledgerlens.repository.ExceptionRecordRepository;
import com.ledgerlens.repository.IngestBatchRepository;
import com.ledgerlens.repository.MerchantOrderRepository;
import com.ledgerlens.repository.SettlementBatchRepository;
import com.ledgerlens.rules.StatusGlossary;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers questions about one batch from rows it actually retrieved.
 *
 * <p>Retrieval is by query, not by similarity: whatever order ids, dates and amounts the question
 * mentions are looked up directly, capped at twenty rows. If nothing comes back the model is never
 * called at all — an ungrounded answer is worse than no answer, so the refusal is returned instead.
 */
@Service
public class QuestionAnswerer {

    public static final String NOTHING_FOUND =
            "I could not find any rows in this batch that relate to that question, so I have nothing to answer from.";

    private static final int MAX_ROWS = 20;
    private static final Pattern ORDER_ID = Pattern.compile("\\bORD-[A-Za-z0-9-]+\\b");
    private static final Pattern UTR = Pattern.compile("\\bUTR[A-Za-z0-9]+\\b");
    private static final Pattern DATE = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
    private static final Pattern AMOUNT = Pattern.compile("(?<![\\d-])\\d+(?:\\.\\d{1,2})?(?![\\d-])");

    private static final Pattern DEFINITIONAL = Pattern.compile(
            "\\b(what is|what are|what does|what do|explain|define|definition of|difference between|meaning of)\\b");

    /**
     * Words that make a question about <em>their</em> data, however definitional its opening sounds.
     *
     * <p>Matching whole phrases was too brittle. "Explain the reconciliation for the current batch"
     * slipped past a "the batch" pattern because of the word sitting between them, and was answered
     * out of a glossary when it was a question about their rows. Single words are the safer failure:
     * sending a definition down the hybrid path costs one lookup, while sending a question about
     * their data to the glossary costs the answer.
     */
    private static final Pattern BATCH_REFERENCE = Pattern.compile(
            "\\b(batch|batches|current|this|these|those|my|our|we|us|mine|here)\\b");

    private static final Pattern DOMAIN_TERM = Pattern.compile(
            "\\b(reconciliation|reconcile|settlement|utr|dispute|disputed|chargeback|hold|held|refund|gst|mdr"
                    + "|fee|fees|match rate|waterfall|payout|matched|mismatch|duplicate|unknown)\\b");

    private final LlmGateway llm;
    private final IngestBatchRepository ingestBatchRepository;
    private final MerchantOrderRepository orderRepository;
    private final SettlementBatchRepository settlementBatchRepository;
    private final BankEntryRepository bankEntryRepository;
    private final ExceptionRecordRepository exceptionRepository;
    private final RagRetriever ragRetriever;
    private final String template;
    private final String glossaryTemplate;

    public QuestionAnswerer(LlmGateway llm,
                            IngestBatchRepository ingestBatchRepository,
                            MerchantOrderRepository orderRepository,
                            SettlementBatchRepository settlementBatchRepository,
                            BankEntryRepository bankEntryRepository,
                            ExceptionRecordRepository exceptionRepository,
                            RagRetriever ragRetriever) {
        this.llm = llm;
        this.ingestBatchRepository = ingestBatchRepository;
        this.orderRepository = orderRepository;
        this.settlementBatchRepository = settlementBatchRepository;
        this.bankEntryRepository = bankEntryRepository;
        this.exceptionRepository = exceptionRepository;
        this.ragRetriever = ragRetriever;
        this.template = LlmGateway.loadPrompt("question-answerer.txt");
        this.glossaryTemplate = LlmGateway.loadPrompt("glossary.txt");
    }

    @Transactional
    public AskResponse ask(UUID batchId, String question) {
        if (!ingestBatchRepository.existsById(batchId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown batch " + batchId);
        }

        // Checked first, and it neither queries nor searches: "what is a chargeback" has no answer
        // in anyone's rows, and looking for one only produces a refusal to a fair question.
        if (isConceptual(question)) {
            String prompt = glossaryTemplate
                    .replace("{{glossary}}", StatusGlossary.rendered())
                    .replace("{{question}}", question);
            return AskResponse.conceptual(llm.complete(batchId, "LLM_ASK_CONCEPT", prompt).trim());
        }

        Map<Long, Retrieved> rows = retrieve(batchId, question);
        // A question naming a record has something exact to look up, so it keeps the SQL-only path
        // it has always had. Similarity search would only add noise to an answer already grounded.
        if (!hasExactAnchor(question)) {
            addExcerpts(rows, ragRetriever.search(question, batchId));
        }
        if (rows.isEmpty()) {
            return AskResponse.factual(NOTHING_FOUND, List.of(), List.of());
        }

        String rendered = rows.entrySet().stream()
                .map(row -> "[%d] %s".formatted(row.getKey(), row.getValue().rendered()))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        String prompt = template.replace("{{question}}", question).replace("{{rows}}", rendered);
        ModelAnswer answer = llm.completeAs(batchId, "LLM_ASK", prompt, ModelAnswer.class);

        if (answer == null || answer.answer() == null || answer.answer().isBlank()) {
            return AskResponse.factual(NOTHING_FOUND, List.of(), List.of());
        }
        // Only ids that were actually handed over survive: the model cannot cite a row it never saw.
        List<Long> cited = answer.citedRowIds() == null
                ? List.of()
                : answer.citedRowIds().stream().filter(rows::containsKey).toList();
        List<Citation> citations = cited.stream().map(id -> rows.get(id).citation()).toList();
        return AskResponse.factual(answer.answer().trim(), cited, citations);
    }

    /** One retrieved row, in both the form the model reads and the form a person reads. */
    private record Retrieved(String rendered, Citation citation) {
    }

    /**
     * True when the question names a specific record, so an exact lookup can answer it.
     *
     * <p>This is the switch that keeps today's behaviour intact: anything matching here takes the
     * path it took before this feature existed, byte for byte.
     */
    public static boolean hasExactAnchor(String question) {
        return ORDER_ID.matcher(question).find()
                || UTR.matcher(question).find()
                || DATE.matcher(question).find()
                || AMOUNT.matcher(question).find()
                || question.contains("₹");
    }

    /**
     * True when the question asks what a term means rather than what happened.
     *
     * <p>All three conditions are required. A definitional phrase alone is not enough — "explain the
     * mismatch in this batch" is a question about their data that happens to start with "explain",
     * and answering it from a glossary would be a non-answer dressed as one.
     */
    public static boolean isConceptual(String question) {
        String lower = question.toLowerCase(java.util.Locale.ROOT);
        return DEFINITIONAL.matcher(lower).find()
                && DOMAIN_TERM.matcher(lower).find()
                && !BATCH_REFERENCE.matcher(lower).find()
                && !hasExactAnchor(question);
    }

    /** Similarity hits joined to the exact rows, marked so the model can tell them apart. */
    private static void addExcerpts(Map<Long, Retrieved> rows, List<Document> hits) {
        long excerptId = -1_000L;
        for (Document hit : hits) {
            String recordId = String.valueOf(hit.getMetadata().getOrDefault("recordId", ""));
            String recordType = String.valueOf(hit.getMetadata().getOrDefault("recordType", "EXCERPT"));
            rows.put(excerptId--, new Retrieved(
                    "[excerpt] " + hit.getText().replace('\n', ' '),
                    new Citation(excerptId + 1, recordType, recordId, null, null, null)));
        }
    }

    /** Looks up exactly what the question names, so every row offered can be traced back to it. */
    private Map<Long, Retrieved> retrieve(UUID batchId, String question) {
        Map<Long, Retrieved> rows = new LinkedHashMap<>();

        for (String orderId : matches(ORDER_ID, question)) {
            orderRepository.findByBatchIdAndOrderId(batchId, orderId)
                    .forEach(order -> rows.put(order.getId(), describe(order)));
            exceptionRepository.findByBatchIdAndEntityRef(batchId, orderId)
                    .forEach(record -> rows.put(record.getId(), describe(record)));
        }
        for (String utr : matches(UTR, question)) {
            settlementBatchRepository.findByBatchIdAndUtr(batchId, utr)
                    .forEach(settlement -> rows.put(settlement.getId(), describe(settlement)));
            exceptionRepository.findByBatchIdAndEntityRef(batchId, utr)
                    .forEach(record -> rows.put(record.getId(), describe(record)));
        }
        for (String text : matches(DATE, question)) {
            LocalDate date = parseDate(text);
            if (date == null) {
                continue;
            }
            settlementBatchRepository.findByBatchIdAndSettledOn(batchId, date)
                    .forEach(settlement -> rows.put(settlement.getId(), describe(settlement)));
            bankEntryRepository.findByBatchIdAndEntryDate(batchId, date)
                    .forEach(entry -> rows.put(entry.getId(), describe(entry)));
        }
        for (String text : matches(AMOUNT, question)) {
            BigDecimal amount = new BigDecimal(text).setScale(2, java.math.RoundingMode.HALF_UP);
            orderRepository.findByBatchIdAndAmount(batchId, amount)
                    .forEach(order -> rows.put(order.getId(), describe(order)));
            bankEntryRepository.findByBatchIdAndAmount(batchId, amount)
                    .forEach(entry -> rows.put(entry.getId(), describe(entry)));
        }

        return rows.size() <= MAX_ROWS
                ? rows
                : rows.entrySet().stream().limit(MAX_ROWS)
                        .collect(LinkedHashMap::new, (map, e) -> map.put(e.getKey(), e.getValue()), Map::putAll);
    }

    private static List<String> matches(Pattern pattern, String question) {
        List<String> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(question);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    private static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // The rendered halves are unchanged: they are what the model has always been shown, and the
    // citation beside each one only names the same row the way the merchant's own records do.

    private static Retrieved describe(MerchantOrder order) {
        return new Retrieved(
                "order orderId=%s placedAt=%s amount=%s"
                        .formatted(order.getOrderId(), order.getOrderTs(), order.getAmount()),
                new Citation(order.getId(), "ORDER", order.getOrderId(), order.getAmount(),
                        order.getOrderTs() == null ? null : order.getOrderTs().toLocalDate(), null));
    }

    private static Retrieved describe(SettlementBatch settlement) {
        return new Retrieved(
                "settlement utr=%s settledOn=%s amount=%s"
                        .formatted(settlement.getUtr(), settlement.getSettledOn(), settlement.getAmount()),
                new Citation(settlement.getId(), "SETTLEMENT", settlement.getUtr(), settlement.getAmount(),
                        settlement.getSettledOn(), null));
    }

    private static Retrieved describe(BankEntry entry) {
        return new Retrieved(
                "bankCredit date=%s utr=%s amount=%s narration=%s"
                        .formatted(entry.getEntryDate(), entry.getUtr(), entry.getAmount(), entry.getDescription()),
                new Citation(entry.getId(), "BANK_CREDIT", entry.getUtr(), entry.getAmount(),
                        entry.getEntryDate(), entry.getDescription()));
    }

    private static Retrieved describe(ExceptionRecord record) {
        return new Retrieved(
                "exception status=%s entity=%s confidence=%s reason=%s"
                        .formatted(record.getStatus(), record.getEntityRef(), record.getConfidence(),
                                record.getReason()),
                new Citation(record.getId(), "EXCEPTION", record.getEntityRef(), null, null,
                        "%s — %s".formatted(record.getStatus(), record.getReason())));
    }
}
