package br.rafaeros.fastrelax_api.features.billing;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import br.rafaeros.fastrelax_api.features.companies.Company;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Fatura emitida para uma empresa cliente.
 */
@Entity
@Table(name = "invoices")
@NoArgsConstructor
@Getter
@Setter
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "subscription_id")
    private Subscription subscription;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "plan_id")
    private Plan plan;

    /** Competência no formato YYYY-MM (ex.: 2026-11). */
    @Column(name = "reference_month", length = 7)
    private String referenceMonth;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(name = "active_collaborators_count", nullable = false)
    private int activeCollaboratorsCount = 0;

    @Column(name = "active_chairs_count", nullable = false)
    private int activeChairsCount = 0;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_type", nullable = false, length = 20)
    private BillingType billingType = BillingType.PIX;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvoiceStatus status = InvoiceStatus.PENDING;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "gateway_provider", nullable = false, length = 30)
    private String gatewayProvider = "ASAAS";

    @Column(name = "gateway_charge_id", length = 100)
    private String gatewayChargeId;

    @Column(name = "invoice_url", columnDefinition = "TEXT")
    private String invoiceUrl;

    @Column(name = "pix_copy_paste", columnDefinition = "TEXT")
    private String pixCopyPaste;

    @Column(name = "pix_qr_code_base64", columnDefinition = "TEXT")
    private String pixQrCodeBase64;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
