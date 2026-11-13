package br.rafaeros.fastrelax_api.features.billing.dtos;

import br.rafaeros.fastrelax_api.features.billing.gateway.PaymentGatewayPort.CardHolderData;
import br.rafaeros.fastrelax_api.features.billing.gateway.PaymentGatewayPort.CreditCardData;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Assinatura recorrente no cartão de crédito.
 *
 * <p>
 * O cartão só passa por aqui a caminho do provedor, que devolve um token; nada
 * dele é gravado. Os {@code toString} mascaram o conteúdo para que nenhum log
 * de requisição ou de erro exponha número ou CVV. As mensagens de validação não
 * repetem o valor recusado.
 */
public record CreditCardSubscriptionRequestDTO(
        @NotNull(message = "Escolha um plano") Long planId,
        @NotNull(message = "Informe o cartão") @Valid Card card,
        @NotNull(message = "Informe o titular") @Valid Holder holder,
        /** IP do navegador de quem paga, repassado pelo servidor web. */
        @Size(max = 45) String remoteIp) {

    public record Card(
            @NotBlank(message = "Informe o nome impresso no cartão") @Size(max = 100) String holderName,
            @NotBlank(message = "Informe o número do cartão")
            @Pattern(regexp = "\\d{13,19}", message = "Número do cartão inválido") String number,
            @NotBlank(message = "Informe o mês de validade")
            @Pattern(regexp = "0[1-9]|1[0-2]", message = "Mês de validade inválido") String expiryMonth,
            @NotBlank(message = "Informe o ano de validade")
            @Pattern(regexp = "20\\d{2}", message = "Ano de validade inválido") String expiryYear,
            @NotBlank(message = "Informe o código de segurança")
            @Pattern(regexp = "\\d{3,4}", message = "Código de segurança inválido") String ccv) {

        public CreditCardData toGateway() {
            return new CreditCardData(holderName.trim(), number, expiryMonth, expiryYear, ccv);
        }

        @Override
        public String toString() {
            return "Card[****]";
        }
    }

    public record Holder(
            @NotBlank(message = "Informe o nome do titular") @Size(max = 120) String name,
            @NotBlank(message = "Informe o e-mail do titular") @Email(message = "E-mail do titular inválido") String email,
            @NotBlank(message = "Informe o CPF ou CNPJ do titular")
            @Pattern(regexp = "\\d{11}|\\d{14}", message = "CPF ou CNPJ do titular inválido") String cpfCnpj,
            @NotBlank(message = "Informe o CEP do titular")
            @Pattern(regexp = "\\d{8}", message = "CEP inválido") String postalCode,
            @NotBlank(message = "Informe o número do endereço") @Size(max = 10) String addressNumber,
            @Size(max = 60) String addressComplement,
            @NotBlank(message = "Informe o telefone do titular")
            @Pattern(regexp = "\\d{10,11}", message = "Telefone inválido") String phone) {

        public CardHolderData toGateway() {
            return new CardHolderData(name.trim(), email.trim(), cpfCnpj, postalCode, addressNumber.trim(),
                    addressComplement, phone);
        }

        @Override
        public String toString() {
            return "Holder[****]";
        }
    }

    @Override
    public String toString() {
        return "CreditCardSubscriptionRequestDTO[planId=" + planId + "]";
    }
}
