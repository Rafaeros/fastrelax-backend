package br.rafaeros.fastrelax_api.features.billing.dtos;

import java.time.LocalDate;

import br.rafaeros.fastrelax_api.features.billing.BillingType;
import jakarta.validation.constraints.NotNull;

public record SaveSubscriptionRequestDTO(
    @NotNull(message = "A empresa é obrigatória")
    Long companyId,

    /** Opcional quando autoAdjustTier = true: se nulo, escolhe a faixa pelos colaboradores ativos. */
    Long planId,

    @NotNull(message = "A forma de pagamento é obrigatória")
    BillingType billingType,

    @NotNull(message = "A data do próximo vencimento é obrigatória")
    LocalDate nextDueDate,

    boolean autoAdjustTier,

    /** Se verdadeiro, já emite a fatura do ciclo atual ao salvar a assinatura. */
    boolean generateInitialInvoice,

    /** Se verdadeiro, soma a taxa única de setup por cadeira na fatura inicial. */
    boolean includeSetupFee
) {}
