package br.rafaeros.fastrelax_api.features.billing.dtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Pagamento de uma fatura em aberto no cartão, dentro da aplicação.
 *
 * <p>
 * O cartão é tokenizado no provedor e não é gravado. Com
 * {@code useForSubscription}, o mesmo token passa a pagar as próximas
 * mensalidades automaticamente.
 */
public record PayInvoiceWithCardRequestDTO(
        @NotNull(message = "Informe o cartão") @Valid CreditCardSubscriptionRequestDTO.Card card,
        @NotNull(message = "Informe o titular") @Valid CreditCardSubscriptionRequestDTO.Holder holder,
        boolean useForSubscription,
        @Size(max = 45) String remoteIp) {

    @Override
    public String toString() {
        return "PayInvoiceWithCardRequestDTO[useForSubscription=" + useForSubscription + "]";
    }
}
