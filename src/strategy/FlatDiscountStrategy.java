package strategy;

public class FlatDiscountStrategy implements DiscountStrategy {

    private final double flatAmountDiscount;

    // assuming flat amount discount per unit of product
    public FlatDiscountStrategy(double flatAmountDiscount) {
        this.flatAmountDiscount = flatAmountDiscount;
    }

    @Override
    public double calculateDiscount(double basePrice, int quantity) {
        return flatAmountDiscount * quantity;
    }
}
