package store.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract for checkout and orders, against the seeded catalogue. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class OrderApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void checkoutReturnsTheOrderAndClosesTheCart() throws Exception {
        String cartId = cartWith("socks", 3);

        String body = checkout(cartId, "key-socks", "{\"expectedSubtotal\": \"16.47\"}")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(header().string("Location", startsWith("/orders/ord_")))
                .andExpect(jsonPath("$.cartId").value(cartId))
                .andExpect(jsonPath("$.lines[0].name").value("Socks"))
                .andExpect(jsonPath("$.lines[0].unitPrice").value("5.49"))
                .andExpect(jsonPath("$.lines[0].lineTotal").value("16.47"))
                .andExpect(jsonPath("$.subtotal").value("16.47"))
                .andExpect(jsonPath("$.coupon").isEmpty())
                .andExpect(jsonPath("$.discount").value("0.00"))
                .andExpect(jsonPath("$.total").value("16.47"))
                .andExpect(jsonPath("$.placedAt").isString())
                .andReturn().getResponse().getContentAsString();
        String orderId = json.readTree(body).get("id").asText();

        mvc.perform(get("/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("16.47"));
        mvc.perform(get("/carts/" + cartId))
                .andExpect(jsonPath("$.status").value("CHECKED_OUT"))
                .andExpect(jsonPath("$.orderId").value(orderId));

        // The client lost the response and retries with the same key: same order, 200, flagged as a replay.
        checkout(cartId, "key-socks", "{\"expectedSubtotal\": \"16.47\"}")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(orderId));
        // A genuinely new attempt (new key) on the same cart is refused and points at the existing order.
        checkout(cartId, "key-other", "{\"expectedSubtotal\": \"16.47\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_ALREADY_CHECKED_OUT"))
                .andExpect(jsonPath("$.details.orderId").value(orderId));
        // The same key with a different body is a client bug, not a retry.
        checkout(cartId, "key-socks", "{\"expectedSubtotal\": \"10.00\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void checkoutErrorsAreDistinguishable() throws Exception {
        String emptyCart = json.readTree(mvc.perform(post("/carts")).andReturn().getResponse().getContentAsString())
                .get("id").asText();
        checkout(emptyCart, "k-1", "{\"expectedSubtotal\": \"0.00\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CART_EMPTY"));

        String cartId = cartWith("shirt", 1);
        checkout(cartId, "k-2", "{\"expectedSubtotal\": \"20.00\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.details.currentSubtotal").value("25.00"));
        checkout(cartId, "k-3", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.expectedSubtotal").exists());
        checkout(cartId, "k-4", "{\"expectedSubtotal\": 25.00}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.expectedSubtotal").value("wrong type"));
        mvc.perform(post("/carts/" + cartId + "/checkout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedSubtotal\": \"25.00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_MISSING"));
        checkout("cart_missing", "k-5", "{\"expectedSubtotal\": \"1.00\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_NOT_FOUND"));
        mvc.perform(get("/orders/ord_missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    private String cartWith(String productId, int quantity) throws Exception {
        JsonNode cart = json.readTree(mvc.perform(post("/carts")).andReturn().getResponse().getContentAsString());
        String cartId = cart.get("id").asText();
        mvc.perform(post("/carts/" + cartId + "/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": \"" + productId + "\", \"quantity\": " + quantity + "}"))
                .andExpect(status().isOk());
        return cartId;
    }

    private ResultActions checkout(String cartId, String idempotencyKey, String body) throws Exception {
        return mvc.perform(post("/carts/" + cartId + "/checkout").header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
