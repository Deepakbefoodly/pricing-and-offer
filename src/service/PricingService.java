package service;

import entity.Cart;
import entity.Offer;
import entity.Product;
import factory.OfferFactory;
import model.CartEvaluationResult;
import model.LineItemResult;
import model.OfferEvaluationDetail;
import repository.CartRepository;
import repository.OfferRepository;
import repository.ProductRepository;

import java.util.*;

public class PricingService {

    private final ProductRepository productRepository;
    private final OfferRepository offerRepository;
    private final CartRepository cartRepository;

    public PricingService(ProductRepository productRepository, OfferRepository offerRepository, CartRepository cartRepository) {
        this.productRepository = productRepository;
        this.cartRepository = cartRepository;
        this.offerRepository = offerRepository;
    }

    public void addProduct(String productId, double basePrice) {
        productRepository.addProduct(productId, basePrice);
    }

    public void createPercentageOffer(String offerId, Set<String> productIds, double discountPercentage,
                                      int minQuantity, int maxGlobalUsage) {
        offerRepository.addOffer(OfferFactory.createPercentageOffer(offerId, productIds, discountPercentage,
                minQuantity, maxGlobalUsage));
    }

    public void createFlatOffer(String offerId, Set<String> productIds, double discountAmountPerUnit,
                                      int minQuantity, int maxGlobalUsage) {
        offerRepository.addOffer(OfferFactory.createFlatOffer(offerId, productIds, discountAmountPerUnit,
                minQuantity, maxGlobalUsage));
    }

    public void createCart(String cartId) {
        cartRepository.createCart(cartId);
    }

    public void addToCart(String cartId, String productId, int quantity) {
        productRepository.getProduct(productId);
        cartRepository.getCart(cartId).addItem(productId, quantity);
    }

    public CartEvaluationResult evaluateCart(String cartId) {
        Cart cart = cartRepository.getCart(cartId);
        List<LineItemResult> lineItems = new ArrayList<>();
        double totalBase = 0;
        double totalDiscount = 0;

        for (Map.Entry<String, Integer> entry : cart.getItems().entrySet()) {
            LineItemResult lineItem = evaluateLineItem(entry.getKey(), entry.getValue());
            lineItems.add(lineItem);
            totalBase += lineItem.basePrice();
            totalDiscount += lineItem.discount();
        }

        return new CartEvaluationResult(
                cartId,
                lineItems,
                totalBase,
                totalDiscount,
                totalBase - totalDiscount);
    }

    private LineItemResult evaluateLineItem(String productId, int quantity) {
        Product product = productRepository.getProduct(productId);
        double lineBase = product.getBasePrice() * quantity;

        List<Offer> candidates = offerRepository.getOfferForProduct(productId);
        List<OfferEvaluationDetail> details = new ArrayList<>();
        List<Offer> validOffers = new ArrayList<>();

        for (Offer offer : candidates) {
            if (!offer.meetsMinQuantity(quantity)) {
                details.add(new OfferEvaluationDetail(
                        offer.getOfferId(),
                        0,
                        false,
                        "minimum quantity not met"));
                continue;
            }
            if (!offer.hasRemainingUsage()) {
                details.add(new OfferEvaluationDetail(
                        offer.getOfferId(),
                        0,
                        false,
                        "global use exhausted"));
                continue;
            }
            double discount = offer.calculateDiscount(product.getBasePrice(), quantity);
            if (discount <= 0 || discount >= lineBase) {
                details.add(new OfferEvaluationDetail(
                        offer.getOfferId(),
                        discount,
                        false,
                        "would not leave a positive price"));
                continue;
            }
            details.add(new OfferEvaluationDetail(
                    offer.getOfferId(),
                    discount,
                    true,
                    null));
            validOffers.add(offer);
        }

        // Best Single Offer: try highest-discount first.
        validOffers.sort(Comparator.comparingDouble(
                (Offer o) -> o.calculateDiscount(product.getBasePrice(), quantity)).reversed());

        Offer appliedOffer = null;
        double appliedDiscount = 0;
        for (Offer candidate : validOffers) {
            if (candidate.tryRedeem()) {
                appliedOffer = candidate;
                appliedDiscount = candidate.calculateDiscount(product.getBasePrice(), quantity);
                break;
            }
        }

        String appliedOfferId = appliedOffer == null ? null : appliedOffer.getOfferId();
        double finalPrice = lineBase - appliedDiscount;
        return new LineItemResult(
                productId,
                quantity,
                lineBase,
                details,
                appliedOfferId,
                appliedDiscount,
                finalPrice);
    }
}
