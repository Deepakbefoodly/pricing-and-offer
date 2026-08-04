package repository;

import entity.Product;
import exception.ProductNotFoundException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ProductRepository {

    private final Map<String, Product> products = new ConcurrentHashMap<>();

    public void addProduct(String productId, double basePrice) {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId must not be null/blank");
        }
        if (basePrice <= 0) {
            throw new IllegalArgumentException("basePrice must be >0, was " + basePrice);
        }

        Product existing = products.putIfAbsent(productId, new Product(productId, basePrice));
        if (existing != null) {
            throw new IllegalArgumentException("Product already exists: " + productId);
        }
    }

    public Product getProduct(String productId) {
        Product product = products.get(productId);

        if (product == null) {
            throw new ProductNotFoundException(productId);
        }

        return product;
    }
}
