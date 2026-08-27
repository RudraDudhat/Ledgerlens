package com.ledgerlens.service;

import com.ledgerlens.dto.BatchMetrics;
import com.ledgerlens.entity.AlertSeverity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The thresholds are the whole product here, so they are checked as arithmetic rather than through
 * the database: a detector that fires at 1.9× or stays silent at 2.1× is worse than no detector.
 */
class AnomalyDetectorTest {

    @Test
    void aBaselineIsTheMiddleValue() {
        assertThat(AnomalyDetector.median(List.of(new BigDecimal("0.10"))))
                .isEqualByComparingTo("0.10");
        assertThat(AnomalyDetector.median(List.of(new BigDecimal("0.10"), new BigDecimal("0.30"))))
                .isEqualByComparingTo("0.20");
        assertThat(AnomalyDetector.median(
                List.of(new BigDecimal("0.30"), new BigDecimal("0.10"), new BigDecimal("0.20"))))
                .isEqualByComparingTo("0.20");
    }

    @Test
    void oneCatastrophicBatchDoesNotDragTheBaselineWithIt() {
        // The mean of these is 0.35, which would hide the next disaster. The median is 0.10.
        List<BigDecimal> values = List.of(new BigDecimal("0.10"), new BigDecimal("0.10"), new BigDecimal("0.95"));

        assertThat(AnomalyDetector.median(values)).isEqualByComparingTo("0.10");
    }

    @Test
    void twoToThreeTimesWarnsAndBeyondThatIsHigh() {
        assertThat(AnomalyDetector.severityOf(new BigDecimal("2.5"))).isEqualTo(AlertSeverity.WARN);
        assertThat(AnomalyDetector.severityOf(new BigDecimal("3.0"))).isEqualTo(AlertSeverity.WARN);
        assertThat(AnomalyDetector.severityOf(new BigDecimal("3.01"))).isEqualTo(AlertSeverity.HIGH);
        assertThat(AnomalyDetector.severityOf(new BigDecimal("8.0"))).isEqualTo(AlertSeverity.HIGH);
    }

    @Test
    void aCollapseAsSevereAsASpikeIsRatedTheSame() {
        // A match rate that fell to a third of its baseline is as bad as one that tripled.
        assertThat(AnomalyDetector.severityOf(new BigDecimal("0.20"))).isEqualTo(AlertSeverity.HIGH);
        assertThat(AnomalyDetector.severityOf(new BigDecimal("0.45"))).isEqualTo(AlertSeverity.WARN);
    }

    @Test
    void theThresholdsThemselvesAreWhatTheSpecSays() {
        assertThat(AnomalyDetector.SPIKE_RATIO).isEqualByComparingTo("2");
        assertThat(AnomalyDetector.HIGH_RATIO).isEqualByComparingTo("3");
        assertThat(AnomalyDetector.HOUR_SPIKE_RATIO).isEqualByComparingTo("3");
        assertThat(AnomalyDetector.MIN_PRIOR_BATCHES).isEqualTo(2);
    }

    @Test
    void anHourIsJudgedAgainstItsOwnBatchNotAgainstHistory() {
        // 21% at 03:00 against a 5% batch is 4.2x — an outage the overall rate barely registers.
        BatchMetrics metrics = metricsWithHour(3, new BigDecimal("0.21"), new BigDecimal("0.05"));
        BigDecimal threshold = metrics.failureRate().multiply(AnomalyDetector.HOUR_SPIKE_RATIO);

        assertThat(metrics.failureRateByHour().get("3")).isGreaterThan(threshold);
        assertThat(metrics.failureRateByHour().get("4")).isLessThan(threshold);
    }

    private static BatchMetrics metricsWithHour(int hour, BigDecimal hourRate, BigDecimal overall) {
        Map<String, BigDecimal> byHour = new LinkedHashMap<>();
        for (int i = 0; i < 24; i++) {
            byHour.put(String.valueOf(i), i == hour ? hourRate : overall);
        }
        return new BatchMetrics(new BigDecimal("0.01"), overall, Map.of(), byHour, new BigDecimal("0.03"),
                new BigDecimal("0.92"), Map.of(), new BigDecimal("1.5"), 300);
    }
}
