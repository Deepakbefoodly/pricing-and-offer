package store.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same races as the service-level tests, but through the real HTTP stack (Tomcat, filters, JSON binding,
 * exception handling), with requests released at the same instant from many client threads.
 * Every order is a coupon milestone here (n = 1) so a coupon can be generated without filler orders.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "store.rewards.n=1")
@DirtiesContext
class HttpConcurrencyTest {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final ExecutorService pool = Executors.newFixedThreadPool(20);

    @AfterEach
    void shutDown() {
        pool.shutdownNow();
    }

    @Test
    void twentyBuyersForThreeWatchesGetExactlyThreeOrders() throws Exception {
        List<String> carts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            carts.add(cartWith("watch", 1));
        }

        List<HttpResponse<String>> responses = concurrently(carts.stream()
                .map(cartId -> (Callable<HttpResponse<String>>) () -> checkout(cartId, UUID.randomUUID().toString(), "199.00", null))
                .toList());

        assertThat(statuses(responses, 201)).isEqualTo(3);
        assertThat(statuses(responses, 409)).isEqualTo(17);
        assertThat(responses).filteredOn(r -> r.statusCode() == 409)
                .allSatisfy(r -> assertThat(body(r).get("code").asText()).isEqualTo("INSUFFICIENT_STOCK"));
        assertThat(availableQty("watch")).isZero();
    }

    @Test
    void twentySimultaneousRetriesOfOneRequestPlaceOneOrder() throws Exception {
        String cartId = cartWith("socks", 1);
        int stockBefore = availableQty("socks");
        String key = UUID.randomUUID().toString();

        List<HttpResponse<String>> responses = concurrently(java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> (Callable<HttpResponse<String>>) () -> checkout(cartId, key, "5.49", null))
                .toList());

        assertThat(statuses(responses, 201)).isEqualTo(1);
        assertThat(statuses(responses, 200)).isEqualTo(19);
        assertThat(responses).filteredOn(r -> r.statusCode() == 200)
                .allSatisfy(r -> assertThat(r.headers().firstValue("Idempotent-Replayed")).hasValue("true"));
        assertThat(responses).extracting(r -> body(r).get("id").asText()).containsOnly(body(responses.get(0)).get("id").asText());
        assertThat(availableQty("socks")).isEqualTo(stockBefore - 1);
    }

    @Test
    void tenCheckoutsRacingForOneCouponLetExactlyOneUseIt() throws Exception {
        checkout(cartWith("shirt", 1), UUID.randomUUID().toString(), "25.00", null); // reach a milestone
        String code = body(send(post("/admin/coupons", ""))).get("code").asText();
        List<String> carts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            carts.add(cartWith("shirt", 1));
        }

        List<HttpResponse<String>> responses = concurrently(carts.stream()
                .map(cartId -> (Callable<HttpResponse<String>>) () -> checkout(cartId, UUID.randomUUID().toString(), "25.00", code))
                .toList());

        assertThat(statuses(responses, 201)).isEqualTo(1);
        assertThat(responses).filteredOn(r -> r.statusCode() != 201)
                .allSatisfy(r -> assertThat(body(r).get("code").asText()).isEqualTo("COUPON_ALREADY_REDEEMED"));
    }

    // --- helpers -------------------------------------------------------------------------------------------

    /** Submits every call, then releases them together so the requests genuinely overlap. */
    private List<HttpResponse<String>> concurrently(List<Callable<HttpResponse<String>>> calls) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (Callable<HttpResponse<String>> call : calls) {
            futures.add(pool.submit(() -> {
                start.await();
                return call.call();
            }));
        }
        start.countDown();
        List<HttpResponse<String>> responses = new ArrayList<>();
        for (Future<HttpResponse<String>> future : futures) {
            responses.add(future.get(30, TimeUnit.SECONDS));
        }
        return responses;
    }

    private String cartWith(String productId, int quantity) throws Exception {
        String cartId = body(send(post("/carts", ""))).get("id").asText();
        HttpResponse<String> added = send(post("/carts/" + cartId + "/items",
                "{\"productId\": \"" + productId + "\", \"quantity\": " + quantity + "}"));
        assertThat(added.statusCode()).isEqualTo(200);
        return cartId;
    }

    private HttpResponse<String> checkout(String cartId, String key, String expectedSubtotal, String couponCode) throws Exception {
        String coupon = couponCode == null ? "" : ", \"couponCode\": \"" + couponCode + "\"";
        HttpRequest request = HttpRequest.newBuilder(uri("/carts/" + cartId + "/checkout"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString("{\"expectedSubtotal\": \"" + expectedSubtotal + "\"" + coupon + "}"))
                .build();
        return send(request);
    }

    private int availableQty(String productId) throws Exception {
        for (JsonNode product : body(send(HttpRequest.newBuilder(uri("/products")).GET().build()))) {
            if (product.get("id").asText().equals(productId)) {
                return product.get("availableQty").asInt();
            }
        }
        throw new AssertionError("unknown product " + productId);
    }

    private HttpRequest post(String path, String body) {
        return HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private JsonNode body(HttpResponse<String> response) {
        try {
            return json.readTree(response.body());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response.body(), e);
        }
    }

    private static long statuses(List<HttpResponse<String>> responses, int status) {
        return responses.stream().filter(r -> r.statusCode() == status).count();
    }
}
