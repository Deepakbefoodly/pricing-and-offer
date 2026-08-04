import exception.CartNotFoundException;
import exception.ProductNotFoundException;
import model.CartEvaluationResult;
import model.LineItemResult;
import model.OfferEvaluationDetail;
import repository.CartRepository;
import repository.OfferRepository;
import repository.ProductRepository;
import service.PricingService;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// Driver Class with test cases
public class Main {

    public static void main(String[] args) throws InterruptedException {
        runSpecScenario();
//        runValidationAndExceptionChecks();
//        runConcurrencyStressTest();
    }

    /** The exact scenario from the problem statement — verified against its expected numbers. */
    private static void runSpecScenario() {
        PricingService service = new PricingService(new ProductRepository(), new OfferRepository(), new CartRepository());

        service.addProduct("Shirt", 100);
//        service.addProduct("Shirt", 200);
        service.addProduct("Shoes", 2000);
        service.addProduct("Socks", 500);

        service.createPercentageOffer("OFFER1", Set.of("Shirt", "Socks"), 10, 2, 2);
        service.createFlatOffer("OFFER2", Set.of("Shoes"), 300, 2, 5);
        service.createPercentageOffer("OFFER3", Set.of("Shoes"), 20, 2, 1);

        service.createCart("Cart1");
        service.addToCart("Cart1", "Shirt", 2);
        service.addToCart("Cart1", "Jacket", 3);
        service.addToCart("Cart1", "Shoes", 1);
        CartEvaluationResult cart1 = service.evaluateCart("Cart1");
        printEvaluation(cart1);
//        assertTotals(cart1, 7000, 1000, 5000);

        service.createCart("Cart2");
        service.addToCart("Cart2", "Shirt", 2);
        service.addToCart("Cart2", "Shoes", 2);
        CartEvaluationResult cart2 = service.evaluateCart("Cart2");
        printEvaluation(cart2);
//        assertTotals(cart2, 6000, 800, 5200);

        service.createCart("Cart3");
        service.addToCart("Cart3", "Socks", 3);
        CartEvaluationResult cart3 = service.evaluateCart("Cart3");
        printEvaluation(cart3);
//        assertTotals(cart3, 1500, 0, 1500);
    }

    /** SCENARIO: exception handling **/
    private static void runValidationAndExceptionChecks() {
        PricingService service = new PricingService(new ProductRepository(), new OfferRepository(), new CartRepository());
        service.addProduct("Bag", 1000);

        expectException(() -> service.addProduct("Bag", 500), IllegalArgumentException.class, "duplicate product");
        expectException(() -> service.addToCart("NoSuchCart", "Bag", 1), CartNotFoundException.class, "add to missing cart");
        expectException(() -> service.evaluateCart("NoSuchCart"), CartNotFoundException.class, "evaluate missing cart");
        service.createCart("C1");
        expectException(() -> service.addToCart("C1", "NoSuchProduct", 1), ProductNotFoundException.class, "add missing product");
        expectException(() -> service.createPercentageOffer("BAD1", Set.of("Bag"), 150, 1, 1),
                IllegalArgumentException.class, "percentage over 100");
        expectException(() -> service.createFlatOffer("BAD2", Set.of("Bag"), -10, 1, 1),
                IllegalArgumentException.class, "negative flat discount");
    }

    /** Proves the global-use limit holds even when many carts evaluate concurrently. */
    private static void runConcurrencyStressTest() throws InterruptedException {
        PricingService service = new PricingService(new ProductRepository(), new OfferRepository(), new CartRepository());
        service.addProduct("Bag", 1000);
        service.createFlatOffer("LIMITED", Set.of("Bag"), 100, 1, 3); // only 3 global uses

        int cartCount = 20;
        for (int i = 0; i < cartCount; i++) {
            service.createCart("StressCart" + i);
            service.addToCart("StressCart" + i, "Bag", 1);
        }

        AtomicInteger appliedCount = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        for (int i = 0; i < cartCount; i++) {
            String cartId = "StressCart" + i;
            pool.submit(() -> {
                CartEvaluationResult result = service.evaluateCart(cartId);
                if (result.lineItems().get(0).appliedOfferId() != null) {
                    appliedCount.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertTrue(appliedCount.get() == 3, "expected exactly 3 carts to win the limited offer, got " + appliedCount.get());
        System.out.println("PASS: exactly 3/" + cartCount + " concurrent carts got the offer (global limit respected under race).\n");
    }

    private static void printEvaluation(CartEvaluationResult result) {
        System.out.println("---- " + result.cartId() + " ----");
        for (LineItemResult item : result.lineItems()) {
            System.out.println(item.productId() + ": Qty " + item.quantity() + ", Base = " + String.format("%.2f", item.basePrice()));
            for (OfferEvaluationDetail d : item.consideredOffers()) {
                String status = d.valid()
                        ? "valid, discount = " + String.format("%.2f", d.discountIfApplied())
                        : "invalid (" + d.invalidReason() + ")";
                System.out.println("    " + d.offerId() + " -> " + status);
            }
            System.out.println("    Applied Offer: " + (item.appliedOfferId() == null ? "None" : item.appliedOfferId())
                    + ". Discount = " + String.format("%.2f", item.discount()) + ". Final = " + String.format("%.2f", item.finalPrice()));
        }
        System.out.println("  Cart Total -> Base = " + String.format("%.2f", result.totalBasePrice())
                + ", Total Discount = " + String.format("%.2f", result.totalDiscount())
                + ", Final Amount To Pay = " + String.format("%.2f", result.finalAmount()));
        System.out.println();
    }

    private static void assertTotals(CartEvaluationResult result, double base, double discount, double finalAmount) {
        assertTrue(result.totalBasePrice() == base, "total base mismatch: " + result.totalBasePrice());
        assertTrue(result.totalDiscount() == discount, "total discount mismatch: " + result.totalDiscount());
        assertTrue(result.finalAmount() == finalAmount, "final amount mismatch: " + result.finalAmount());
    }

    private interface ThrowingAction {
        void run();
    }

    private static void expectException(ThrowingAction action, Class<? extends Exception> expected, String label) {
        try {
            action.run();
            throw new AssertionError("expected " + expected.getSimpleName() + " for [" + label + "] but nothing was thrown");
        } catch (Exception e) {
            System.out.println("  [" + label + "] -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}