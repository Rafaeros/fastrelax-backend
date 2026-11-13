package br.rafaeros.fastrelax_api.features.audit;

import java.time.LocalDateTime;

public record AuditLogResponseDTO(
    Long id,
    Long companyId,
    AuditActorType actorType,
    Long actorId,
    String actorName,
    String action,
    String targetType,
    Long targetId,
    String reason,
    String details,
    LocalDateTime createdAt
) {
    public AuditLogResponseDTO(AuditLog log) {
        this(
                log.getId(),
                log.getCompanyId(),
                log.getActorType(),
                log.getActorId(),
                log.getActorName(),
                log.getAction(),
                log.getTargetType(),
                log.getTargetId(),
                log.getReason(),
                log.getDetails(),
                log.getCreatedAt());
    }
}
