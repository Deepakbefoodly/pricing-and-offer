package store.service;

import org.springframework.stereotype.Service;
import store.domain.Order;
import store.error.ApiException;
import store.error.ErrorCode;
import store.repository.OrderRepository;

import java.util.List;
import java.util.Map;

@Service
public class OrderService {

    private final OrderRepository orders;
    private final StoreLock lock;

    public OrderService(OrderRepository orders, StoreLock lock) {
        this.orders = orders;
        this.lock = lock;
    }

    /** All placed orders, oldest first; the report can be reconciled against this list. */
    public List<Order> list() {
        return lock.read(orders::findAll);
    }

    public Order get(String orderId) {
        return lock.read(() -> orders.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND, "Order not found: " + orderId,
                        Map.of("orderId", orderId))));
    }
}
