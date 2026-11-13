package br.rafaeros.fastrelax_api.features.billing.dtos;

import br.rafaeros.fastrelax_api.features.billing.BillingType;
import jakarta.validation.constraints.NotNull;

/**
 * Assinatura pela própria empresa, com Pix ou boleto. Só o plano e a forma de
 * pagamento vêm do cliente: vencimento, valor e fatura inicial são decididos no
 * servidor — aceitar data do cliente permitia empurrar a cobrança para anos
 * depois. Cartão de crédito tem endpoint próprio
 * ({@link CreditCardSubscriptionRequestDTO}).
 */
public record SaveMySubscriptionRequestDTO(
        @NotNull(message = "Escolha um plano") Long planId,
        @NotNull(message = "A forma de cobrança é obrigatória") BillingType billingType) {
}
