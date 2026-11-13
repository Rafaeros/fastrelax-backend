package br.rafaeros.fastrelax_api.features.billing.dtos;

import java.time.LocalDate;
import java.time.LocalDateTime;

import br.rafaeros.fastrelax_api.features.billing.BillingType;
import br.rafaeros.fastrelax_api.features.billing.Invoice;
import br.rafaeros.fastrelax_api.features.billing.InvoiceStatus;

public record InvoiceResponseDTO(
    Long id,
    Long companyId,
    String companyName,
    Long subscriptionId,
    Long planId,
    String planName,
    String referenceMonth,
    String description,
    int activeCollaboratorsCount,
    int activeChairsCount,
    long amountCents,
    BillingType billingType,
    String billingTypeLabel,
    InvoiceStatus status,
    String statusLabel,
    LocalDate dueDate,
    LocalDateTime paidAt,
    String gatewayProvider,
    String gatewayChargeId,
    String invoiceUrl,
    String pixCopyPaste,
    String pixQrCodeBase64,
    LocalDateTime createdAt
) {
    public InvoiceResponseDTO(Invoice invoice) {
        this(
                invoice.getId(),
                invoice.getCompany().getId(),
                invoice.getCompany().getName(),
                invoice.getSubscription() != null ? invoice.getSubscription().getId() : null,
                invoice.getPlan() != null ? invoice.getPlan().getId() : null,
                invoice.getPlan() != null ? invoice.getPlan().getName() : null,
                invoice.getReferenceMonth(),
                invoice.getDescription(),
                invoice.getActiveCollaboratorsCount(),
                invoice.getActiveChairsCount(),
                invoice.getAmountCents(),
                invoice.getBillingType(),
                invoice.getBillingType() != null ? invoice.getBillingType().getLabel() : null,
                invoice.getStatus(),
                invoice.getStatus() != null ? invoice.getStatus().getLabel() : null,
                invoice.getDueDate(),
                invoice.getPaidAt(),
                invoice.getGatewayProvider(),
                invoice.getGatewayChargeId(),
                invoice.getInvoiceUrl(),
                invoice.getPixCopyPaste(),
                invoice.getPixQrCodeBase64(),
                invoice.getCreatedAt());
    }
}
