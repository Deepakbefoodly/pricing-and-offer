package store.payment;

import java.math.BigDecimal;

/**
 * Charges the customer for an order. There is no real payment provider; {@link FakePaymentGateway}
 * stands in for one so checkout can be written (and tested) against the failure it would face.
 */
public interface PaymentGateway {

    /**
     * @param orderId reference for the charge, so a provider could de-duplicate retries
     * @throws PaymentDeclinedException if the charge is refused; nothing has been charged in that case
     */
    void charge(String orderId, BigDecimal amount);
}
