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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract for the catalogue. Uses the seeded products; the context is discarded afterwards. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class ProductApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void seededCatalogueHasAtLeastFiveProductsIncludingLimitedStock() throws Exception {
        JsonNode products = json.readTree(mvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(products.size()).isGreaterThanOrEqualTo(5);
        assertThat(products).anySatisfy(p -> {
            assertThat(p.get("id").asText()).isEqualTo("watch");
            assertThat(p.get("availableQty").asInt()).isBetween(1, 5);
        });
        // Money is serialized as a string with exactly two decimals.
        assertThat(products).allSatisfy(p -> {
            assertThat(p.get("unitPrice").isTextual()).isTrue();
            assertThat(p.get("unitPrice").asText()).matches("\\d+\\.\\d{2}");
        });
    }

    @Test
    void adminUpdateReturnsTheUpdatedProduct() throws Exception {
        long version = currentVersion("jeans");

        patchProduct("jeans", "{\"version\": " + version + ", \"unitPrice\": \"54.5\", \"availableQty\": 40}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unitPrice").value("54.50"))
                .andExpect(jsonPath("$.availableQty").value(40))
                .andExpect(jsonPath("$.version").value(version + 1));
    }

    @Test
    void staleVersionIsAConflict() throws Exception {
        long version = currentVersion("socks");
        patchProduct("socks", "{\"version\": " + version + ", \"availableQty\": 150}").andExpect(status().isOk());

        patchProduct("socks", "{\"version\": " + version + ", \"availableQty\": 999}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_MODIFIED"))
                .andExpect(jsonPath("$.details.currentVersion").value(version + 1));
    }

    @Test
    void priceAsJsonNumberIsRejected() throws Exception {
        patchProduct("shoes", "{\"version\": 1, \"unitPrice\": 27.5}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields.unitPrice").exists());
    }

    @Test
    void priceWithMoreThanTwoDecimalsIsRejected() throws Exception {
        patchProduct("shoes", "{\"version\": 1, \"unitPrice\": \"27.505\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.unitPrice").exists());
    }

    @Test
    void fractionalStockIsRejectedNotTruncated() throws Exception {
        patchProduct("shoes", "{\"version\": 1, \"availableQty\": 1.5}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.availableQty").exists());
    }

    @Test
    void unknownFieldIsRejected() throws Exception {
        patchProduct("shoes", "{\"version\": 1, \"availableQuantity\": 5}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.availableQuantity").value("unknown field"));
    }

    @Test
    void missingVersionIsRejected() throws Exception {
        patchProduct("shoes", "{\"availableQty\": 5}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.version").exists());
    }

    @Test
    void unknownProductIsNotFound() throws Exception {
        patchProduct("nope", "{\"version\": 1, \"availableQty\": 5}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    private ResultActions patchProduct(String id, String body) throws Exception {
        return mvc.perform(patch("/admin/products/" + id).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long currentVersion(String id) throws Exception {
        JsonNode products = json.readTree(mvc.perform(get("/products")).andReturn().getResponse().getContentAsString());
        for (JsonNode product : products) {
            if (product.get("id").asText().equals(id)) {
                return product.get("version").asLong();
            }
        }
        throw new AssertionError("product not seeded: " + id);
    }
}
