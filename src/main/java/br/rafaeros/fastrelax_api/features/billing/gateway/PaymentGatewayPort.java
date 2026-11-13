package br.rafaeros.fastrelax_api.features.billing.gateway;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import br.rafaeros.fastrelax_api.features.billing.BillingType;
import br.rafaeros.fastrelax_api.features.billing.InvoiceStatus;
import br.rafaeros.fastrelax_api.features.companies.Company;

/**
 * Porta hexagonal para comunicação com gateways de pagamento (Asaas, Trio,
 * Mercado Pago).
 *
 * <p>
 * Atende tanto o Modelo A (assinatura recorrente e faturas B2B da plataforma)
 * quanto o Modelo B (cobrança avulsa Pix, verificação de status e estorno
 * automático da sessão).
 *
 * <p>
 * Dados de cartão só atravessam esta porta em {@link #tokenizeCreditCard}: o
 * número, a validade e o CVV seguem direto para o provedor e voltam como um
 * token. Nada disso é persistido nem registrado em log.
 */
public interface PaymentGatewayPort {

    String providerName();

    /**
     * Falso quando não há chave do provedor configurada e o adapter simula as
     * respostas localmente (desenvolvimento). Nesse modo a assinatura não gera
     * cobranças sozinha, então quem chama emite a fatura do ciclo.
     */
    boolean isLive();

    String ensureCustomer(Company company);

    /**
     * Cria ou atualiza a assinatura mensal.
     *
     * @param creditCardToken token de {@link #tokenizeCreditCard}, obrigatório
     *                        quando {@code billingType} é cartão e a assinatura
     *                        é nova; nulo nos demais casos
     * @param remoteIp        IP de quem está pagando, exigido pelo provedor na
     *                        cobrança por cartão
     */
    GatewaySubscriptionResult createOrUpdateSubscription(
            String customerId,
            String existingSubscriptionId,
            BillingType billingType,
            long amountCents,
            LocalDate nextDueDate,
            String description,
            String externalReference,
            String creditCardToken,
            String remoteIp);

    void cancelSubscription(String gatewaySubscriptionId);

    /** Troca o cartão de uma assinatura já existente, sem cobrar nada na hora. */
    void updateSubscriptionCreditCard(String gatewaySubscriptionId, String creditCardToken, String remoteIp);

    /** Token do cartão já vinculado à assinatura no provedor, ou nulo se não houver. */
    String getSubscriptionCardToken(String gatewaySubscriptionId);

    /** Valida o cartão no provedor e devolve o token que o representa. */
    CardTokenResult tokenizeCreditCard(String customerId, CreditCardData card, CardHolderData holder,
            String remoteIp);

    /** Cobranças geradas pela assinatura (a mais recente primeiro). */
    List<GatewayChargeStatus> listSubscriptionCharges(String gatewaySubscriptionId);

    GatewayChargeResult createCharge(GatewayChargeRequest request);

    /**
     * Paga no cartão uma cobrança que já existe (fatura em aberto), sem mandar
     * ninguém para a página do provedor. Devolve o estado da cobrança logo após
     * a tentativa; quem decide se conta como pago é a reconsulta.
     */
    GatewayChargeStatus payChargeWithCreditCard(String chargeId, String creditCardToken, String remoteIp);

    /** Cancela (remove) uma cobrança não paga no provedor. */
    void cancelCharge(String chargeId);

    GatewayChargeStatus getChargeStatus(String externalId);

    GatewayRefundResult refund(String externalId, Long amountCents, String reason);

    GatewayWebhookPayload parseWebhook(Map<String, String> headers, String body);

    record GatewayChargeRequest(
            String customerId,
            String externalReference,
            BillingType billingType,
            long amountCents,
            LocalDate dueDate,
            String description
    ) {}

    record GatewaySubscriptionResult(
            String subscriptionId,
            String status
    ) {}

    record GatewayChargeResult(
            String chargeId,
            InvoiceStatus status,
            String invoiceUrl,
            String pixCopyPaste,
            String pixQrCodeBase64
    ) {}

    /**
     * Estado de uma cobrança lido do provedor. {@code subscriptionId},
     * {@code dueDate} e {@code invoiceUrl} podem vir nulos (cobrança avulsa ou
     * modo simulado).
     */
    record GatewayChargeStatus(
            String chargeId,
            String externalReference,
            InvoiceStatus status,
            long amountCents,
            String subscriptionId,
            LocalDate dueDate,
            String invoiceUrl
    ) {
        public GatewayChargeStatus(String chargeId, String externalReference, InvoiceStatus status, long amountCents) {
            this(chargeId, externalReference, status, amountCents, null, null, null);
        }
    }

    record GatewayRefundResult(
            String refundId,
            boolean refunded
    ) {}

    record GatewayWebhookPayload(
            String eventId,
            String eventType,
            String chargeId,
            String subscriptionId,
            String externalReference,
            InvoiceStatus mappedStatus
    ) {}

    /**
     * Cartão como digitado. Só existe em memória durante a tokenização; o
     * {@code toString} mascara tudo para que um log acidental não vaze o PAN
     * nem o CVV.
     */
    record CreditCardData(
            String holderName,
            String number,
            String expiryMonth,
            String expiryYear,
            String ccv
    ) {
        public String lastFour() {
            String digits = number == null ? "" : number.replaceAll("\\D", "");
            return digits.length() >= 4 ? digits.substring(digits.length() - 4) : "";
        }

        @Override
        public String toString() {
            return "CreditCardData[****" + lastFour() + "]";
        }
    }

    /** Titular do cartão, exigido pelo provedor para análise antifraude. */
    record CardHolderData(
            String name,
            String email,
            String cpfCnpj,
            String postalCode,
            String addressNumber,
            String addressComplement,
            String phone
    ) {
        @Override
        public String toString() {
            return "CardHolderData[" + (name == null ? "" : name) + "]";
        }
    }

    /** Token do cartão no provedor, mais o que dá para mostrar com segurança. */
    record CardTokenResult(
            String token,
            String brand,
            String lastFour
    ) {
        @Override
        public String toString() {
            return "CardTokenResult[" + brand + " ****" + lastFour + "]";
        }
    }
}
