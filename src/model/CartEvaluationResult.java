package model;

import java.util.List;

public record CartEvaluationResult(
        String cartId,
        List<LineItemResult> lineItems,
        double totalBasePrice,
        double totalDiscount,
        double finalAmount
) {
}
