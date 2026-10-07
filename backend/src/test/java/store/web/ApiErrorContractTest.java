package store.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import store.error.ApiException;
import store.error.ErrorCode;

import java.util.Map;

import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every failure must reach the client as {code, message, details} with the status mapped from the code. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiErrorContractTest.ProbeConfig.class)
class ApiErrorContractTest {

    @Autowired
    MockMvc mvc;

    @Test
    void unknownRouteReturnsJsonNotFound() throws Exception {
        mvc.perform(get("/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.details").isMap());
    }

    @Test
    void apiExceptionKeepsCodeStatusAndDetails() throws Exception {
        mvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.details.available").value(1));
    }

    @Test
    void invalidBodyListsFailingFields() throws Exception {
        mvc.perform(post("/test/validated").contentType(MediaType.APPLICATION_JSON).content("{\"quantity\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields.quantity").isString());
    }

    @Test
    void malformedJsonIsAValidationError() throws Exception {
        mvc.perform(post("/test/validated").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void wrongMethodReturnsJson405() throws Exception {
        mvc.perform(post("/test/conflict"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unexpectedExceptionDoesNotLeakInternals() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected server error"));
    }

    @Test
    void corsPreflightAllowsFrontendOriginAndIdempotencyHeader() throws Exception {
        mvc.perform(options("/test/validated")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Idempotency-Key, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void errorResponsesCarryCorsHeadersSoTheBrowserCanReadThem() throws Exception {
        mvc.perform(get("/does-not-exist").header("Origin", "http://localhost:5173"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void errorsAreJsonEvenWhenTheClientAsksForXml() throws Exception {
        mvc.perform(get("/test/conflict").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", startsWith("application/json")))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
    }

    @Test
    void unsupportedAcceptHeaderIsAJson406() throws Exception {
        mvc.perform(get("/test/ok").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }

    @Test
    void theErrorEndpointUsesTheSameBody() throws Exception {
        mvc.perform(get("/error"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.details").isMap());
    }

    @Test
    void corsRejectsUnknownOrigin() throws Exception {
        mvc.perform(options("/test/validated")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Access-Control-Allow-Origin", nullValue()));
    }

    @TestConfiguration
    static class ProbeConfig {

        @RestController
        static class ProbeController {

            record Body(@Min(1) int quantity) {
            }

            @GetMapping("/test/conflict")
            void conflict() {
                throw new ApiException(ErrorCode.INSUFFICIENT_STOCK, "Not enough stock", Map.of("available", 1));
            }

            @PostMapping("/test/validated")
            void validated(@Valid @RequestBody Body body) {
            }

            @GetMapping("/test/ok")
            Map<String, String> ok() {
                return Map.of("status", "ok");
            }

            @GetMapping("/test/boom")
            void boom() {
                throw new IllegalStateException("internal detail that must not leak");
            }
        }
    }
}
