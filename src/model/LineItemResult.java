package model;

import java.util.List;

public record LineItemResult(
        String productId,
        int quantity,
        double basePrice,
        List<OfferEvaluationDetail> consideredOffers,
        String appliedOfferId,
        double discount,
        double finalPrice
) {
}
