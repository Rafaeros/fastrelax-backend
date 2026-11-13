package br.rafaeros.fastrelax_api.features.audit;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Trilha de auditoria imutável (LGPD, cobrança e segurança operacional).
 *
 * <p>
 * Registra quem executou a ação, em qual empresa, sobre qual alvo e por qual
 * motivo (ex.: desativação de cadeira por risco elétrico, suspensão automática
 * por fim da carência, exportação de planilha ou anonimização de colaborador).
 */
@Entity
@Table(name = "audit_logs")
@NoArgsConstructor
@Getter
@Setter
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id")
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 30)
    private AuditActorType actorType;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_name", length = 180)
    private String actorName;

    @Column(nullable = false, length = 80)
    private String action;

    @Column(name = "target_type", nullable = false, length = 60)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String details;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
