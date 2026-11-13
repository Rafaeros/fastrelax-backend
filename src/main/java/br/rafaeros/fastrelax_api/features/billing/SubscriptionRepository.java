package br.rafaeros.fastrelax_api.features.billing;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;

import br.rafaeros.fastrelax_api.features.companies.BillingStatus;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    /**
     * Trava a linha da assinatura até o fim da transação: duas pessoas
     * cadastrando colaboradores ao mesmo tempo contam um de cada vez e não
     * furam juntas o limite do plano.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Subscription s WHERE s.company.id = :companyId")
    Optional<Subscription> findByCompanyIdForUpdate(@Param("companyId") Long companyId);

    Optional<Subscription> findByCompanyId(Long companyId);

    Optional<Subscription> findByGatewaySubscriptionId(String gatewaySubscriptionId);

    List<Subscription> findByStatusInAndNextDueDateLessThanEqual(List<BillingStatus> statuses, LocalDate date);
}
