package entity;

public class Product {

    private final String productId;
    private final double basePrice;

    public Product(String productId, double basePrice) {
        this.productId = productId;
        this.basePrice = basePrice;
    }

    public String getProductId() {
        return productId;
    }

    public double getBasePrice() {
        return basePrice;
    }
}
