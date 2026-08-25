package com.ledgerlens.rules;

import com.ledgerlens.entity.PaymentMethod;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Razorpay fee and GST arithmetic. Fee is rounded to paise first, then GST is charged on the
 * rounded fee — not on the gross amount and not on the unrounded fee.
 */
public final class FeeSchedule {

    private static final BigDecimal GST_RATE = new BigDecimal("0.18");

    private FeeSchedule() {
    }

    public static BigDecimal feeRate(PaymentMethod method) {
        return switch (method) {
            case UPI -> new BigDecimal("0.000");
            case CARD -> new BigDecimal("0.020");
            case NETBANKING -> new BigDecimal("0.019");
            case WALLET -> new BigDecimal("0.020");
        };
    }

    public static BigDecimal fee(PaymentMethod method, BigDecimal amount) {
        return amount.multiply(feeRate(method)).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal gst(BigDecimal fee) {
        return fee.multiply(GST_RATE).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal net(BigDecimal amount, BigDecimal fee, BigDecimal gst) {
        return amount.subtract(fee).subtract(gst).setScale(2, RoundingMode.HALF_UP);
    }
}
