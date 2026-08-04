package entity;

import strategy.DiscountStrategy;

import java.util.Set;

public class Offer {

    private final String offerId;
    private final Set<String> productIds;
    private final int minQuantity;
    private final int maxGlobalUsage;
    private final DiscountStrategy discountStrategy;
    private int currentUsage = 0;

    public Offer(String offerId, Set<String> productIds, int minQuantity, int maxGlobalUsage,
                 DiscountStrategy discountStrategy) {
        this.offerId = offerId;
        this.productIds = productIds;
        this.maxGlobalUsage = maxGlobalUsage;
        this.minQuantity = minQuantity;
        this.discountStrategy = discountStrategy;
    }

    public boolean appliesTo(String productId) {
        return productIds.contains(productId);
    }

    public boolean meetsMinQuantity(int quantity) {
        return quantity >= minQuantity;
    }

    public synchronized boolean hasRemainingUsage() {
        return currentUsage < maxGlobalUsage;
    }

    public synchronized boolean tryRedeem() {
        if (currentUsage < maxGlobalUsage) {
            currentUsage++;
            return true;
        }
        return false;
    }

    // returns total discount
    public double calculateDiscount(double basePrice, int quantity) {
        return discountStrategy.calculateDiscount(basePrice, quantity);
    }

    public int getMinQuantity() {
        return minQuantity;
    }

    public String getOfferId() {
        return offerId;
    }

    public Set<String> getProductIds() {
        return productIds;
    }
}
