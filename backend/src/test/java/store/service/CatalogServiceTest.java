package store.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import store.domain.Product;
import store.error.ApiException;
import store.error.ErrorCode;
import store.repository.ProductRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogServiceTest {

    private ProductRepository repository;
    private CatalogService catalog;

    @BeforeEach
    void setUp() {
        repository = new ProductRepository();
        repository.add(Product.create("watch", "Limited Watch", "199.00", 3));
        catalog = new CatalogService(repository, new StoreLock());
    }

    @Test
    void updateChangesPriceAndStockAndBumpsVersion() {
        Product updated = catalog.update("watch", 1, "205.5", 10);

        assertThat(updated.unitPrice()).isEqualByComparingTo("205.50");
        assertThat(updated.unitPrice().scale()).isEqualTo(2);
        assertThat(updated.availableQty()).isEqualTo(10);
        assertThat(updated.version()).isEqualTo(2);
        assertThat(catalog.get("watch")).isEqualTo(updated);
    }

    @Test
    void omittedFieldsKeepTheirCurrentValue() {
        catalog.update("watch", 1, null, 7);

        Product product = catalog.get("watch");
        assertThat(product.unitPrice()).isEqualTo(new BigDecimal("199.00"));
        assertThat(product.availableQty()).isEqualTo(7);
    }

    @Test
    void staleVersionIsRejectedAndNothingChanges() {
        catalog.update("watch", 1, null, 2); // e.g. a sale happened: now version 2

        assertThatThrownBy(() -> catalog.update("watch", 1, null, 50))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.PRODUCT_MODIFIED);
                    assertThat(e.getDetails()).containsEntry("currentVersion", 2L);
                });
        assertThat(catalog.get("watch").availableQty()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "19.999", "-5.00", "abc", "1e3", " 10.00", "10.", "12345678.00"})
    void invalidPricesAreRejected(String price) {
        assertThatThrownBy(() -> catalog.update("watch", 1, price, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThat(catalog.get("watch").version()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, CatalogService.MAX_STOCK + 1})
    void invalidStockIsRejected(int qty) {
        assertThatThrownBy(() -> catalog.update("watch", 1, null, qty))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void updateWithNothingToChangeIsRejected() {
        assertThatThrownBy(() -> catalog.update("watch", 1, null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void unknownProductIsNotFound() {
        assertThatThrownBy(() -> catalog.update("nope", 1, "1.00", null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));
    }

    /** Ten admins save edits based on the same loaded version at once: exactly one may win. */
    @Test
    void concurrentEditsFromTheSameVersionHaveExactlyOneWinner() throws Exception {
        int editors = 10;
        ExecutorService pool = Executors.newFixedThreadPool(editors);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < editors; i++) {
            int stock = 100 + i;
            results.add(pool.submit(() -> {
                start.await();
                try {
                    catalog.update("watch", 1, null, stock);
                    return true;
                } catch (ApiException e) {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.PRODUCT_MODIFIED);
                    return false;
                }
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        long winners = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                winners++;
            }
        }
        assertThat(winners).isEqualTo(1);
        assertThat(catalog.get("watch").version()).isEqualTo(2);
    }
}
