package store.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Coupon-code guessing is throttled per client, without affecting checkouts that carry no coupon. */
@SpringBootTest(properties = {"store.coupon-guessing.max-failed-attempts=3", "store.coupon-guessing.window=15m"})
@AutoConfigureMockMvc
@DirtiesContext
class CouponGuessingApiTest {

    private static final String ATTACKER = "203.0.113.7";
    private static final String SOMEONE_ELSE = "198.51.100.9";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void repeatedUnknownCodesAreThrottledForThatClientOnly() throws Exception {
        String cartId = cartWithSocks();
        for (int i = 0; i < 3; i++) {
            checkout(cartId, "REWARD-0005-GUESS" + i, ATTACKER)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("COUPON_NOT_FOUND"));
        }

        checkout(cartId, "REWARD-0005-GUESS3", ATTACKER)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.details.retryAfterSeconds").isNumber())
                .andExpect(header().exists("Retry-After"));

        // Another client is unaffected, and so is the same client checking out without a coupon.
        checkout(cartWithSocks(), "REWARD-0005-OTHER", SOMEONE_ELSE).andExpect(status().isNotFound());
        checkout(cartId, null, ATTACKER).andExpect(status().isCreated());
    }

    private String cartWithSocks() throws Exception {
        String cartId = json.readTree(mvc.perform(post("/carts")).andReturn().getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/carts/" + cartId + "/items").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\": \"socks\", \"quantity\": 1}")).andExpect(status().isOk());
        return cartId;
    }

    private ResultActions checkout(String cartId, String couponCode, String clientAddress) throws Exception {
        String coupon = couponCode == null ? "" : ", \"couponCode\": \"" + couponCode + "\"";
        return mvc.perform(post("/carts/" + cartId + "/checkout")
                .with(request -> {
                    request.setRemoteAddr(clientAddress);
                    return request;
                })
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedSubtotal\": \"5.49\"" + coupon + "}"));
    }
}
