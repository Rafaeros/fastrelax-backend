package br.rafaeros.fastrelax_api.features.billing;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    Page<Invoice> findByCompanyIdOrderByDueDateDesc(Long companyId, Pageable pageable);

    Page<Invoice> findByCompanyIdAndStatusOrderByDueDateDesc(Long companyId, InvoiceStatus status, Pageable pageable);

    Page<Invoice> findByStatusOrderByDueDateDesc(InvoiceStatus status, Pageable pageable);

    Page<Invoice> findAllByOrderByDueDateDesc(Pageable pageable);

    Optional<Invoice> findByGatewayProviderAndGatewayChargeId(String gatewayProvider, String gatewayChargeId);

    Optional<Invoice> findByGatewayChargeId(String gatewayChargeId);

    List<Invoice> findByStatusAndDueDateBefore(InvoiceStatus status, LocalDate date);

    boolean existsByCompanyIdAndStatus(Long companyId, InvoiceStatus status);

    /** Fatura em aberto mais antiga (pendente ou vencida), a próxima a pagar. */
    Optional<Invoice> findFirstByCompanyIdAndStatusInOrderByDueDateAsc(Long companyId, java.util.Collection<InvoiceStatus> statuses);

    Optional<Invoice> findFirstByCompanyIdAndStatusOrderByDueDateAsc(Long companyId, InvoiceStatus status);
}
