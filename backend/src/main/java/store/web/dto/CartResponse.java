package store.web.dto;

import store.domain.CartStatus;
import store.domain.Money;
import store.service.CartView;

import java.util.List;

public record CartResponse(String id, CartStatus status, String orderId, List<Item> items, int itemCount, String subtotal) {

    public record Item(String productId, String name, String unitPrice, int quantity, String lineTotal,
                       int availableQty, boolean inStock) {
    }

    public static CartResponse from(CartView cart) {
        List<Item> items = cart.lines().stream()
                .map(line -> new Item(line.productId(), line.name(), Money.format(line.unitPrice()), line.quantity(),
                        Money.format(line.lineTotal()), line.availableQty(), line.inStock()))
                .toList();
        return new CartResponse(cart.id(), cart.status(), cart.orderId(), items, cart.itemCount(),
                Money.format(cart.subtotal()));
    }
}
