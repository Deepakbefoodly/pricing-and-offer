package store.service;

import org.springframework.stereotype.Service;
import store.domain.Money;
import store.domain.Product;
import store.error.ApiException;
import store.error.ErrorCode;
import store.repository.ProductRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class CatalogService {

    static final int MAX_STOCK = 1_000_000;

    private final ProductRepository products;
    private final StoreLock lock;

    public CatalogService(ProductRepository products, StoreLock lock) {
        this.products = products;
        this.lock = lock;
    }

    public List<Product> list() {
        return lock.read(products::findAll);
    }

    public Product get(String productId) {
        return lock.read(() -> require(productId));
    }

    /**
     * Admin edit of price and/or stock. {@code expectedVersion} must match the stored product, so an edit
     * based on a stale view (e.g. stock read before a sale) is rejected instead of overwriting the sale.
     *
     * @param unitPrice    new price as a decimal string, or null to keep the current price
     * @param availableQty new absolute stock level, or null to keep the current stock
     */
    public Product update(String productId, long expectedVersion, String unitPrice, Integer availableQty) {
        if (unitPrice == null && availableQty == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Provide unitPrice and/or availableQty to update",
                    Map.of("fields", Map.of("unitPrice", "required if availableQty is absent",
                            "availableQty", "required if unitPrice is absent")));
        }
        BigDecimal newPrice = unitPrice == null ? null : parsePositivePrice(unitPrice);
        if (availableQty != null && (availableQty < 0 || availableQty > MAX_STOCK)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Invalid stock level",
                    Map.of("fields", Map.of("availableQty", "must be between 0 and " + MAX_STOCK)));
        }

        return lock.write(() -> {
            Product current = require(productId);
            if (current.version() != expectedVersion) {
                throw new ApiException(ErrorCode.PRODUCT_MODIFIED,
                        "Product '" + productId + "' changed since it was loaded; reload and retry",
                        Map.of("expectedVersion", expectedVersion, "currentVersion", current.version()));
            }
            Product updated = current.withPriceAndStock(
                    newPrice != null ? newPrice : current.unitPrice(),
                    availableQty != null ? availableQty : current.availableQty());
            products.replace(updated);
            return updated;
        });
    }

    private Product require(String productId) {
        return products.findById(productId)
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + productId,
                        Map.of("productId", productId)));
    }

    private static BigDecimal parsePositivePrice(String unitPrice) {
        BigDecimal price = Money.parse(unitPrice, "unitPrice");
        if (price.signum() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Invalid amount for 'unitPrice'",
                    Map.of("fields", Map.of("unitPrice", "must be greater than 0")));
        }
        return price;
    }
}
