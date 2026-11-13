package br.rafaeros.fastrelax_api.features.billing.dtos;

import java.time.LocalDateTime;

import br.rafaeros.fastrelax_api.features.billing.Plan;

public record PlanResponseDTO(
    Long id,
    String name,
    String description,
    int minCollaborators,
    Integer maxCollaborators,
    long monthlyPriceCents,
    int includedChairs,
    long extraChairPriceCents,
    long setupFeePerChairCents,
    int graceDays,
    boolean active,
    LocalDateTime createdAt
) {
    public PlanResponseDTO(Plan plan) {
        this(
                plan.getId(),
                plan.getName(),
                plan.getDescription(),
                plan.getMinCollaborators(),
                plan.getMaxCollaborators(),
                plan.getMonthlyPriceCents(),
                plan.getIncludedChairs(),
                plan.getExtraChairPriceCents(),
                plan.getSetupFeePerChairCents(),
                plan.getGraceDays(),
                plan.isActive(),
                plan.getCreatedAt());
    }
}
