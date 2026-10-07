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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The report, as an API client sees it, reconciles with GET /admin/orders and GET /admin/coupons. */
@SpringBootTest(properties = {"store.rewards.n=2", "store.rewards.x=10"})
@AutoConfigureMockMvc
@DirtiesContext
class ReportApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void reportMatchesTheOrdersAndCouponsTheApiReturns() throws Exception {
        checkout("shirt", 2, "50.00", null);
        checkout("socks", 3, "16.47", null);
        String code = read(mvc.perform(post("/admin/coupons")).andExpect(status().isCreated())).get("code").asText();
        checkout("jeans", 1, "49.99", code);

        JsonNode report = read(mvc.perform(get("/admin/report")).andExpect(status().isOk()));
        JsonNode orders = read(mvc.perform(get("/admin/orders")).andExpect(status().isOk()));
        JsonNode coupons = read(mvc.perform(get("/admin/coupons")).andExpect(status().isOk()));

        assertThat(report.get("totalOrders").asLong()).isEqualTo(orders.size()).isEqualTo(3);
        assertThat(money(report, "grossRevenue")).isEqualByComparingTo(sum(orders, "subtotal")).isEqualByComparingTo("116.46");
        assertThat(money(report, "totalDiscounts")).isEqualByComparingTo(sum(orders, "discount")).isEqualByComparingTo("5.00");
        assertThat(money(report, "netRevenue")).isEqualByComparingTo(sum(orders, "total")).isEqualByComparingTo("111.46");
        assertThat(report.get("grossRevenue").isTextual()).isTrue();

        long quantityInOrders = 0;
        for (JsonNode order : orders) {
            for (JsonNode line : order.get("lines")) {
                quantityInOrders += line.get("quantity").asLong();
            }
        }
        long quantityInReport = 0;
        for (JsonNode product : report.get("quantityByProduct")) {
            quantityInReport += product.get("quantity").asLong();
        }
        assertThat(quantityInReport).isEqualTo(quantityInOrders).isEqualTo(6);

        long redeemed = 0;
        for (JsonNode coupon : coupons) {
            redeemed += "REDEEMED".equals(coupon.get("status").asText()) ? 1 : 0;
        }
        assertThat(report.get("coupons").get("generated").asLong()).isEqualTo(coupons.size()).isEqualTo(1);
        assertThat(report.get("coupons").get("redeemed").asLong()).isEqualTo(redeemed).isEqualTo(1);
        assertThat(report.get("coupons").get("available").asLong()).isZero();

        // Asking again changes nothing.
        assertThat(read(mvc.perform(get("/admin/report")))).isEqualTo(report);
        assertThat(read(mvc.perform(get("/admin/orders")))).isEqualTo(orders);
    }

    private void checkout(String productId, int quantity, String expectedSubtotal, String couponCode) throws Exception {
        String cartId = read(mvc.perform(post("/carts"))).get("id").asText();
        mvc.perform(post("/carts/" + cartId + "/items").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\": \"" + productId + "\", \"quantity\": " + quantity + "}")).andExpect(status().isOk());
        String coupon = couponCode == null ? "" : ", \"couponCode\": \"" + couponCode + "\"";
        mvc.perform(post("/carts/" + cartId + "/checkout").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedSubtotal\": \"" + expectedSubtotal + "\"" + coupon + "}"))
                .andExpect(status().isCreated());
    }

    private JsonNode read(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static BigDecimal money(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asText());
    }

    private static BigDecimal sum(JsonNode orders, String field) {
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode order : orders) {
            total = total.add(money(order, field));
        }
        return total;
    }
}
