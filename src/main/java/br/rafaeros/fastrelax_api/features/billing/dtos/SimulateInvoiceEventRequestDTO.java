package br.rafaeros.fastrelax_api.features.billing.dtos;

import jakarta.validation.constraints.NotBlank;

public record SimulateInvoiceEventRequestDTO(
    @NotBlank(message = "A ação de simulação é obrigatória")
    String action
) {}
