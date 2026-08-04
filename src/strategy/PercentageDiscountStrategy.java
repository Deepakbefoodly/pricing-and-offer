package strategy;

public class PercentageDiscountStrategy implements DiscountStrategy {

    private final double percentage;

    public PercentageDiscountStrategy(double percentage) {
        this.percentage = percentage;
    }

    @Override
    public double calculateDiscount(double basePrice, int quantity) {
        return basePrice * quantity * (percentage / 100.0);
    }
}
