package br.rafaeros.fastrelax_api.features.billing.dtos;

import java.time.LocalDate;

public record GenerateInvoiceRequestDTO(
    String referenceMonth,
    LocalDate dueDate,
    boolean includeSetupFee,
    String description
) {}
