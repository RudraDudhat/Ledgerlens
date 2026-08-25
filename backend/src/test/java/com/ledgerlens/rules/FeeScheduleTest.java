package com.ledgerlens.rules;

import com.ledgerlens.domain.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Expected values here are worked out by hand, not by another implementation, so a mistake in the
 * fee rules cannot cancel itself out against the generator.
 */
class FeeScheduleTest {

    @Test
    void upiIsFreeSoTheNetIsTheGross() {
        BigDecimal amount = new BigDecimal("2499.50");
        BigDecimal fee = FeeSchedule.fee(PaymentMethod.UPI, amount);
        BigDecimal gst = FeeSchedule.gst(fee);

        assertThat(fee).isEqualByComparingTo("0.00");
        assertThat(gst).isEqualByComparingTo("0.00");
        assertThat(FeeSchedule.net(amount, fee, gst)).isEqualByComparingTo("2499.50");
    }

    @Test
    void cardChargesTwoPercentPlusGstOnTheFee() {
        // 1000.00 * 2.0% = 20.00 fee; 20.00 * 18% = 3.60 GST; 1000.00 - 20.00 - 3.60 = 976.40
        BigDecimal amount = new BigDecimal("1000.00");
        BigDecimal fee = FeeSchedule.fee(PaymentMethod.CARD, amount);
        BigDecimal gst = FeeSchedule.gst(fee);

        assertThat(fee).isEqualByComparingTo("20.00");
        assertThat(gst).isEqualByComparingTo("3.60");
        assertThat(FeeSchedule.net(amount, fee, gst)).isEqualByComparingTo("976.40");
    }

    @Test
    void gstIsChargedOnTheRoundedFee() {
        // 2499.50 * 2.0% = 49.99 fee; 49.99 * 18% = 8.9982 -> 9.00 GST; net 2440.51
        BigDecimal amount = new BigDecimal("2499.50");
        BigDecimal fee = FeeSchedule.fee(PaymentMethod.CARD, amount);
        BigDecimal gst = FeeSchedule.gst(fee);

        assertThat(fee).isEqualByComparingTo("49.99");
        assertThat(gst).isEqualByComparingTo("9.00");
        assertThat(FeeSchedule.net(amount, fee, gst)).isEqualByComparingTo("2440.51");
    }

    @Test
    void netbankingChargesOnePointNinePercent() {
        // 1234.56 * 1.9% = 23.45664 -> 23.46 fee; 23.46 * 18% = 4.2228 -> 4.22 GST; net 1206.88
        BigDecimal amount = new BigDecimal("1234.56");
        BigDecimal fee = FeeSchedule.fee(PaymentMethod.NETBANKING, amount);
        BigDecimal gst = FeeSchedule.gst(fee);

        assertThat(fee).isEqualByComparingTo("23.46");
        assertThat(gst).isEqualByComparingTo("4.22");
        assertThat(FeeSchedule.net(amount, fee, gst)).isEqualByComparingTo("1206.88");
    }

    @Test
    void halfPaiseRoundsUp() {
        // 500.25 * 2.0% = 10.005 exactly, which must round up to 10.01 rather than down to 10.00
        BigDecimal amount = new BigDecimal("500.25");
        BigDecimal fee = FeeSchedule.fee(PaymentMethod.WALLET, amount);
        BigDecimal gst = FeeSchedule.gst(fee);

        assertThat(fee).isEqualByComparingTo("10.01");
        assertThat(gst).isEqualByComparingTo("1.80");
        assertThat(FeeSchedule.net(amount, fee, gst)).isEqualByComparingTo("488.44");
    }

    @Test
    void everyFeeAndGstIsRoundedToPaise() {
        for (PaymentMethod method : PaymentMethod.values()) {
            BigDecimal fee = FeeSchedule.fee(method, new BigDecimal("7777.77"));
            assertThat(fee.scale()).isEqualTo(2);
            assertThat(FeeSchedule.gst(fee).scale()).isEqualTo(2);
        }
    }
}
