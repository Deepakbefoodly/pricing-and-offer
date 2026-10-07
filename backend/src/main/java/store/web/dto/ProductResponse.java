package store.web.dto;

import store.domain.Money;
import store.domain.Product;

public record ProductResponse(String id, String name, String unitPrice, int availableQty, long version) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(product.id(), product.name(), Money.format(product.unitPrice()),
                product.availableQty(), product.version());
    }
}
