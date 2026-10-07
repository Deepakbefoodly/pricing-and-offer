package store.payment;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Always approves and records every charge, so tests can assert how many times a customer was
 * charged (e.g. exactly once despite retries). Tests that need a decline use their own gateway.
 */
@Component
public class FakePaymentGateway implements PaymentGateway {

    public record Charge(String orderId, BigDecimal amount) {
    }

    private final List<Charge> charges = new CopyOnWriteArrayList<>();

    @Override
    public void charge(String orderId, BigDecimal amount) {
        if (amount.signum() < 0) {
            throw new PaymentDeclinedException("negative amount: " + amount);
        }
        charges.add(new Charge(orderId, amount));
    }

    public List<Charge> charges() {
        return List.copyOf(charges);
    }
}
