package br.rafaeros.fastrelax_api.features.billing.dtos;

import java.time.LocalDate;
import java.time.LocalDateTime;

import br.rafaeros.fastrelax_api.features.billing.BillingType;
import br.rafaeros.fastrelax_api.features.billing.Subscription;
import br.rafaeros.fastrelax_api.features.companies.BillingStatus;

public record SubscriptionResponseDTO(
    Long id,
    Long companyId,
    String companyName,
    BillingStatus companyBillingStatus,
    String companyBillingStatusLabel,
    LocalDateTime companyGraceUntil,
    Long planId,
    String planName,
    int planMinCollaborators,
    Integer planMaxCollaborators,
    long planMonthlyPriceCents,
    int planIncludedChairs,
    long planExtraChairPriceCents,
    long planSetupFeePerChairCents,
    int planGraceDays,
    BillingType billingType,
    String billingTypeLabel,
    BillingStatus status,
    String statusLabel,
    String gatewayProvider,
    String gatewaySubscriptionId,
    String cardBrand,
    String cardLast4,
    LocalDate nextDueDate,
    boolean autoAdjustTier,
    long activeCollaboratorsCount,
    int activeChairsCount,
    long estimatedCycleAmountCents,
    LocalDateTime createdAt
) {
    public SubscriptionResponseDTO(Subscription sub, long activeCollaboratorsCount, int activeChairsCount) {
        this(
                sub.getId(),
                sub.getCompany().getId(),
                sub.getCompany().getName(),
                sub.getCompany().getBillingStatus(),
                sub.getCompany().getBillingStatus() != null ? sub.getCompany().getBillingStatus().getLabel() : null,
                sub.getCompany().getGraceUntil(),
                sub.getPlan().getId(),
                sub.getPlan().getName(),
                sub.getPlan().getMinCollaborators(),
                sub.getPlan().getMaxCollaborators(),
                sub.getPlan().getMonthlyPriceCents(),
                sub.getPlan().getIncludedChairs(),
                sub.getPlan().getExtraChairPriceCents(),
                sub.getPlan().getSetupFeePerChairCents(),
                sub.getPlan().getGraceDays(),
                sub.getBillingType(),
                sub.getBillingType() != null ? sub.getBillingType().getLabel() : null,
                sub.getStatus(),
                sub.getStatus() != null ? sub.getStatus().getLabel() : null,
                sub.getGatewayProvider(),
                sub.getGatewaySubscriptionId(),
                sub.getCardBrand(),
                sub.getCardLast4(),
                sub.getNextDueDate(),
                sub.isAutoAdjustTier(),
                activeCollaboratorsCount,
                activeChairsCount,
                sub.getPlan().calculateCycleAmountCents(activeChairsCount, false),
                sub.getCreatedAt());
    }
}
