package com.learne.backend.monetization.service;

import com.learne.backend.monetization.enums.PlanCode;
import com.learne.backend.shared.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PlanCatalogService {
    private final PlanCatalogProperties properties;

    public PlanCatalogService(PlanCatalogProperties properties) {
        this.properties = properties;
    }

    public PlanCatalogItem get(PlanCode planCode) {
        Long price = switch (planCode) {
            case VIP -> properties.vipPriceVnd();
            case PRO -> properties.proPriceVnd();
            case PRO_PLUS -> properties.proPlusPriceVnd();
        };
        if (price == null || price <= 0) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Plan price is not configured.");
        }
        return new PlanCatalogItem(planCode, planName(planCode), price);
    }

    private String planName(PlanCode planCode) {
        return switch (planCode) {
            case VIP -> "VIP Plan";
            case PRO -> "PRO Plan";
            case PRO_PLUS -> "PRO_PLUS Plan";
        };
    }

    public record PlanCatalogItem(PlanCode planCode, String name, Long priceVnd) {
    }
}
