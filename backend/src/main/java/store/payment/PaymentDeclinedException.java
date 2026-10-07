package store.payment;

public class PaymentDeclinedException extends RuntimeException {

    public PaymentDeclinedException(String reason) {
        super(reason);
    }
}
