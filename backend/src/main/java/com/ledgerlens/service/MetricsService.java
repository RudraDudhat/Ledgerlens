package com.ledgerlens.service;

import com.ledgerlens.dto.AnswerKey;
import com.ledgerlens.dto.MetricsReport;
import com.ledgerlens.entity.ExceptionRecord;
import com.ledgerlens.entity.ExceptionStatus;
import com.ledgerlens.repository.ExceptionRecordRepository;
import com.ledgerlens.repository.IngestBatchRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Scores the detected exceptions against the anomalies the generator injected.
 *
 * <p>Predictions and ground truth are joined on the entity reference — an order id for order-level
 * findings, a UTR for bank-level ones — so a finding of the right type against the wrong record
 * counts as both a false positive and a false negative rather than quietly passing.
 */
@Service
public class MetricsService {

    private static final BigDecimal ZERO = new BigDecimal("0.0000");
    private static final BigDecimal ONE = new BigDecimal("1.0000");

    private final IngestBatchRepository ingestBatchRepository;
    private final ExceptionRecordRepository exceptionRepository;
    private final String answerKeyPath;

    public MetricsService(IngestBatchRepository ingestBatchRepository,
                          ExceptionRecordRepository exceptionRepository,
                          @Value("${ledgerlens.answer-key-path:data/answer_key.json}") String answerKeyPath) {
        this.ingestBatchRepository = ingestBatchRepository;
        this.exceptionRepository = exceptionRepository;
        this.answerKeyPath = answerKeyPath;
    }

    @Transactional(readOnly = true)
    public MetricsReport metrics(UUID batchId) {
        if (!ingestBatchRepository.existsById(batchId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown batch " + batchId);
        }
        List<ExceptionRecord> detected = exceptionRepository.findByBatchIdOrderById(batchId);

        Path path = Path.of(answerKeyPath);
        if (!Files.isRegularFile(path)) {
            return new MetricsReport(batchId, false, path.toString(), detected.size(), 0, Map.of(), null);
        }
        AnswerKey answerKey = readAnswerKey(path);

        Map<ExceptionStatus, Set<String>> predicted = new HashMap<>();
        detected.forEach(record -> predicted
                .computeIfAbsent(record.getStatus(), status -> new HashSet<>())
                .add(record.getEntityRef()));

        Map<ExceptionStatus, Set<String>> expected = new HashMap<>();
        answerKey.anomalies().forEach(anomaly -> expected
                .computeIfAbsent(anomaly.type(), status -> new HashSet<>())
                .add(anomaly.orderId() != null ? anomaly.orderId() : anomaly.utr()));

        Map<String, MetricsReport.TypeMetrics> byType = new LinkedHashMap<>();
        int truePositives = 0;
        int falsePositives = 0;
        int falseNegatives = 0;
        for (ExceptionStatus status : ExceptionStatus.values()) {
            if (status == ExceptionStatus.MATCHED) {
                continue;
            }
            Set<String> predictedRefs = predicted.getOrDefault(status, Set.of());
            Set<String> expectedRefs = expected.getOrDefault(status, Set.of());
            int hits = (int) predictedRefs.stream().filter(expectedRefs::contains).count();
            int misfires = predictedRefs.size() - hits;
            int missed = expectedRefs.size() - hits;
            truePositives += hits;
            falsePositives += misfires;
            falseNegatives += missed;
            byType.put(status.name(), score(hits, misfires, missed));
        }

        return new MetricsReport(batchId, true, path.toString(), detected.size(),
                answerKey.anomalies().size(), byType, score(truePositives, falsePositives, falseNegatives));
    }

    private static MetricsReport.TypeMetrics score(int truePositives, int falsePositives, int falseNegatives) {
        BigDecimal precision = ratio(truePositives, truePositives + falsePositives, falseNegatives == 0);
        BigDecimal recall = ratio(truePositives, truePositives + falseNegatives, falsePositives == 0);
        BigDecimal f1 = precision.signum() == 0 && recall.signum() == 0
                ? ZERO
                : precision.multiply(recall).multiply(BigDecimal.TWO)
                        .divide(precision.add(recall), 4, RoundingMode.HALF_UP);
        return new MetricsReport.TypeMetrics(truePositives, falsePositives, falseNegatives, precision, recall, f1);
    }

    /** With nothing to score, a status is perfect only if nothing was missed either. */
    private static BigDecimal ratio(int hits, int total, boolean vacuouslyPerfect) {
        if (total == 0) {
            return vacuouslyPerfect ? ONE : ZERO;
        }
        return BigDecimal.valueOf(hits).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    private static AnswerKey readAnswerKey(Path path) {
        try {
            return SyntheticDataWriter.answerKeyMapper().readValue(path.toFile(), AnswerKey.class);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read answer key at " + path, e);
        }
    }
}
