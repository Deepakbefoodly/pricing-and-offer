package model;

public record OfferEvaluationDetail(
        String offerId,
        double discountIfApplied,
        boolean valid,
        String invalidReason
) {
}
