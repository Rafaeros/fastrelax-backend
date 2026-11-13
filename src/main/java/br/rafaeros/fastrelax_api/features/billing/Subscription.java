package br.rafaeros.fastrelax_api.features.billing;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import br.rafaeros.fastrelax_api.features.companies.BillingStatus;
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
 * Assinatura recorrente de uma empresa cliente.
 */
@Entity
@Table(name = "subscriptions")
@NoArgsConstructor
@Getter
@Setter
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "company_id", nullable = false, unique = true)
    private Company company;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_type", nullable = false, length = 20)
    private BillingType billingType = BillingType.PIX;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BillingStatus status = BillingStatus.PENDING;

    @Column(name = "gateway_provider", nullable = false, length = 30)
    private String gatewayProvider = "ASAAS";

    @Column(name = "gateway_subscription_id", length = 100)
    private String gatewaySubscriptionId;

    /**
     * Bandeira e últimos 4 dígitos do cartão, só para exibição. Número,
     * validade, CVV e até o token ficam no provedor de pagamento.
     */
    @Column(name = "card_brand", length = 20)
    private String cardBrand;

    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    @Column(name = "next_due_date", nullable = false)
    private LocalDate nextDueDate;

    /**
     * Quando verdadeiro, no fechamento do ciclo mede a quantidade de
     * colaboradores ativos ({@code active = true}) e reenquadra a assinatura
     * na faixa correspondente.
     */
    @Column(name = "auto_adjust_tier", nullable = false)
    private boolean autoAdjustTier = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;
}
