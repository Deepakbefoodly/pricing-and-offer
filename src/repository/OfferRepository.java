package repository;

import entity.Offer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class OfferRepository {

    private final Map<String, CopyOnWriteArrayList<Offer>> offersByProduct = new ConcurrentHashMap<>();
    private final Map<String, Offer> offersById = new ConcurrentHashMap<>();

    public void addOffer(Offer offer) {
        Offer existing = offersById.putIfAbsent(offer.getOfferId(), offer);
        if (existing != null) {
            throw new IllegalArgumentException("Offer already exists: " + offer.getOfferId());
        }
        for (String productId : offer.getProductIds()) {
            offersByProduct.computeIfAbsent(productId, k -> new CopyOnWriteArrayList<>()).add(offer);
        }
    }

    public List<Offer> getOfferForProduct(String productId) {
        return offersByProduct.getOrDefault(productId, new CopyOnWriteArrayList<>());
    }
}
