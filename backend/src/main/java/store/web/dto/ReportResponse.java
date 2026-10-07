package store.web.dto;

import store.domain.Money;
import store.service.Report;

import java.util.List;

public record ReportResponse(long totalOrders, List<Report.ProductQuantity> quantityByProduct, String grossRevenue,
                             String totalDiscounts, String netRevenue, Report.CouponCounts coupons) {

    public static ReportResponse from(Report report) {
        return new ReportResponse(report.totalOrders(), report.quantityByProduct(), Money.format(report.grossRevenue()),
                Money.format(report.totalDiscounts()), Money.format(report.netRevenue()), report.coupons());
    }
}
