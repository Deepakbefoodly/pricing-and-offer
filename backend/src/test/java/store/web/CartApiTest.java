package store.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract for carts, against the seeded catalogue. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class CartApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private String cartId;

    @BeforeEach
    void createCart() throws Exception {
        MvcResult result = mvc.perform(post("/carts"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/carts/cart_")))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.subtotal").value("0.00"))
                .andReturn();
        cartId = json.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void addViewUpdateAndRemoveItems() throws Exception {
        addItem("{\"productId\": \"socks\", \"quantity\": 3}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productId").value("socks"))
                .andExpect(jsonPath("$.items[0].unitPrice").value("5.49"))
                .andExpect(jsonPath("$.items[0].lineTotal").value("16.47"))
                .andExpect(jsonPath("$.items[0].inStock").value(true))
                .andExpect(jsonPath("$.subtotal").value("16.47"));

        mvc.perform(put("/carts/" + cartId + "/items/socks").contentType(MediaType.APPLICATION_JSON).content("{\"quantity\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(1))
                .andExpect(jsonPath("$.itemCount").value(1));

        mvc.perform(get("/carts/" + cartId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value("5.49"));

        mvc.perform(delete("/carts/" + cartId + "/items/socks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void addingBeyondStockIsAConflictWithShortageDetails() throws Exception {
        addItem("{\"productId\": \"hoodie\", \"quantity\": 1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.details.shortages[0].productId").value("hoodie"))
                .andExpect(jsonPath("$.details.shortages[0].available").value(0));
    }

    @Test
    void invalidRequestsAreRejectedWithTheFieldNamed() throws Exception {
        addItem("{\"quantity\": 1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.productId").exists());
        addItem("{\"productId\": \"shirt\", \"quantity\": \"2\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.quantity").value("wrong type"));
        addItem("{\"productId\": \"shirt\", \"quantity\": 0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.quantity").exists());
        mvc.perform(get("/carts/" + cartId)).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void distinctNotFoundCodes() throws Exception {
        mvc.perform(get("/carts/cart_missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_NOT_FOUND"));
        addItem("{\"productId\": \"jacket\", \"quantity\": 1}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        mvc.perform(delete("/carts/" + cartId + "/items/shirt"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_FOUND"));
    }

    private ResultActions addItem(String body) throws Exception {
        return mvc.perform(post("/carts/" + cartId + "/items").contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
