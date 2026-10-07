package store.repository;

import org.springframework.stereotype.Repository;
import store.domain.Order;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Orders are immutable and never removed; only successful checkouts are stored. */
@Repository
public class OrderRepository {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    public void add(Order order) {
        if (orders.putIfAbsent(order.id(), order) != null) {
            throw new IllegalArgumentException("Order already exists: " + order.id());
        }
    }

    public Optional<Order> findById(String id) {
        return Optional.ofNullable(orders.get(id));
    }

    public long count() {
        return orders.size();
    }

    public List<Order> findAll() {
        return orders.values().stream().sorted(Comparator.comparingLong(Order::orderNumber)).toList();
    }
}
