package br.rafaeros.fastrelax_api.features.billing.gateway;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.rafaeros.fastrelax_api.core.exceptions.BusinessException;
import br.rafaeros.fastrelax_api.features.billing.BillingType;
import br.rafaeros.fastrelax_api.features.billing.InvoiceStatus;
import br.rafaeros.fastrelax_api.features.companies.Company;

/**
 * Adapter Asaas v3 para {@link PaymentGatewayPort}.
 *
 * <p>
 * Com {@code app.billing.asaas.api-key} configurada, fala com a API v3 do Asaas
 * (produção ou sandbox). Sem a chave (desenvolvimento local), simula as
 * respostas: identificadores, Pix Copia-e-Cola, QR Code e tokenização de cartão
 * — e avisa no log de inicialização, porque cair nesse modo em produção
 * significa faturas falsas.
 *
 * <p>
 * Mensagens de erro que sobem para o usuário nunca citam o provedor nem trazem
 * o corpo da resposta: o detalhe técnico vai só para o log.
 */
@Component
public class AsaasAdapter implements PaymentGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(AsaasAdapter.class);
    private static final String PROVIDER = "ASAAS";
    private static final String UNAVAILABLE =
            "O serviço de pagamento não respondeu. Tente de novo em alguns minutos.";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String webhookSecret;

    /** Estado em memória para reconsulta de status quando rodando em modo simulado local. */
    private final Map<String, GatewayChargeStatus> simulatedCharges = new ConcurrentHashMap<>();

    public AsaasAdapter(
            @Value("${app.billing.asaas.base-url:https://sandbox.asaas.com/api/v3}") String baseUrl,
            @Value("${app.billing.asaas.api-key:}") String apiKey,
            @Value("${app.billing.asaas.webhook-secret:}") String webhookSecret,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.restClient = RestClient.builder().requestFactory(factory).build();

        if (this.apiKey.isEmpty()) {
            log.warn("Cobrança em MODO SIMULADO: ASAAS_API_KEY vazia. Nenhuma cobrança real será criada.");
        }
        if (this.webhookSecret.isEmpty()) {
            log.warn("ASAAS_WEBHOOK_SECRET vazio: todo webhook de pagamento será recusado.");
        }
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public boolean isLive() {
        return !apiKey.isEmpty();
    }

    /** Mantido para chamadas existentes; prefira {@link #isLive()}. */
    public boolean isLiveConfigured() {
        return isLive();
    }

    @Override
    public String ensureCustomer(Company company) {
        if (company.getGatewayCustomerId() != null && !company.getGatewayCustomerId().isBlank()) {
            return company.getGatewayCustomerId();
        }
        if (!isLive()) {
            return "cus_sim_" + company.getId();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", company.getName());
        body.put("cpfCnpj", company.getCnpj());
        body.put("email", company.getEmail());
        body.put("phone", company.getPhone());
        body.put("externalReference", "company:" + company.getId());

        JsonNode response = send(HttpMethod.POST, "/customers", body);
        String customerId = response.path("id").asText(null);
        if (customerId == null || customerId.isBlank()) {
            log.error("Criação de cliente sem id na resposta (empresa {})", company.getId());
            throw new BusinessException(UNAVAILABLE);
        }
        return customerId;
    }

    @Override
    public GatewaySubscriptionResult createOrUpdateSubscription(
            String customerId,
            String existingSubscriptionId,
            BillingType billingType,
            long amountCents,
            LocalDate nextDueDate,
            String description,
            String externalReference,
            String creditCardToken,
            String remoteIp) {
        boolean update = existingSubscriptionId != null && !existingSubscriptionId.isBlank();
        if (!isLive()) {
            String subId = update ? existingSubscriptionId
                    : "sub_sim_" + UUID.randomUUID().toString().substring(0, 8);
            return new GatewaySubscriptionResult(subId, "ACTIVE");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("billingType", toAsaasBillingType(billingType));
        body.put("value", toReais(amountCents));
        body.put("nextDueDate", nextDueDate.toString());
        body.put("description", description);
        body.put("externalReference", externalReference);

        JsonNode response;
        if (update) {
            // Reprecifica também as cobranças já geradas e ainda não pagas.
            body.put("updatePendingPayments", true);
            response = send(HttpMethod.PUT, "/subscriptions/" + existingSubscriptionId, body);
        } else {
            body.put("customer", customerId);
            body.put("cycle", "MONTHLY");
            if (billingType == BillingType.CREDIT_CARD) {
                if (creditCardToken == null || creditCardToken.isBlank()) {
                    throw new BusinessException("Informe um cartão de crédito para a assinatura.");
                }
                body.put("creditCardToken", creditCardToken);
                body.put("remoteIp", remoteIp);
            }
            response = send(HttpMethod.POST, "/subscriptions", body);
        }
        return new GatewaySubscriptionResult(
                response.path("id").asText(existingSubscriptionId),
                response.path("status").asText("ACTIVE"));
    }

    @Override
    public void cancelSubscription(String gatewaySubscriptionId) {
        if (gatewaySubscriptionId == null || gatewaySubscriptionId.isBlank() || !isLive()) {
            return;
        }
        try {
            restClient.delete()
                    .uri(baseUrl + "/subscriptions/" + gatewaySubscriptionId)
                    .header("access_token", apiKey)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                    })
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Falha ao cancelar assinatura {} no provedor: {}", gatewaySubscriptionId, e.getMessage());
        }
    }

    @Override
    public void updateSubscriptionCreditCard(String gatewaySubscriptionId, String creditCardToken, String remoteIp) {
        if (!isLive()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("creditCardToken", creditCardToken);
        body.put("remoteIp", remoteIp);
        send(HttpMethod.PUT, "/subscriptions/" + gatewaySubscriptionId + "/creditCard", body);
    }

    @Override
    public String getSubscriptionCardToken(String gatewaySubscriptionId) {
        if (gatewaySubscriptionId == null || gatewaySubscriptionId.isBlank()) {
            return null;
        }
        if (!isLive()) {
            return "tok_sim_" + gatewaySubscriptionId;
        }
        JsonNode response = send(HttpMethod.GET, "/subscriptions/" + gatewaySubscriptionId, null);
        String token = response.path("creditCard").path("creditCardToken").asText(null);
        return (token != null && !token.isBlank()) ? token : null;
    }

    @Override
    public CardTokenResult tokenizeCreditCard(String customerId, CreditCardData card, CardHolderData holder,
            String remoteIp) {
        String digits = card.number() == null ? "" : card.number().replaceAll("\\D", "");
        if (!isLive()) {
            if (!passesLuhn(digits)) {
                throw new BusinessException("Número do cartão inválido. Confira os dígitos.");
            }
            return new CardTokenResult("tok_sim_" + UUID.randomUUID(), detectBrand(digits), card.lastFour());
        }

        Map<String, Object> creditCard = new LinkedHashMap<>();
        creditCard.put("holderName", card.holderName());
        creditCard.put("number", digits);
        creditCard.put("expiryMonth", card.expiryMonth());
        creditCard.put("expiryYear", card.expiryYear());
        creditCard.put("ccv", card.ccv());

        Map<String, Object> holderInfo = new LinkedHashMap<>();
        holderInfo.put("name", holder.name());
        holderInfo.put("email", holder.email());
        holderInfo.put("cpfCnpj", holder.cpfCnpj());
        holderInfo.put("postalCode", holder.postalCode());
        holderInfo.put("addressNumber", holder.addressNumber());
        if (holder.addressComplement() != null && !holder.addressComplement().isBlank()) {
            holderInfo.put("addressComplement", holder.addressComplement());
        }
        holderInfo.put("phone", holder.phone());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customer", customerId);
        body.put("creditCard", creditCard);
        body.put("creditCardHolderInfo", holderInfo);
        body.put("remoteIp", remoteIp);

        JsonNode response = send(HttpMethod.POST, "/creditCard/tokenize", body);
        String token = response.path("creditCardToken").asText(null);
        if (token == null || token.isBlank()) {
            log.error("Tokenização de cartão sem token na resposta (cliente {})", customerId);
            throw new BusinessException("Não foi possível validar o cartão. Confira os dados ou use outro cartão.");
        }
        String brand = response.path("creditCardBrand").asText(detectBrand(digits));
        String last4 = response.path("creditCardNumber").asText(card.lastFour());
        return new CardTokenResult(token, brand, last4);
    }

    @Override
    public List<GatewayChargeStatus> listSubscriptionCharges(String gatewaySubscriptionId) {
        if (!isLive() || gatewaySubscriptionId == null || gatewaySubscriptionId.isBlank()) {
            return List.of();
        }
        JsonNode response = send(HttpMethod.GET, "/subscriptions/" + gatewaySubscriptionId + "/payments", null);
        List<GatewayChargeStatus> charges = new ArrayList<>();
        for (JsonNode node : response.path("data")) {
            charges.add(toChargeStatus(node, node.path("id").asText()));
        }
        return charges;
    }

    @Override
    public GatewayChargeResult createCharge(GatewayChargeRequest request) {
        if (!isLive()) {
            String chargeId = "pay_sim_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String pixCopyPaste = buildSimulatedPixPayload(chargeId, request.amountCents(), request.description());
            String qrBase64 = buildSimulatedQrSvgBase64(chargeId, request.amountCents());
            String invoiceUrl = "https://sandbox.asaas.com/i/" + chargeId;

            simulatedCharges.put(chargeId, new GatewayChargeStatus(
                    chargeId,
                    request.externalReference(),
                    InvoiceStatus.PENDING,
                    request.amountCents()));

            return new GatewayChargeResult(
                    chargeId,
                    InvoiceStatus.PENDING,
                    invoiceUrl,
                    pixCopyPaste,
                    qrBase64);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customer", request.customerId());
        body.put("billingType", toAsaasBillingType(request.billingType()));
        body.put("value", toReais(request.amountCents()));
        body.put("dueDate", request.dueDate().toString());
        body.put("description", request.description());
        body.put("externalReference", request.externalReference());

        JsonNode paymentNode = send(HttpMethod.POST, "/payments", body);
        String chargeId = paymentNode.path("id").asText();
        String invoiceUrl = paymentNode.path("invoiceUrl").asText(null);
        InvoiceStatus status = mapAsaasStatus(paymentNode.path("status").asText("PENDING"));

        String pixCopyPaste = null;
        String pixQrBase64 = null;
        if (request.billingType() == BillingType.PIX && !chargeId.isBlank()) {
            PixData pix = fetchPix(chargeId);
            pixCopyPaste = pix.copyPaste();
            pixQrBase64 = pix.qrBase64();
        }

        return new GatewayChargeResult(chargeId, status, invoiceUrl, pixCopyPaste, pixQrBase64);
    }

    @Override
    public GatewayChargeStatus payChargeWithCreditCard(String chargeId, String creditCardToken, String remoteIp) {
        if (!isLive()) {
            GatewayChargeStatus current = simulatedCharges.get(chargeId);
            long cents = current != null ? current.amountCents() : 0L;
            String ref = current != null ? current.externalReference() : null;
            updateSimulatedStatus(chargeId, InvoiceStatus.PAID, cents, ref);
            return simulatedCharges.get(chargeId);
        }

        // Cobrança emitida como Pix ou boleto precisa virar cartão antes de
        // aceitar o pagamento no cartão.
        JsonNode payment = send(HttpMethod.GET, "/payments/" + chargeId, null);
        String billingType = payment.path("billingType").asText("");
        if (!"CREDIT_CARD".equals(billingType) && !"UNDEFINED".equals(billingType)) {
            send(HttpMethod.PUT, "/payments/" + chargeId, Map.of("billingType", "CREDIT_CARD"));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("creditCardToken", creditCardToken);
        body.put("remoteIp", remoteIp);
        return toChargeStatus(send(HttpMethod.POST, "/payments/" + chargeId + "/payWithCreditCard", body), chargeId);
    }

    @Override
    public void cancelCharge(String chargeId) {
        if (!isLive() || chargeId.startsWith("pay_sim_")) {
            GatewayChargeStatus current = simulatedCharges.get(chargeId);
            updateSimulatedStatus(chargeId, InvoiceStatus.CANCELLED,
                    current != null ? current.amountCents() : 0L, current != null ? current.externalReference() : null);
            return;
        }
        send(HttpMethod.DELETE, "/payments/" + chargeId, null);
    }

    /** Pix Copia-e-Cola e QR Code de uma cobrança; falha vira campos vazios. */
    public PixData fetchPix(String chargeId) {
        if (!isLive() || chargeId == null || chargeId.isBlank()) {
            return new PixData(null, null);
        }
        try {
            JsonNode pixNode = send(HttpMethod.GET, "/payments/" + chargeId + "/pixQrCode", null);
            return new PixData(pixNode.path("payload").asText(null), pixNode.path("encodedImage").asText(null));
        } catch (Exception e) {
            log.warn("Não foi possível obter o QR Code Pix da cobrança {}: {}", chargeId, e.getMessage());
            return new PixData(null, null);
        }
    }

    public record PixData(String copyPaste, String qrBase64) {}

    @Override
    public GatewayChargeStatus getChargeStatus(String externalId) {
        if (!isLive()) {
            return simulatedCharges.getOrDefault(
                    externalId,
                    new GatewayChargeStatus(externalId, null, InvoiceStatus.PENDING, 0L));
        }
        if (externalId.startsWith("pay_sim_")) {
            // Cobrança criada em modo simulado não existe no provedor: sem
            // status verificável, nada muda (nunca confirmar pelo payload).
            return new GatewayChargeStatus(externalId, null, null, 0L);
        }
        return toChargeStatus(send(HttpMethod.GET, "/payments/" + externalId, null), externalId);
    }

    private GatewayChargeStatus toChargeStatus(JsonNode node, String fallbackId) {
        long cents = node.hasNonNull("value")
                ? BigDecimal.valueOf(node.path("value").asDouble())
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValue()
                : 0L;
        LocalDate dueDate = null;
        String rawDue = node.path("dueDate").asText(null);
        if (rawDue != null && !rawDue.isBlank()) {
            dueDate = LocalDate.parse(rawDue);
        }
        return new GatewayChargeStatus(
                node.path("id").asText(fallbackId),
                node.path("externalReference").asText(null),
                mapAsaasStatus(node.path("status").asText("PENDING")),
                cents,
                node.path("subscription").asText(null),
                dueDate,
                node.path("invoiceUrl").asText(null));
    }

    /** Atualiza o estado simulado local (usado só pelo simulador do SYSADMIN). */
    public void updateSimulatedStatus(String externalId, InvoiceStatus status, long amountCents, String externalRef) {
        if (externalId != null) {
            simulatedCharges.put(externalId, new GatewayChargeStatus(externalId, externalRef, status, amountCents));
        }
    }

    @Override
    public GatewayRefundResult refund(String externalId, Long amountCents, String reason) {
        if (!isLive() || externalId.startsWith("pay_sim_")) {
            updateSimulatedStatus(externalId, InvoiceStatus.REFUNDED, amountCents != null ? amountCents : 0L, null);
            return new GatewayRefundResult("ref_sim_" + UUID.randomUUID().toString().substring(0, 8), true);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        if (amountCents != null && amountCents > 0) {
            body.put("value", toReais(amountCents));
        }
        if (reason != null && !reason.isBlank()) {
            body.put("description", reason);
        }
        JsonNode response = send(HttpMethod.POST, "/payments/" + externalId + "/refund", body);
        String status = response.path("status").asText("");
        return new GatewayRefundResult(
                response.path("id").asText(externalId),
                "REFUNDED".equalsIgnoreCase(status) || "REFUND_REQUESTED".equalsIgnoreCase(status));
    }

    /**
     * Autentica e interpreta o webhook.
     *
     * <p>
     * Falha fechada: sem segredo configurado, recusa tudo — um endpoint público
     * que aceita qualquer chamada deixaria "confirmar pagamento" ao alcance de
     * quem souber o formato. O token é comparado em tempo constante. O status
     * do payload é só uma dica: quem decide é a reconsulta no
     * {@code BillingService}.
     */
    @Override
    public GatewayWebhookPayload parseWebhook(Map<String, String> headers, String body) {
        if (webhookSecret.isEmpty()) {
            throw new BusinessException("Webhook de pagamento não configurado");
        }
        String provided = findHeaderIgnoreCase(headers, "asaas-access-token");
        if (provided == null || !MessageDigest.isEqual(
                webhookSecret.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException("Segredo do webhook inválido ou ausente");
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            String eventId = root.path("id").asText("");
            String eventType = root.path("event").asText("");
            JsonNode payment = root.path("payment");
            String chargeId = payment.path("id").asText(null);
            String subscriptionId = payment.path("subscription").asText(null);
            String externalRef = payment.path("externalReference").asText(null);
            String rawStatus = payment.path("status").asText("");

            if (eventId.isBlank()) {
                // Sem id do evento, o próprio conteúdo identifica: dois eventos
                // iguais são o mesmo reenvio; dois diferentes não colidem.
                eventId = "sha256:" + HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
            }

            InvoiceStatus mapped = mapWebhookEventOrStatus(eventType, rawStatus);
            return new GatewayWebhookPayload(eventId, eventType, chargeId, subscriptionId, externalRef, mapped);
        } catch (Exception e) {
            log.warn("Webhook de pagamento com payload inválido: {}", e.getMessage());
            throw new BusinessException("Payload de webhook inválido");
        }
    }

    private InvoiceStatus mapWebhookEventOrStatus(String eventType, String rawStatus) {
        return switch (eventType) {
            case "PAYMENT_RECEIVED", "PAYMENT_CONFIRMED" -> InvoiceStatus.PAID;
            case "PAYMENT_OVERDUE" -> InvoiceStatus.OVERDUE;
            case "PAYMENT_DELETED" -> InvoiceStatus.CANCELLED;
            case "PAYMENT_REFUNDED", "PAYMENT_CHARGEBACK_REQUESTED", "PAYMENT_CHARGEBACK_DISPUTE" ->
                InvoiceStatus.REFUNDED;
            default -> mapAsaasStatus(rawStatus);
        };
    }

    private InvoiceStatus mapAsaasStatus(String status) {
        if (status == null) {
            return InvoiceStatus.PENDING;
        }
        return switch (status.toUpperCase()) {
            case "RECEIVED", "CONFIRMED", "RECEIVED_IN_CASH" -> InvoiceStatus.PAID;
            case "OVERDUE" -> InvoiceStatus.OVERDUE;
            // Chargeback desfaz o pagamento do ponto de vista do contrato.
            case "REFUNDED", "REFUND_REQUESTED", "REFUND_IN_PROGRESS",
                    "CHARGEBACK_REQUESTED", "CHARGEBACK_DISPUTE", "AWAITING_CHARGEBACK_REVERSAL" ->
                InvoiceStatus.REFUNDED;
            case "DELETED", "CANCELLED" -> InvoiceStatus.CANCELLED;
            default -> InvoiceStatus.PENDING;
        };
    }

    private String toAsaasBillingType(BillingType type) {
        if (type == null) {
            return "PIX";
        }
        return switch (type) {
            case PIX -> "PIX";
            case BOLETO -> "BOLETO";
            case CREDIT_CARD -> "CREDIT_CARD";
        };
    }

    private BigDecimal toReais(long cents) {
        return BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /**
     * Chamada à API. Erro do provedor vira uma mensagem para o usuário com a
     * descrição que ele mesmo devolve (ex.: "Cartão recusado"), nunca com o
     * corpo bruto; o corpo vai para o log. A requisição nunca é logada: na
     * tokenização ela carrega o cartão.
     */
    private JsonNode send(HttpMethod method, String path, Map<String, Object> payload) {
        ResponseEntity<String> response;
        try {
            RestClient.RequestBodySpec spec = restClient.method(method)
                    .uri(baseUrl + path)
                    .header("access_token", apiKey);
            if (payload != null) {
                spec = spec.contentType(MediaType.APPLICATION_JSON)
                        .body(objectMapper.writeValueAsString(payload));
            }
            response = spec.retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                    })
                    .toEntity(String.class);
        } catch (Exception e) {
            log.warn("Falha de comunicação com o provedor de pagamento ({} {}): {}", method, path, e.getMessage());
            throw new BusinessException(UNAVAILABLE);
        }

        if (!response.getStatusCode().is2xxSuccessful()) {
            log.warn("Provedor de pagamento recusou {} {} ({}): {}", method, path,
                    response.getStatusCode().value(), response.getBody());
            String described = describeErrors(response.getBody());
            if (response.getStatusCode().is4xxClientError() && described != null) {
                throw new BusinessException("Não foi possível concluir: " + described);
            }
            throw new BusinessException(UNAVAILABLE);
        }
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            log.warn("Resposta ilegível do provedor de pagamento ({} {})", method, path);
            throw new BusinessException(UNAVAILABLE);
        }
    }

    private String describeErrors(String body) {
        try {
            JsonNode errors = objectMapper.readTree(body).path("errors");
            List<String> parts = new ArrayList<>();
            for (JsonNode error : errors) {
                String description = error.path("description").asText("");
                if (!description.isBlank()) {
                    parts.add(description.trim());
                }
            }
            return parts.isEmpty() ? null : String.join(" ", parts);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean passesLuhn(String digits) {
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    private static String detectBrand(String digits) {
        if (digits.matches("^(4011|4312|4389|4514|4576|5041|5066|5067|509|6277|6362|6363|650|6516|6550).*")) {
            return "ELO";
        }
        if (digits.startsWith("4")) {
            return "VISA";
        }
        if (digits.matches("^(5[1-5]|2[2-7]).*")) {
            return "MASTERCARD";
        }
        if (digits.matches("^3[47].*")) {
            return "AMEX";
        }
        if (digits.matches("^(606282|3841).*")) {
            return "HIPERCARD";
        }
        return "UNKNOWN";
    }

    private String findHeaderIgnoreCase(Map<String, String> headers, String target) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(target)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String buildSimulatedPixPayload(String chargeId, long amountCents, String description) {
        String amount = toReais(amountCents).toPlainString();
        return "00020126580014br.gov.bcb.pix0136fastrelax-b2b-" + chargeId
                + "520400005303986540" + amount.length() + amount
                + "5802BR5913FASTRELAX B2B6009SAO PAULO62070503***6304ABCD";
    }

    private String buildSimulatedQrSvgBase64(String chargeId, long amountCents) {
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200" width="200" height="200">
                  <rect width="200" height="200" fill="#ffffff" rx="12"/>
                  <rect x="16" y="16" width="52" height="52" fill="none" stroke="#111827" stroke-width="8"/>
                  <rect x="30" y="30" width="24" height="24" fill="#111827"/>
                  <rect x="132" y="16" width="52" height="52" fill="none" stroke="#111827" stroke-width="8"/>
                  <rect x="146" y="30" width="24" height="24" fill="#111827"/>
                  <rect x="16" y="132" width="52" height="52" fill="none" stroke="#111827" stroke-width="8"/>
                  <rect x="30" y="146" width="24" height="24" fill="#111827"/>
                  <rect x="84" y="24" width="12" height="12" fill="#111827"/>
                  <rect x="104" y="36" width="12" height="24" fill="#111827"/>
                  <rect x="84" y="68" width="24" height="12" fill="#111827"/>
                  <rect x="24" y="84" width="36" height="12" fill="#111827"/>
                  <rect x="76" y="92" width="48" height="16" fill="#059669" rx="4"/>
                  <text x="100" y="104" font-family="monospace" font-size="10" fill="#ffffff" text-anchor="middle">PIX</text>
                  <rect x="136" y="84" width="16" height="32" fill="#111827"/>
                  <rect x="164" y="96" width="20" height="16" fill="#111827"/>
                  <rect x="84" y="124" width="16" height="28" fill="#111827"/>
                  <rect x="112" y="136" width="28" height="16" fill="#111827"/>
                  <rect x="148" y="128" width="32" height="32" fill="#111827"/>
                  <rect x="92" y="164" width="44" height="16" fill="#111827"/>
                </svg>
                """;
        return Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
    }
}
