package store.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import store.service.CatalogService;
import store.service.CouponService;
import store.service.OrderService;
import store.service.ReportService;
import store.web.dto.CouponResponse;
import store.web.dto.OrderResponse;
import store.web.dto.ProductResponse;
import store.web.dto.ReportResponse;
import store.web.dto.UpdateProductRequest;

import java.util.List;

/** Administrative operations. Everything under /admin is admin-only (authentication is out of scope). */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final CatalogService catalog;
    private final CouponService coupons;
    private final OrderService orders;
    private final ReportService reports;

    public AdminController(CatalogService catalog, CouponService coupons, OrderService orders, ReportService reports) {
        this.catalog = catalog;
        this.coupons = coupons;
        this.orders = orders;
        this.reports = reports;
    }

    @PatchMapping("/products/{productId}")
    public ProductResponse updateProduct(@PathVariable String productId, @Valid @RequestBody UpdateProductRequest request) {
        return ProductResponse.from(
                catalog.update(productId, request.version(), request.unitPrice(), request.availableQty()));
    }

    /** Generates the coupon for the oldest reached-but-unrewarded milestone; 409 NO_ELIGIBLE_MILESTONE otherwise. */
    @PostMapping("/coupons")
    @ResponseStatus(HttpStatus.CREATED)
    public CouponResponse generateCoupon() {
        return CouponResponse.from(coupons.generate());
    }

    @GetMapping("/coupons")
    public List<CouponResponse> listCoupons() {
        return coupons.list().stream().map(CouponResponse::from).toList();
    }

    /** All placed orders, oldest first, so the report can be reconciled against them. */
    @GetMapping("/orders")
    public List<OrderResponse> listOrders() {
        return orders.list().stream().map(OrderResponse::from).toList();
    }

    /** Sales summary computed from orders and coupons. Read-only: calling it never changes state. */
    @GetMapping("/report")
    public ReportResponse report() {
        return ReportResponse.from(reports.summary());
    }
}
