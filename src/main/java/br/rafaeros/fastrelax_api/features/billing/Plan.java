package br.rafaeros.fastrelax_api.features.billing;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Plano comercial do Modelo A (Corporativo B2B), precificado por faixa de
 * colaboradores ativos e franquia de cadeiras.
 */
@Entity
@Table(name = "plans")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor
@Getter
@Setter
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "min_collaborators", nullable = false)
    private int minCollaborators = 1;

    /** Nulo significa sem limite superior ("Acima de N colaboradores"). */
    @Column(name = "max_collaborators")
    private Integer maxCollaborators;

    /** Valor mensal da faixa em centavos (ex.: 49000 = R$ 490,00). */
    @Column(name = "monthly_price_cents", nullable = false)
    private long monthlyPriceCents;

    /** Quantidade de cadeiras já incluídas na mensalidade da faixa. */
    @Column(name = "included_chairs", nullable = false)
    private int includedChairs = 1;

    /** Valor mensal por cadeira adicional além de {@link #includedChairs}, em centavos. */
    @Column(name = "extra_chair_price_cents", nullable = false)
    private long extraChairPriceCents = 0;

    /** Taxa única de setup (ESP32 + instalação) por cadeira, em centavos. */
    @Column(name = "setup_fee_per_chair_cents", nullable = false)
    private long setupFeePerChairCents = 0;

    /** Dias de carência após vencimento da fatura antes de suspender o tenant (padrão: 5). */
    @Column(name = "grace_days", nullable = false)
    private int graceDays = 5;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public boolean matchesCollaboratorCount(long activeCollaborators) {
        if (!active || deletedAt != null) {
            return false;
        }
        if (activeCollaborators < minCollaborators) {
            return false;
        }
        return maxCollaborators == null || activeCollaborators <= maxCollaborators;
    }

    public long calculateCycleAmountCents(int activeChairs, boolean includeSetup) {
        int extraChairs = Math.max(0, activeChairs - includedChairs);
        long total = monthlyPriceCents + (extraChairs * extraChairPriceCents);
        if (includeSetup && activeChairs > 0) {
            total += activeChairs * setupFeePerChairCents;
        }
        return total;
    }
}
