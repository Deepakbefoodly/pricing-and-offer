package factory;

import entity.Offer;
import strategy.FlatDiscountStrategy;
import strategy.PercentageDiscountStrategy;

import java.util.Set;

public class OfferFactory {

    private OfferFactory() {}

    public static Offer createPercentageOffer(String offerId, Set<String> productIds, double discountPercentage,
                                              int minQuantity, int maxGlobalUsage) {
        validateInput(offerId, productIds, minQuantity, maxGlobalUsage);
        if (discountPercentage <= 0 || discountPercentage > 100) {
            throw new IllegalArgumentException("discountPercentage must be in range of (0, 100], was" + discountPercentage);
        }

        return new Offer(offerId, productIds, minQuantity, maxGlobalUsage, new PercentageDiscountStrategy(discountPercentage));
    }

    public static Offer createFlatOffer(String offerId, Set<String> productIds, double discountAmountPerUnit,
                                              int minQuantity, int maxGlobalUsage) {
        validateInput(offerId, productIds, minQuantity, maxGlobalUsage);
        if (discountAmountPerUnit <= 0) {
            throw new IllegalArgumentException("discountAmountPerUnit must be > 0, was" + discountAmountPerUnit);
        }

        return new Offer(offerId, productIds, minQuantity, maxGlobalUsage, new FlatDiscountStrategy(discountAmountPerUnit));
    }

    private static void validateInput(String offerId, Set<String> productIds, int minQuantity, int maxGlobalUsage) {
        if (offerId == null || offerId.isBlank()) {
            throw new IllegalArgumentException("offerId must not be null/blank");
        }
        if (productIds == null || productIds.isEmpty()) {
            throw new IllegalArgumentException("productIds must not be empty");
        }
        if (minQuantity < 1) {
            throw new IllegalArgumentException("minQuantity must be >= 1");
        }
        if (maxGlobalUsage < 1) {
            throw new IllegalArgumentException("maxGlobalUsage must be >= 1");
        }
    }
}
