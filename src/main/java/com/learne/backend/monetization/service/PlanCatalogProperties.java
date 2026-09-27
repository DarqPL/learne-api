package com.learne.backend.monetization.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.monetization.plans")
public record PlanCatalogProperties(
        Long vipPriceVnd,
        Long proPriceVnd,
        Long proPlusPriceVnd
) {
}
