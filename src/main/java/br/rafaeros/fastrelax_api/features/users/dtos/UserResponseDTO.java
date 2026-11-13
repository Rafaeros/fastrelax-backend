package br.rafaeros.fastrelax_api.features.users.dtos;

import java.time.LocalDateTime;

import br.rafaeros.fastrelax_api.features.companies.BillingStatus;
import br.rafaeros.fastrelax_api.features.companies.CompanyType;
import br.rafaeros.fastrelax_api.features.users.User;
import br.rafaeros.fastrelax_api.features.users.UserRole;

public record UserResponseDTO (
    Long id,
    String name,
    String email,
    UserRole role,
    /** Mesmo perfil em português, pronto para exibição. */
    String roleLabel,
    /** Nulo para a equipe da plataforma, que não pertence a nenhuma empresa. */
    Long companyId,
    String companyName,
    CompanyType companyType,
    BillingStatus companyBillingStatus,
    String companyBillingStatusLabel,
    LocalDateTime companyGraceUntil,
    /** Verdadeiro enquanto o usuário ainda não definiu a própria senha. */
    boolean mustChangePassword,
    boolean active
){
    public UserResponseDTO(User user) {
        this(user.getId(), user.getName(), user.getEmail(), user.getRole(),
                user.getRole() != null ? user.getRole().getLabel() : null,
                user.getCompany() != null ? user.getCompany().getId() : null,
                user.getCompany() != null ? user.getCompany().getName() : null,
                user.getCompany() != null ? user.getCompany().getType() : null,
                user.getCompany() != null ? user.getCompany().getBillingStatus() : null,
                user.getCompany() != null && user.getCompany().getBillingStatus() != null
                        ? user.getCompany().getBillingStatus().getLabel()
                        : null,
                user.getCompany() != null ? user.getCompany().getGraceUntil() : null,
                user.isMustChangePassword(), user.isActive());
    }
}
