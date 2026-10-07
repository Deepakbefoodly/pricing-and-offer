package store.repository;

import org.springframework.stereotype.Repository;
import store.domain.Product;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory product store. Products are immutable, so readers always see a whole snapshot;
 * multi-step read-check-write sequences are made atomic by {@link store.service.StoreLock}, not here.
 */
@Repository
public class ProductRepository {

    private final Map<String, Product> products = new ConcurrentHashMap<>();

    public void add(Product product) {
        if (products.putIfAbsent(product.id(), product) != null) {
            throw new IllegalArgumentException("Product already exists: " + product.id());
        }
    }

    public void replace(Product product) {
        if (products.replace(product.id(), product) == null) {
            throw new IllegalArgumentException("Product does not exist: " + product.id());
        }
    }

    public Optional<Product> findById(String id) {
        return Optional.ofNullable(products.get(id));
    }

    public List<Product> findAll() {
        return products.values().stream()
                .sorted(Comparator.comparing(Product::name))
                .toList();
    }
}
