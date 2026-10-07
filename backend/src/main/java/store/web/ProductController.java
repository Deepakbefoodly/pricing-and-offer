package store.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import store.service.CatalogService;
import store.web.dto.ProductResponse;

import java.util.List;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final CatalogService catalog;

    public ProductController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<ProductResponse> list() {
        return catalog.list().stream().map(ProductResponse::from).toList();
    }
}
