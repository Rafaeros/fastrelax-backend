package br.rafaeros.fastrelax_api.features.billing;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import br.rafaeros.fastrelax_api.core.exceptions.BusinessException;
import br.rafaeros.fastrelax_api.features.collaborators.CollaboratorRepository;
import br.rafaeros.fastrelax_api.features.companies.BillingStatus;
import br.rafaeros.fastrelax_api.features.companies.Company;
import br.rafaeros.fastrelax_api.features.companies.CompanyRepository;
import br.rafaeros.fastrelax_api.features.companies.CompanyType;
import lombok.RequiredArgsConstructor;

/**
 * Limite de colaboradores ativos pelo plano contratado.
 *
 * <p>
 * Cada plano corporativo cobre uma faixa ({@code maxCollaborators}); o limite
 * vale em todo ponto que torna alguém ativo: cadastro, reativação de
 * removido, reativação de desativado e importação por planilha. Para crescer,
 * a empresa troca de plano em Faturas.
 *
 * <p>
 * Operador pay-per-use não tem plano nem limite.
 */
@Service
@RequiredArgsConstructor
public class CollaboratorQuotaService {

    private final SubscriptionRepository subscriptionRepository;
    private final CollaboratorRepository collaboratorRepository;
    private final CompanyRepository companyRepository;

    /**
     * Recusa se ativar mais {@code additional} colaboradores passar do plano.
     *
     * <p>
     * Roda dentro da transação de quem cadastra e trava a assinatura até o
     * commit, para que dois cadastros simultâneos não ultrapassem juntos o
     * limite.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireRoomFor(Long companyId, int additional) {
        String refusal = checkRoomFor(companyId, additional);
        if (refusal != null) {
            throw new BusinessException(refusal);
        }
    }

    /**
     * Mesma regra, mas devolve o motivo em vez de lançar: a importação trata
     * a recusa como erro da linha e segue. Uma exceção atravessando este proxy
     * marcaria a transação inteira da planilha para rollback.
     *
     * @return {@code null} se cabe; senão, a mensagem para o usuário
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String checkRoomFor(Long companyId, int additional) {
        Company company = companyRepository.findById(Objects.requireNonNull(companyId)).orElse(null);
        if (company == null) {
            return "Empresa não encontrada";
        }
        if (company.getType() == CompanyType.OPERATOR) {
            return null;
        }

        Subscription subscription = subscriptionRepository.findByCompanyIdForUpdate(companyId)
                .filter(s -> s.getStatus() != BillingStatus.CANCELLED)
                .orElse(null);
        if (subscription == null) {
            return "Escolha um plano em Faturas antes de cadastrar colaboradores.";
        }

        Plan plan = subscription.getPlan();
        Integer max = plan.getMaxCollaborators();
        if (max == null) {
            return null;
        }

        long active = collaboratorRepository.countByCompanyIdAndActiveTrue(companyId);
        if (active + additional > max) {
            return "O plano " + plan.getName() + " permite até " + max + " colaboradores ativos e a empresa já tem "
                    + active + ". Para cadastrar mais, troque de plano em Faturas ou desative quem saiu.";
        }
        return null;
    }
}
