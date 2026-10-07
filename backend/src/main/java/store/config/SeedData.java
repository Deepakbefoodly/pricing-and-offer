package store.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import store.domain.Product;
import store.repository.ProductRepository;

/** Loads the demo catalogue at startup. Disable with {@code store.seed.enabled=false}. */
@Component
@ConditionalOnProperty(prefix = "store.seed", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SeedData implements ApplicationRunner {

    private final ProductRepository products;

    public SeedData(ProductRepository products) {
        this.products = products;
    }

    @Override
    public void run(ApplicationArguments args) {
        products.add(Product.create("shirt", "Shirt", "25.00", 100));
        products.add(Product.create("jeans", "Jeans", "49.99", 50));
        products.add(Product.create("shoes", "Shoes", "89.90", 30));
        products.add(Product.create("socks", "Socks", "5.49", 200));
        products.add(Product.create("watch", "Limited Watch", "199.00", 3));   // limited inventory
        products.add(Product.create("hoodie", "Hoodie", "39.00", 0));          // sold out
    }
}
