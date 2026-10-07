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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract for coupon generation and redemption. Every order is a milestone here (n = 1), x = 10%. */
@SpringBootTest(properties = {"store.rewards.n=1", "store.rewards.x=10"})
@AutoConfigureMockMvc
@DirtiesContext
class CouponApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void generateRedeemAndRejectReuse() throws Exception {
        mvc.perform(post("/admin/coupons"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_ELIGIBLE_MILESTONE"))
                .andExpect(jsonPath("$.details.nextMilestone").value(1))
                .andExpect(jsonPath("$.details.placedOrders").value(0));

        checkout(cartWith("shirt", 1), "25.00", null).andExpect(status().isCreated());

        String body = mvc.perform(post("/admin/coupons"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.percentOff").value(10))
                .andExpect(jsonPath("$.milestoneOrderNumber").value(1))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.redeemedByOrderId").isEmpty())
                .andReturn().getResponse().getContentAsString();
        String code = json.readTree(body).get("code").asText();

        String orderBody = checkout(cartWith("socks", 3), "16.47", code)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.coupon.code").value(code))
                .andExpect(jsonPath("$.coupon.percentOff").value(10))
                .andExpect(jsonPath("$.discount").value("1.65"))
                .andExpect(jsonPath("$.total").value("14.82"))
                .andReturn().getResponse().getContentAsString();
        String orderId = json.readTree(orderBody).get("id").asText();

        mvc.perform(get("/admin/coupons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value(code))
                .andExpect(jsonPath("$[0].status").value("REDEEMED"))
                .andExpect(jsonPath("$[0].redeemedByOrderId").value(orderId));

        checkout(cartWith("shirt", 1), "25.00", code)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_ALREADY_REDEEMED"));
    }

    @Test
    void unknownOrMalformedCouponIsRejected() throws Exception {
        String cartId = cartWith("shirt", 1);
        checkout(cartId, "25.00", "REWARD-0000-NOPE")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COUPON_NOT_FOUND"));
        mvc.perform(post("/carts/" + cartId + "/checkout").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedSubtotal\": \"25.00\", \"couponCode\": 123}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.couponCode").value("wrong type"));
    }

    private String cartWith(String productId, int quantity) throws Exception {
        String cartId = json.readTree(mvc.perform(post("/carts")).andReturn().getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/carts/" + cartId + "/items").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\": \"" + productId + "\", \"quantity\": " + quantity + "}")).andExpect(status().isOk());
        return cartId;
    }

    private ResultActions checkout(String cartId, String expectedSubtotal, String couponCode) throws Exception {
        String coupon = couponCode == null ? "" : ", \"couponCode\": \"" + couponCode + "\"";
        return mvc.perform(post("/carts/" + cartId + "/checkout").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedSubtotal\": \"" + expectedSubtotal + "\"" + coupon + "}"));
    }
}
