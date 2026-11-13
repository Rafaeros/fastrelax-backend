package br.rafaeros.fastrelax_api.features.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.rafaeros.fastrelax_api.core.security.Principals;
import br.rafaeros.fastrelax_api.core.tenancy.CurrentTenant;
import br.rafaeros.fastrelax_api.features.collaborators.Collaborator;
import br.rafaeros.fastrelax_api.features.users.User;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final CurrentTenant currentTenant;

    @Transactional
    public void record(Long companyId, String action, String targetType, Long targetId, String reason, String details) {
        AuditLog entry = new AuditLog();
        entry.setCompanyId(companyId);
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setReason(reason);
        entry.setDetails(details);

        User loggedUser = Principals.user().orElse(null);
        if (loggedUser != null) {
            entry.setActorType(AuditActorType.USER);
            entry.setActorId(loggedUser.getId());
            entry.setActorName(loggedUser.getName() + " <" + loggedUser.getEmail() + ">");
        } else {
            Collaborator loggedCollab = Principals.collaborator().orElse(null);
            if (loggedCollab != null) {
                entry.setActorType(AuditActorType.COLLABORATOR);
                entry.setActorId(loggedCollab.getId());
                entry.setActorName(loggedCollab.getName());
            } else {
                entry.setActorType(AuditActorType.SYSTEM);
                entry.setActorName("SYSTEM");
            }
        }

        auditLogRepository.save(entry);
    }

    @Transactional
    public void recordSystem(Long companyId, String action, String targetType, Long targetId, String reason,
            String details) {
        AuditLog entry = new AuditLog();
        entry.setCompanyId(companyId);
        entry.setActorType(AuditActorType.SYSTEM);
        entry.setActorName("SYSTEM");
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setReason(reason);
        entry.setDetails(details);
        auditLogRepository.save(entry);
    }

    @Transactional
    public void recordWebhook(Long companyId, String provider, String action, String targetType, Long targetId,
            String details) {
        AuditLog entry = new AuditLog();
        entry.setCompanyId(companyId);
        entry.setActorType(AuditActorType.WEBHOOK);
        entry.setActorName("WEBHOOK:" + provider);
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setDetails(details);
        auditLogRepository.save(entry);
    }

    public Page<AuditLogResponseDTO> listForPlatform(Long companyId, Pageable pageable) {
        if (companyId != null) {
            return auditLogRepository.findByCompanyIdOrderByCreatedAtDesc(companyId, pageable)
                    .map(AuditLogResponseDTO::new);
        }
        return auditLogRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(AuditLogResponseDTO::new);
    }

    public Page<AuditLogResponseDTO> listMine(Pageable pageable) {
        return auditLogRepository.findByCompanyIdOrderByCreatedAtDesc(currentTenant.companyId(), pageable)
                .map(AuditLogResponseDTO::new);
    }
}
