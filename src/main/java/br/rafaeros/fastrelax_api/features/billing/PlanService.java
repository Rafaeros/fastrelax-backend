package br.rafaeros.fastrelax_api.features.billing;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.rafaeros.fastrelax_api.core.exceptions.BusinessException;
import br.rafaeros.fastrelax_api.core.exceptions.ResourceNotFoundException;
import br.rafaeros.fastrelax_api.features.audit.AuditLogService;
import br.rafaeros.fastrelax_api.features.billing.dtos.PlanResponseDTO;
import br.rafaeros.fastrelax_api.features.billing.dtos.SavePlanRequestDTO;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PlanService {

    private final PlanRepository planRepository;
    private final AuditLogService auditLogService;

    public Page<PlanResponseDTO> findAll(@org.springframework.lang.NonNull Pageable pageable) {
        return planRepository.findAllByOrderByMinCollaboratorsAsc(Objects.requireNonNull(pageable))
                .map(PlanResponseDTO::new);
    }

    public List<PlanResponseDTO> listActive() {
        return planRepository.findByActiveTrueOrderByMinCollaboratorsAsc().stream()
                .map(PlanResponseDTO::new)
                .toList();
    }

    public PlanResponseDTO findById(Long id) {
        return new PlanResponseDTO(findEntityById(id));
    }

    public Plan findEntityById(Long id) {
        return planRepository.findById(Objects.requireNonNull(id))
                .orElseThrow(() -> new ResourceNotFoundException("Plano não encontrado"));
    }

    /**
     * Descobre o plano ativo cuja faixa de colaboradores cobre a contagem
     * informada. Se a empresa crescer além da maior faixa com teto, cai no
     * último plano ativo ordenado por {@code minCollaborators}.
     */
    public Plan resolveTierForCollaborators(long activeCollaborators) {
        List<Plan> activePlans = planRepository.findByActiveTrueOrderByMinCollaboratorsAsc();
        if (activePlans.isEmpty()) {
            throw new BusinessException("Nenhum plano ativo cadastrado na plataforma");
        }
        return activePlans.stream()
                .filter(plan -> plan.matchesCollaboratorCount(activeCollaborators))
                .findFirst()
                .orElse(activePlans.get(activePlans.size() - 1));
    }

    @Transactional
    public PlanResponseDTO create(SavePlanRequestDTO dto) {
        validateTierBounds(dto.minCollaborators(), dto.maxCollaborators());
        Plan plan = new Plan();
        applyFields(plan, dto);
        Plan saved = planRepository.save(plan);
        auditLogService.record(null, "PLAN_CREATED", "PLAN", saved.getId(),
                "Criação de plano comercial",
                saved.getName() + " (" + saved.getMinCollaborators() + ".."
                        + (saved.getMaxCollaborators() != null ? saved.getMaxCollaborators() : "∞") + ")");
        return new PlanResponseDTO(saved);
    }

    @Transactional
    public PlanResponseDTO update(Long id, SavePlanRequestDTO dto) {
        validateTierBounds(dto.minCollaborators(), dto.maxCollaborators());
        Plan plan = findEntityById(id);
        applyFields(plan, dto);
        Plan saved = planRepository.save(plan);
        auditLogService.record(null, "PLAN_UPDATED", "PLAN", saved.getId(),
                "Atualização de plano comercial", saved.getName());
        return new PlanResponseDTO(saved);
    }

    @Transactional
    public PlanResponseDTO toggleActive(Long id) {
        Plan plan = findEntityById(id);
        plan.setActive(!plan.isActive());
        Plan saved = planRepository.save(plan);
        auditLogService.record(null, saved.isActive() ? "PLAN_ACTIVATED" : "PLAN_DEACTIVATED", "PLAN",
                saved.getId(), null, saved.getName());
        return new PlanResponseDTO(saved);
    }

    @Transactional
    public void softDelete(Long id) {
        Plan plan = findEntityById(id);
        plan.setActive(false);
        plan.setDeletedAt(LocalDateTime.now());
        planRepository.save(plan);
        auditLogService.record(null, "PLAN_DELETED", "PLAN", plan.getId(), null, plan.getName());
    }

    private void validateTierBounds(Integer min, Integer max) {
        if (max != null && max < min) {
            throw new BusinessException(
                    "O máximo de colaboradores da faixa deve ser maior ou igual ao mínimo");
        }
    }

    private void applyFields(Plan plan, SavePlanRequestDTO dto) {
        plan.setName(dto.name().trim());
        plan.setDescription(dto.description() != null && !dto.description().isBlank()
                ? dto.description().trim()
                : null);
        plan.setMinCollaborators(dto.minCollaborators());
        plan.setMaxCollaborators(dto.maxCollaborators());
        plan.setMonthlyPriceCents(dto.monthlyPriceCents());
        plan.setIncludedChairs(dto.includedChairs());
        plan.setExtraChairPriceCents(dto.extraChairPriceCents());
        plan.setSetupFeePerChairCents(dto.setupFeePerChairCents());
        plan.setGraceDays(dto.graceDays());
    }
}
