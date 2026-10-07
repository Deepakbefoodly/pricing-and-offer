package store.web;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import store.service.CatalogService;
import store.web.dto.ProductResponse;
import store.web.dto.UpdateProductRequest;

/** Administrative operations. Everything under /admin is admin-only (authentication is out of scope). */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final CatalogService catalog;

    public AdminController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @PatchMapping("/products/{productId}")
    public ProductResponse updateProduct(@PathVariable String productId, @Valid @RequestBody UpdateProductRequest request) {
        return ProductResponse.from(
                catalog.update(productId, request.version(), request.unitPrice(), request.availableQty()));
    }
}
