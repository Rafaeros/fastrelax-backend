package br.rafaeros.fastrelax_api.features.billing;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.rafaeros.fastrelax_api.core.exceptions.BusinessException;
import br.rafaeros.fastrelax_api.core.exceptions.ResourceNotFoundException;
import br.rafaeros.fastrelax_api.core.tenancy.CurrentTenant;
import br.rafaeros.fastrelax_api.features.audit.AuditLogService;
import br.rafaeros.fastrelax_api.features.billing.dtos.CreditCardSubscriptionRequestDTO;
import br.rafaeros.fastrelax_api.features.billing.dtos.GenerateInvoiceRequestDTO;
import br.rafaeros.fastrelax_api.features.billing.dtos.InvoiceResponseDTO;
import br.rafaeros.fastrelax_api.features.billing.dtos.PayInvoiceWithCardRequestDTO;
import br.rafaeros.fastrelax_api.features.billing.dtos.SaveMySubscriptionRequestDTO;
import br.rafaeros.fastrelax_api.features.billing.dtos.SaveSubscriptionRequestDTO;
import br.rafaeros.fastrelax_api.features.billing.dtos.SubscriptionResponseDTO;
import br.rafaeros.fastrelax_api.features.billing.gateway.AsaasAdapter;
import br.rafaeros.fastrelax_api.features.billing.gateway.PaymentGatewayPort;
import br.rafaeros.fastrelax_api.features.billing.gateway.PaymentGatewayPort.CardTokenResult;
import br.rafaeros.fastrelax_api.features.billing.gateway.PaymentGatewayPort.GatewayChargeRequest;
import br.rafaeros.fastrelax_api.features.billing.gateway.PaymentGatewayPort.GatewayChargeResult;
import br.rafaeros.fastrelax_api.features.billing.gateway.PaymentGatewayPort.GatewayChargeStatus;
import br.rafaeros.fastrelax_api.features.billing.gateway.PaymentGatewayPort.GatewaySubscriptionResult;
import br.rafaeros.fastrelax_api.features.billing.gateway.PaymentGatewayPort.GatewayWebhookPayload;
import br.rafaeros.fastrelax_api.features.chairs.ChairRepository;
import br.rafaeros.fastrelax_api.features.collaborators.CollaboratorRepository;
import br.rafaeros.fastrelax_api.features.companies.BillingStatus;
import br.rafaeros.fastrelax_api.features.companies.Company;
import br.rafaeros.fastrelax_api.features.companies.CompanyRepository;
import lombok.RequiredArgsConstructor;

/**
 * Orquestra assinaturas, faturas, webhooks e o ciclo de carência/suspensão do
 * Modelo A (B2B).
 *
 * <p>
 * <b>De onde vêm as cobranças.</b> Com o provedor configurado, a assinatura
 * mensal no provedor é a única fonte de cobrança: ela gera a cobrança de cada
 * ciclo (e, no cartão, debita sozinha), e cada uma é espelhada aqui como
 * {@link Invoice} — pelo webhook ou por {@link #syncFromGateway}. Antes a
 * plataforma também criava cobranças avulsas por cima da assinatura, e o
 * cliente podia pagar duas vezes o mesmo mês. Em modo simulado (sem chave) a
 * assinatura não gera nada sozinha e a fatura do ciclo é emitida localmente.
 *
 * <p>
 * <b>O que o cliente controla.</b> A empresa escolhe o plano (que precisa estar
 * ativo e comportar os colaboradores ativos dela) e a forma de pagamento. Valor,
 * vencimento e descrição são sempre do servidor.
 *
 * <p>
 * Regra crítica de hardware: a suspensão financeira altera exclusivamente
 * {@code companies.billing_status} e {@code subscriptions.status}, deixando
 * {@code chairs.active} intocado para que o relé NC do painel da cadeira
 * permaneça fechado (funcionamento manual pelo botão físico).
 */
@Service
@RequiredArgsConstructor
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private static final int DEFAULT_GRACE_DAYS = 5;
    /** Prazo do primeiro Pix/boleto de uma assinatura nova. */
    private static final int FIRST_DUE_DAYS = 3;
    private static final EnumSet<InvoiceStatus> OPEN = EnumSet.of(InvoiceStatus.PENDING, InvoiceStatus.OVERDUE);

    private final SubscriptionRepository subscriptionRepository;
    private final InvoiceRepository invoiceRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final CompanyRepository companyRepository;
    private final CollaboratorRepository collaboratorRepository;
    private final ChairRepository chairRepository;
    private final PlanService planService;
    private final PaymentGatewayPort paymentGateway;
    private final AsaasAdapter asaasAdapter;
    private final AuditLogService auditLogService;
    private final CurrentTenant currentTenant;

    public Optional<SubscriptionResponseDTO> findByCompany(Long companyId) {
        return subscriptionRepository.findByCompanyId(companyId)
                .map(this::toSubscriptionResponse);
    }

    public Optional<SubscriptionResponseDTO> findMine() {
        return findByCompany(currentTenant.companyId());
    }

    // =========================================================================
    // ASSINATURA PELA PRÓPRIA EMPRESA
    // =========================================================================

    /**
     * Empresa assina ou troca de plano. Pix e boleto assinam direto; cartão
     * aqui só troca o plano de quem já tem cartão cadastrado.
     *
     * <p>
     * Assinatura nova vence em {@value #FIRST_DUE_DAYS} dias; a existente
     * mantém o vencimento. A empresa só passa a operar quando a primeira
     * cobrança é paga.
     */
    @Transactional
    public SubscriptionResponseDTO saveMySubscription(SaveMySubscriptionRequestDTO dto) {
        Company company = myCompany();
        Plan plan = requireSelectablePlan(dto.planId(), company.getId());

        Subscription existing = subscriptionRepository.findByCompanyId(company.getId()).orElse(null);
        boolean isNew = existing == null || existing.getStatus() == BillingStatus.CANCELLED;
        boolean cardOnFile = !isNew && existing.getBillingType() == BillingType.CREDIT_CARD
                && existing.getGatewaySubscriptionId() != null;
        if (dto.billingType() == BillingType.CREDIT_CARD && !cardOnFile) {
            // Cartão novo só pelo endpoint próprio, que tokeniza. Aqui, só a
            // troca de plano de quem já paga no cartão.
            throw new BusinessException("Para pagar no cartão, cadastre o cartão de crédito.");
        }
        LocalDate dueDate = isNew ? LocalDate.now().plusDays(FIRST_DUE_DAYS) : existing.getNextDueDate();

        Subscription saved = applySubscription(company, plan, dto.billingType(), dueDate, false, null, null);
        if (dto.billingType() != BillingType.CREDIT_CARD) {
            // Saiu do cartão: o que se mostrava dele deixa de valer.
            saved.setCardBrand(null);
            saved.setCardLast4(null);
            saved = subscriptionRepository.save(saved);
        }

        if (paymentGateway.isLive()) {
            syncFromGateway(saved);
        } else if (isNew) {
            issueLocalCycleInvoice(company, saved, dueDate, false);
        }
        return toSubscriptionResponse(saved);
    }

    /**
     * Empresa assina (ou troca o cartão) no cartão de crédito, com cobrança
     * recorrente automática.
     *
     * <p>
     * O cartão vai ao provedor e volta como token ({@code tokenizeCreditCard});
     * a assinatura é criada com o token. Aqui ficam só bandeira e últimos 4
     * dígitos. Assinatura nova cobra no ato: a empresa passa a operar assim que
     * o provedor confirma o pagamento.
     */
    @Transactional
    public SubscriptionResponseDTO subscribeWithCreditCard(CreditCardSubscriptionRequestDTO dto, String remoteIp) {
        Company company = myCompany();
        Plan plan = requireSelectablePlan(dto.planId(), company.getId());
        String customerId = ensureCustomerFor(company);

        CardTokenResult card = paymentGateway.tokenizeCreditCard(
                customerId, dto.card().toGateway(), dto.holder().toGateway(), remoteIp);

        Subscription existing = subscriptionRepository.findByCompanyId(company.getId()).orElse(null);
        boolean hasGatewaySub = existing != null
                && existing.getStatus() != BillingStatus.CANCELLED
                && existing.getGatewaySubscriptionId() != null;
        LocalDate dueDate = hasGatewaySub ? existing.getNextDueDate() : LocalDate.now();

        Subscription saved = applySubscription(company, plan, BillingType.CREDIT_CARD, dueDate, false,
                hasGatewaySub ? null : card.token(), remoteIp);
        if (hasGatewaySub) {
            // Com a assinatura já marcada como CREDIT_CARD e valor atualizado,
            // vinculamos o novo token de cartão sem recriar a assinatura.
            paymentGateway.updateSubscriptionCreditCard(saved.getGatewaySubscriptionId(), card.token(), remoteIp);
        }
        saved.setCardBrand(card.brand());
        saved.setCardLast4(card.lastFour());
        saved = subscriptionRepository.save(saved);

        auditLogService.record(company.getId(), "SUBSCRIPTION_CARD_SET", "SUBSCRIPTION", saved.getId(),
                "Cartão de crédito cadastrado para a assinatura",
                card.brand() + " final " + card.lastFour());

        if (paymentGateway.isLive()) {
            syncFromGateway(saved);
            chargeOpenInvoicesWithToken(company, card.token(), card.brand() + " final " + card.lastFour(), remoteIp);
        } else if (!hasGatewaySub) {
            // Simulação da captura imediata do cartão.
            Invoice invoice = issueLocalCycleInvoice(company, saved, dueDate, false);
            asaasAdapter.updateSimulatedStatus(invoice.getGatewayChargeId(), InvoiceStatus.PAID,
                    invoice.getAmountCents(), "invoice:" + invoice.getId());
            applyInvoiceStatusTransition(invoice, InvoiceStatus.PAID, "SIMULATION:CARD_CAPTURE");
        } else {
            chargeOpenInvoicesWithToken(company, card.token(), card.brand() + " final " + card.lastFour(), remoteIp);
        }
        return toSubscriptionResponse(subscriptionRepository.findById(saved.getId()).orElse(saved));
    }

    /**
     * Cobra imediatamente no cartão recém-cadastrado as faturas em aberto da
     * assinatura da empresa, liberando o acesso operacional assim que o
     * provedor confirma a captura.
     */
    private void chargeOpenInvoicesWithToken(Company company, String cardToken, String cardLabel, String remoteIp) {
        List<Invoice> openInvoices = invoiceRepository
                .findByCompanyIdOrderByDueDateDesc(company.getId(), Pageable.unpaged())
                .getContent()
                .stream()
                .filter(inv -> OPEN.contains(inv.getStatus()) && inv.getGatewayChargeId() != null)
                .toList();

        for (Invoice invoice : openInvoices) {
            try {
                GatewayChargeStatus current = paymentGateway.getChargeStatus(invoice.getGatewayChargeId());
                if (current != null && current.status() == InvoiceStatus.PAID) {
                    applyVerifiedStatus(invoice, current, "CARD_SUBSCRIPTION_SYNC");
                    continue;
                }
                GatewayChargeStatus attempt = paymentGateway.payChargeWithCreditCard(
                        invoice.getGatewayChargeId(), cardToken, remoteIp);
                auditLogService.record(company.getId(), "INVOICE_CARD_PAYMENT", "INVOICE", invoice.getId(),
                        "Cobrança imediata no cartão da assinatura", cardLabel);
                GatewayChargeStatus verified = paymentGateway.getChargeStatus(invoice.getGatewayChargeId());
                applyVerifiedStatus(invoice, verified != null && verified.status() != null ? verified : attempt,
                        "CARD_SUBSCRIPTION");
            } catch (Exception e) {
                log.warn("Não foi possível cobrar automaticamente a fatura #{} da empresa {} no cartão: {}",
                        invoice.getId(), company.getId(), e.getMessage());
            }
        }
    }

    /**
     * "Abrir fatura" da empresa: devolve a fatura em aberto mais antiga.
     *
     * <p>
     * Nada do pedido do cliente é usado (antes ele escolhia vencimento e
     * descrição e podia empurrar o ciclo). Com o provedor configurado,
     * sincroniza as cobranças da assinatura antes; em modo simulado, emite a
     * do ciclo atual se não houver nenhuma em aberto.
     */
    @Transactional
    public InvoiceResponseDTO generateMyInvoice(GenerateInvoiceRequestDTO ignored) {
        Company company = myCompany();
        Subscription subscription = subscriptionRepository.findByCompanyId(company.getId())
                .orElseThrow(() -> new BusinessException("Escolha um plano antes de abrir uma fatura."));

        if (paymentGateway.isLive()) {
            syncFromGateway(subscription);
        }
        Optional<Invoice> open = invoiceRepository.findFirstByCompanyIdAndStatusInOrderByDueDateAsc(
                company.getId(), OPEN);
        if (open.isPresent()) {
            return new InvoiceResponseDTO(open.get());
        }
        if (paymentGateway.isLive()) {
            throw new BusinessException("Não há fatura em aberto no momento.");
        }
        return new InvoiceResponseDTO(
                issueLocalCycleInvoice(company, subscription, subscription.getNextDueDate(), false));
    }

    /**
     * Paga no cartão uma fatura em aberto da própria empresa, sem sair da
     * aplicação.
     *
     * <p>
     * A fatura precisa ser da empresa de quem chama (outra empresa responde
     * "não encontrada", como se não existisse). O cartão é tokenizado e o
     * pagamento é feito sobre a cobrança que já existe no provedor; o resultado
     * vale pela reconsulta, como no webhook. Se o cartão também for usado nas
     * próximas mensalidades, essa troca acontece depois do pagamento e não o
     * desfaz se falhar.
     */
    @Transactional
    public InvoiceResponseDTO payMyInvoiceWithCreditCard(Long invoiceId, PayInvoiceWithCardRequestDTO dto,
            String remoteIp) {
        Company company = myCompany();
        Invoice invoice = invoiceRepository.findById(Objects.requireNonNull(invoiceId))
                .filter(i -> Objects.equals(i.getCompany().getId(), company.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Fatura não encontrada"));
        if (!OPEN.contains(invoice.getStatus())) {
            throw new BusinessException("Esta fatura não está em aberto.");
        }
        if (invoice.getGatewayChargeId() == null) {
            throw new BusinessException("Esta fatura não aceita pagamento no cartão. Fale com o suporte.");
        }

        String customerId = ensureCustomerFor(company);
        CardTokenResult card = paymentGateway.tokenizeCreditCard(
                customerId, dto.card().toGateway(), dto.holder().toGateway(), remoteIp);

        GatewayChargeStatus current = paymentGateway.getChargeStatus(invoice.getGatewayChargeId());
        if (current != null && current.status() == InvoiceStatus.PAID) {
            applyVerifiedStatus(invoice, current, "CARD_PAYMENT_SYNC");
            if (dto.useForSubscription()) {
                useCardForSubscription(company, card, remoteIp);
            }
            return new InvoiceResponseDTO(invoiceRepository.findById(invoice.getId()).orElse(invoice));
        }

        GatewayChargeStatus attempt = paymentGateway.payChargeWithCreditCard(
                invoice.getGatewayChargeId(), card.token(), remoteIp);

        auditLogService.record(company.getId(), "INVOICE_CARD_PAYMENT", "INVOICE", invoice.getId(),
                "Pagamento de fatura no cartão pela aplicação", card.brand() + " final " + card.lastFour());

        GatewayChargeStatus verified = paymentGateway.getChargeStatus(invoice.getGatewayChargeId());
        applyVerifiedStatus(invoice, verified != null && verified.status() != null ? verified : attempt,
                "CARD_PAYMENT");

        if (dto.useForSubscription()) {
            useCardForSubscription(company, card, remoteIp);
        }

        // Pode voltar ainda não paga (ex.: em análise antifraude): quem chama
        // olha o status, e o webhook confirma depois. Lançar aqui desfaria o
        // registro de um pagamento que já foi enviado.
        return new InvoiceResponseDTO(invoiceRepository.findById(invoice.getId()).orElse(invoice));
    }

    /**
     * Paga uma fatura em aberto usando o cartão já cadastrado na assinatura da
     * própria empresa, sem precisar redigitar os dados do cartão.
     */
    @Transactional
    public InvoiceResponseDTO payMyInvoiceWithSavedCard(Long invoiceId, String remoteIp) {
        Company company = myCompany();
        Invoice invoice = invoiceRepository.findById(Objects.requireNonNull(invoiceId))
                .filter(i -> Objects.equals(i.getCompany().getId(), company.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Fatura não encontrada"));
        if (!OPEN.contains(invoice.getStatus())) {
            throw new BusinessException("Esta fatura não está em aberto.");
        }
        if (invoice.getGatewayChargeId() == null) {
            throw new BusinessException("Esta fatura não aceita pagamento no cartão. Fale com o suporte.");
        }

        Subscription subscription = subscriptionRepository.findByCompanyId(company.getId())
                .filter(s -> s.getStatus() != BillingStatus.CANCELLED && s.getCardLast4() != null)
                .orElseThrow(() -> new BusinessException("Nenhum cartão cadastrado na assinatura."));
        String token = paymentGateway.getSubscriptionCardToken(subscription.getGatewaySubscriptionId());
        if (token == null || token.isBlank()) {
            throw new BusinessException(
                    "Não foi possível usar o cartão cadastrado no provedor. Cadastre o cartão novamente.");
        }

        GatewayChargeStatus current = paymentGateway.getChargeStatus(invoice.getGatewayChargeId());
        if (current != null && current.status() == InvoiceStatus.PAID) {
            applyVerifiedStatus(invoice, current, "CARD_PAYMENT_SYNC");
            return new InvoiceResponseDTO(invoiceRepository.findById(invoice.getId()).orElse(invoice));
        }

        GatewayChargeStatus attempt = paymentGateway.payChargeWithCreditCard(
                invoice.getGatewayChargeId(), token, remoteIp);
        String cardLabel = (subscription.getCardBrand() != null ? subscription.getCardBrand() : "Cartão")
                + " final " + subscription.getCardLast4();
        auditLogService.record(company.getId(), "INVOICE_CARD_PAYMENT", "INVOICE", invoice.getId(),
                "Pagamento de fatura com cartão cadastrado na assinatura", cardLabel);

        GatewayChargeStatus verified = paymentGateway.getChargeStatus(invoice.getGatewayChargeId());
        applyVerifiedStatus(invoice, verified != null && verified.status() != null ? verified : attempt,
                "CARD_PAYMENT");

        return new InvoiceResponseDTO(invoiceRepository.findById(invoice.getId()).orElse(invoice));
    }

    private void useCardForSubscription(Company company, CardTokenResult card, String remoteIp) {
        Subscription subscription = subscriptionRepository.findByCompanyId(company.getId()).orElse(null);
        if (subscription == null || subscription.getStatus() == BillingStatus.CANCELLED) {
            return;
        }
        try {
            if (subscription.getGatewaySubscriptionId() != null) {
                paymentGateway.updateSubscriptionCreditCard(subscription.getGatewaySubscriptionId(), card.token(),
                        remoteIp);
                if (subscription.getBillingType() != BillingType.CREDIT_CARD) {
                    applySubscription(company, subscription.getPlan(), BillingType.CREDIT_CARD,
                            subscription.getNextDueDate(), subscription.isAutoAdjustTier(), null, null);
                }
            }
            subscription = subscriptionRepository.findByCompanyId(company.getId()).orElse(subscription);
            subscription.setBillingType(BillingType.CREDIT_CARD);
            subscription.setCardBrand(card.brand());
            subscription.setCardLast4(card.lastFour());
            subscriptionRepository.save(subscription);
        } catch (BusinessException e) {
            // O pagamento da fatura já foi feito; a troca do cartão da
            // assinatura pode ser refeita em "Trocar cartão".
            log.warn("Fatura paga, mas o cartão não foi salvo na assinatura da empresa {}: {}",
                    company.getId(), e.getMessage());
        }
    }

    /**
     * Cancela uma fatura não paga (somente equipe da plataforma) — ex.: emitida
     * no plano errado. Cancela também a cobrança no provedor.
     *
     * <p>
     * Sem fatura vencida restante, a empresa sai da carência/suspensão: volta a
     * ativa se já pagou alguma fatura, ou a "aguardando assinatura" se nunca
     * pagou. A empresa não tem este poder sobre as próprias faturas: seria um
     * jeito de apagar dívida.
     */
    @Transactional
    public InvoiceResponseDTO cancelInvoice(Long invoiceId, String reason) {
        Invoice invoice = invoiceRepository.findById(Objects.requireNonNull(invoiceId))
                .orElseThrow(() -> new ResourceNotFoundException("Fatura não encontrada"));
        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw new BusinessException("Fatura paga não pode ser cancelada. Para devolver o valor, faça um estorno.");
        }
        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            return new InvoiceResponseDTO(invoice);
        }
        if (invoice.getGatewayChargeId() != null) {
            paymentGateway.cancelCharge(invoice.getGatewayChargeId());
        }
        invoice.setStatus(InvoiceStatus.CANCELLED);
        invoiceRepository.save(invoice);

        Company company = invoice.getCompany();
        auditLogService.record(company.getId(), "INVOICE_CANCELLED", "INVOICE", invoice.getId(),
                reason != null && !reason.isBlank() ? reason.trim() : "Cancelamento pela equipe da plataforma",
                "Fatura #" + invoice.getId() + " de R$ " + String.format("%.2f", invoice.getAmountCents() / 100.0));

        boolean restricted = company.getBillingStatus() == BillingStatus.PAST_DUE
                || company.getBillingStatus() == BillingStatus.SUSPENDED;
        if (restricted && !invoiceRepository.existsByCompanyIdAndStatus(company.getId(), InvoiceStatus.OVERDUE)) {
            BillingStatus next = invoiceRepository.existsByCompanyIdAndStatus(company.getId(), InvoiceStatus.PAID)
                    ? BillingStatus.ACTIVE
                    : BillingStatus.PENDING;
            company.setBillingStatus(next);
            company.setGraceUntil(null);
            companyRepository.save(company);
            subscriptionRepository.findByCompanyId(company.getId()).ifPresent(sub -> {
                sub.setStatus(next);
                subscriptionRepository.save(sub);
            });
            auditLogService.recordSystem(company.getId(), "BILLING_REEVALUATED", "COMPANY", company.getId(),
                    "Fatura vencida cancelada", "Situação financeira: " + next.getLabel());
        }
        return new InvoiceResponseDTO(invoiceRepository.findById(invoice.getId()).orElse(invoice));
    }

    // =========================================================================
    // CONSULTAS
    // =========================================================================

    public Page<InvoiceResponseDTO> listInvoicesForPlatform(Long companyId, InvoiceStatus status,
            @org.springframework.lang.NonNull Pageable pageable) {
        Pageable p = Objects.requireNonNull(pageable);
        if (companyId != null && status != null) {
            return invoiceRepository.findByCompanyIdAndStatusOrderByDueDateDesc(companyId, status, p)
                    .map(InvoiceResponseDTO::new);
        }
        if (companyId != null) {
            return invoiceRepository.findByCompanyIdOrderByDueDateDesc(companyId, p)
                    .map(InvoiceResponseDTO::new);
        }
        if (status != null) {
            return invoiceRepository.findByStatusOrderByDueDateDesc(status, p)
                    .map(InvoiceResponseDTO::new);
        }
        return invoiceRepository.findAllByOrderByDueDateDesc(p)
                .map(InvoiceResponseDTO::new);
    }

    public Page<InvoiceResponseDTO> listMyInvoices(InvoiceStatus status,
            @org.springframework.lang.NonNull Pageable pageable) {
        Long companyId = currentTenant.companyId();
        Pageable p = Objects.requireNonNull(pageable);
        if (status != null) {
            return invoiceRepository.findByCompanyIdAndStatusOrderByDueDateDesc(companyId, status, p)
                    .map(InvoiceResponseDTO::new);
        }
        return invoiceRepository.findByCompanyIdOrderByDueDateDesc(companyId, p)
                .map(InvoiceResponseDTO::new);
    }

    // =========================================================================
    // ASSINATURA PELO SYSADMIN
    // =========================================================================

    /**
     * Cria ou atualiza a assinatura recorrente de uma empresa cliente
     * (somente equipe da plataforma, que pode escolher qualquer plano e data).
     */
    @Transactional
    public SubscriptionResponseDTO saveSubscription(SaveSubscriptionRequestDTO dto) {
        Company company = companyRepository.findById(Objects.requireNonNull(dto.companyId()))
                .orElseThrow(() -> new ResourceNotFoundException("Empresa não encontrada"));

        long activeCollaborators = collaboratorRepository.countByCompanyIdAndActiveTrue(company.getId());
        Plan plan = (dto.planId() != null)
                ? planService.findEntityById(dto.planId())
                : planService.resolveTierForCollaborators(activeCollaborators);

        Subscription saved = applySubscription(company, plan, dto.billingType(), dto.nextDueDate(),
                dto.autoAdjustTier(), null, null);

        if (paymentGateway.isLive()) {
            syncFromGateway(saved);
            if (dto.includeSetupFee()) {
                issueSetupFeeCharge(company, saved, plan);
            }
        } else if (dto.generateInitialInvoice()) {
            issueLocalCycleInvoice(company, saved, dto.nextDueDate(), dto.includeSetupFee());
        }
        return toSubscriptionResponse(saved);
    }

    @Transactional
    public SubscriptionResponseDTO cancelSubscription(Long companyId) {
        Subscription subscription = subscriptionRepository.findByCompanyId(Objects.requireNonNull(companyId))
                .orElseThrow(() -> new ResourceNotFoundException("Assinatura não encontrada para esta empresa"));

        paymentGateway.cancelSubscription(subscription.getGatewaySubscriptionId());
        subscription.setStatus(BillingStatus.CANCELLED);
        subscription.setCancelledAt(LocalDateTime.now());
        subscription.setGatewaySubscriptionId(null);
        Subscription saved = subscriptionRepository.save(subscription);

        Company company = subscription.getCompany();
        company.setBillingStatus(BillingStatus.CANCELLED);
        company.setGraceUntil(null);
        companyRepository.save(company);

        auditLogService.record(company.getId(), "SUBSCRIPTION_CANCELLED", "SUBSCRIPTION", saved.getId(),
                "Encerramento de contrato", "Assinatura cancelada; cadeiras mantidas em modo manual.");
        return toSubscriptionResponse(saved);
    }

    /**
     * Fatura do ciclo de uma empresa (somente equipe da plataforma).
     *
     * <p>
     * Com o provedor configurado e assinatura ativa, não cria cobrança avulsa
     * — isso cobraria o mês duas vezes —: sincroniza as cobranças da
     * assinatura e, se pedido, emite só a taxa de setup à parte.
     */
    @Transactional
    public InvoiceResponseDTO generateInvoiceForCompany(Long companyId, GenerateInvoiceRequestDTO dto) {
        Company company = companyRepository.findById(Objects.requireNonNull(companyId))
                .orElseThrow(() -> new ResourceNotFoundException("Empresa não encontrada"));
        Subscription subscription = subscriptionRepository.findByCompanyId(company.getId())
                .orElseThrow(() -> new BusinessException(
                        "Configure uma assinatura para a empresa antes de emitir faturas"));

        boolean includeSetup = dto != null && dto.includeSetupFee();
        if (paymentGateway.isLive() && subscription.getGatewaySubscriptionId() != null) {
            syncFromGateway(subscription);
            if (includeSetup) {
                return new InvoiceResponseDTO(issueSetupFeeCharge(company, subscription, subscription.getPlan()));
            }
            return invoiceRepository.findFirstByCompanyIdAndStatusInOrderByDueDateAsc(company.getId(), OPEN)
                    .map(InvoiceResponseDTO::new)
                    .orElseThrow(() -> new BusinessException("A assinatura não tem cobrança em aberto no momento."));
        }

        LocalDate dueDate = (dto != null && dto.dueDate() != null) ? dto.dueDate() : subscription.getNextDueDate();
        Invoice invoice = issueLocalCycleInvoice(company, subscription, dueDate, includeSetup);
        if (dto != null && dto.description() != null && !dto.description().isBlank()) {
            invoice.setDescription(dto.description().trim());
            invoice = invoiceRepository.save(invoice);
        }
        return new InvoiceResponseDTO(invoice);
    }

    // =========================================================================
    // WEBHOOK E ESPELHAMENTO
    // =========================================================================

    /**
     * Recebe o webhook, garante idempotência em {@code webhook_events} e decide
     * pelo status reconsultado no provedor, nunca pelo payload.
     *
     * <p>
     * Cobrança que ainda não existe aqui, mas pertence a uma assinatura nossa
     * (a do ciclo, gerada pelo provedor), é espelhada como fatura.
     */
    @Transactional
    public void handleWebhook(Map<String, String> headers, String rawBody) {
        GatewayWebhookPayload parsed = paymentGateway.parseWebhook(headers, rawBody);
        String provider = paymentGateway.providerName();

        if (webhookEventRepository.existsByProviderAndEventId(provider, parsed.eventId())) {
            return;
        }

        WebhookEvent event = new WebhookEvent();
        event.setProvider(provider);
        event.setEventId(parsed.eventId());
        event.setEventType(parsed.eventType());
        event.setPayload(rawBody);
        webhookEventRepository.save(event);

        if (parsed.chargeId() == null || parsed.chargeId().isBlank()) {
            return;
        }
        GatewayChargeStatus verified = paymentGateway.getChargeStatus(parsed.chargeId());
        if (verified == null || verified.status() == null) {
            return;
        }

        Optional<Invoice> known = invoiceRepository.findByGatewayProviderAndGatewayChargeId(provider,
                verified.chargeId());
        if (known.isPresent()) {
            applyVerifiedStatus(known.get(), verified, "WEBHOOK:" + parsed.eventType());
            return;
        }

        if (verified.subscriptionId() != null) {
            subscriptionRepository.findByGatewaySubscriptionId(verified.subscriptionId())
                    .ifPresent(sub -> upsertFromGateway(sub, verified, "WEBHOOK:" + parsed.eventType()));
        }
    }

    /**
     * Espelha as cobranças da assinatura no provedor como faturas locais
     * (cria as que faltam e atualiza o status das existentes).
     */
    @Transactional
    public void syncFromGateway(Subscription subscription) {
        if (!paymentGateway.isLive() || subscription.getGatewaySubscriptionId() == null) {
            return;
        }
        for (GatewayChargeStatus charge : paymentGateway.listSubscriptionCharges(
                subscription.getGatewaySubscriptionId())) {
            upsertFromGateway(subscription, charge, "SYNC");
        }
    }

    private void upsertFromGateway(Subscription subscription, GatewayChargeStatus charge, String trigger) {
        String provider = paymentGateway.providerName();
        Company company = subscription.getCompany();
        Invoice invoice = invoiceRepository.findByGatewayProviderAndGatewayChargeId(provider, charge.chargeId())
                .orElse(null);

        if (invoice == null) {
            if (charge.status() == InvoiceStatus.CANCELLED) {
                return;
            }
            LocalDate dueDate = charge.dueDate() != null ? charge.dueDate() : LocalDate.now();
            String refMonth = YearMonth.from(dueDate).toString();
            Plan plan = subscription.getPlan();

            invoice = new Invoice();
            invoice.setCompany(company);
            invoice.setSubscription(subscription);
            invoice.setPlan(plan);
            invoice.setReferenceMonth(refMonth);
            invoice.setDescription("Mensalidade " + refMonth + " — " + plan.getName());
            invoice.setActiveCollaboratorsCount(
                    (int) collaboratorRepository.countByCompanyIdAndActiveTrue(company.getId()));
            invoice.setActiveChairsCount(chairRepository.findByCompanyIdAndActiveTrue(company.getId()).size());
            invoice.setAmountCents(charge.amountCents());
            invoice.setBillingType(subscription.getBillingType());
            invoice.setDueDate(dueDate);
            invoice.setStatus(InvoiceStatus.PENDING);
            invoice.setGatewayProvider(provider);
            invoice.setGatewayChargeId(charge.chargeId());
            invoice.setInvoiceUrl(charge.invoiceUrl());
            if (subscription.getBillingType() == BillingType.PIX) {
                AsaasAdapter.PixData pix = asaasAdapter.fetchPix(charge.chargeId());
                invoice.setPixCopyPaste(pix.copyPaste());
                invoice.setPixQrCodeBase64(pix.qrBase64());
            }
            invoice = invoiceRepository.save(invoice);

            if (!dueDate.isBefore(subscription.getNextDueDate())) {
                subscription.setNextDueDate(dueDate.plusMonths(1));
                subscriptionRepository.save(subscription);
            }
            auditLogService.recordSystem(company.getId(), "INVOICE_MIRRORED", "INVOICE", invoice.getId(),
                    "Cobrança da assinatura (" + refMonth + ")",
                    "Valor: R$ " + String.format("%.2f", charge.amountCents() / 100.0) + " (" + trigger + ")");
        } else if (OPEN.contains(invoice.getStatus())) {
            // Reprecificação no provedor (troca de plano) chega aqui.
            invoice.setAmountCents(charge.amountCents());
            if (charge.invoiceUrl() != null) {
                invoice.setInvoiceUrl(charge.invoiceUrl());
            }
            invoice = invoiceRepository.save(invoice);
        }

        applyInvoiceStatusTransition(invoice, charge.status(), trigger);
    }

    /**
     * Aplica o status reconsultado a uma fatura conhecida. Pagamento só conta
     * se a cobrança verificada for a mesma da fatura e cobrir o valor dela.
     */
    private void applyVerifiedStatus(Invoice invoice, GatewayChargeStatus verified, String trigger) {
        if (verified.status() == InvoiceStatus.PAID && paymentGateway.isLive()) {
            boolean sameCharge = Objects.equals(verified.chargeId(), invoice.getGatewayChargeId());
            boolean covers = verified.amountCents() >= invoice.getAmountCents();
            if (!sameCharge || !covers) {
                log.warn("Pagamento ignorado na fatura {}: cobrança {} (mesma={}, cobre o valor={})",
                        invoice.getId(), verified.chargeId(), sameCharge, covers);
                return;
            }
        }
        applyInvoiceStatusTransition(invoice, verified.status(), trigger);
    }

    // =========================================================================
    // SIMULADOR E JOB
    // =========================================================================

    /**
     * Simulador de ciclo de vida financeiro para homologação e testes do SYSADMIN.
     *
     * <ul>
     * <li>{@code MARK_OVERDUE}: marca a fatura como vencida e coloca a empresa em
     * {@code PAST_DUE} com os dias de carência do plano.</li>
     * <li>{@code EXPIRE_GRACE_PERIOD}: expira a carência e move a empresa para
     * {@code SUSPENDED} (sem desligar a cadeira física).</li>
     * <li>{@code CONFIRM_PAYMENT}: confirma o pagamento da fatura e restaura a
     * empresa para {@code ACTIVE}.</li>
     * </ul>
     */
    @Transactional
    public InvoiceResponseDTO simulateInvoiceEvent(Long invoiceId, String action) {
        Invoice invoice = invoiceRepository.findById(Objects.requireNonNull(invoiceId))
                .orElseThrow(() -> new ResourceNotFoundException("Fatura não encontrada"));

        String normalized = action == null ? "" : action.trim().toUpperCase();
        switch (normalized) {
            case "MARK_OVERDUE" -> {
                if (invoice.getGatewayChargeId() != null) {
                    asaasAdapter.updateSimulatedStatus(
                            invoice.getGatewayChargeId(),
                            InvoiceStatus.OVERDUE,
                            invoice.getAmountCents(),
                            "invoice:" + invoice.getId());
                }
                applyInvoiceStatusTransition(invoice, InvoiceStatus.OVERDUE, "SIMULATION:MARK_OVERDUE");
            }
            case "EXPIRE_GRACE_PERIOD" -> {
                if (invoice.getStatus() != InvoiceStatus.OVERDUE) {
                    applyInvoiceStatusTransition(invoice, InvoiceStatus.OVERDUE, "SIMULATION:MARK_OVERDUE");
                }
                Company company = invoice.getCompany();
                company.setBillingStatus(BillingStatus.PAST_DUE);
                company.setGraceUntil(LocalDateTime.now().minusMinutes(1));
                companyRepository.save(company);
                sweepOverdueAndGracePeriods();
            }
            case "CONFIRM_PAYMENT" -> {
                if (invoice.getGatewayChargeId() != null) {
                    asaasAdapter.updateSimulatedStatus(
                            invoice.getGatewayChargeId(),
                            InvoiceStatus.PAID,
                            invoice.getAmountCents(),
                            "invoice:" + invoice.getId());
                }
                applyInvoiceStatusTransition(invoice, InvoiceStatus.PAID, "SIMULATION:CONFIRM_PAYMENT");
            }
            default -> throw new BusinessException(
                    "Ação de simulação desconhecida. Use MARK_OVERDUE, EXPIRE_GRACE_PERIOD ou CONFIRM_PAYMENT.");
        }

        return new InvoiceResponseDTO(invoiceRepository.findById(invoice.getId()).orElse(invoice));
    }

    /**
     * Job diário (e acionável sob demanda):
     * <ol>
     * <li>Faturas {@code PENDING} com {@code dueDate < hoje} viram {@code OVERDUE}
     * e iniciam a carência ({@code PAST_DUE}).</li>
     * <li>Empresas em {@code PAST_DUE} cujo {@code graceUntil} já venceu passam
     * para {@code SUSPENDED} — bloqueando o software (exceto cobrança e
     * exportação para gestor e RH) e mantendo {@code chairs.active} intocado.</li>
     * </ol>
     */
    @Transactional
    public int sweepOverdueAndGracePeriods() {
        int transitions = 0;

        List<Invoice> newlyOverdue = invoiceRepository.findByStatusAndDueDateBefore(
                InvoiceStatus.PENDING, LocalDate.now());
        for (Invoice invoice : newlyOverdue) {
            applyInvoiceStatusTransition(invoice, InvoiceStatus.OVERDUE, "JOB:DUE_DATE_PASSED");
            transitions++;
        }

        List<Company> expiredGraceCompanies = companyRepository.findByBillingStatusAndGraceUntilBefore(
                BillingStatus.PAST_DUE, LocalDateTime.now());
        for (Company company : expiredGraceCompanies) {
            company.setBillingStatus(BillingStatus.SUSPENDED);
            companyRepository.save(company);

            subscriptionRepository.findByCompanyId(company.getId()).ifPresent(sub -> {
                sub.setStatus(BillingStatus.SUSPENDED);
                subscriptionRepository.save(sub);
            });

            auditLogService.recordSystem(
                    company.getId(),
                    "BILLING_SUSPENDED",
                    "COMPANY",
                    company.getId(),
                    "Fim do prazo de carência sem regularização da fatura",
                    "Acesso ao software bloqueado (exceto pagamento e exportação para gestor e RH). Cadeiras seguem liberadas manualmente.");
            transitions++;
        }

        return transitions;
    }

    // =========================================================================
    // INTERNOS
    // =========================================================================

    private Company myCompany() {
        Long companyId = Objects.requireNonNull(currentTenant.companyId());
        return companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Empresa não encontrada"));
    }

    /**
     * Plano que a própria empresa pode escolher: ativo e com espaço para os
     * colaboradores ativos que ela já tem. Pode ser maior que o necessário,
     * nunca menor — escolher uma faixa abaixo seria pagar menos que o devido.
     */
    private Plan requireSelectablePlan(Long planId, Long companyId) {
        Plan plan = planService.findEntityById(Objects.requireNonNull(planId));
        if (!plan.isActive()) {
            throw new BusinessException("Este plano não está mais disponível. Escolha outro.");
        }
        long active = collaboratorRepository.countByCompanyIdAndActiveTrue(companyId);
        if (plan.getMaxCollaborators() != null && active > plan.getMaxCollaborators()) {
            throw new BusinessException("A empresa tem " + active + " colaboradores ativos e o plano "
                    + plan.getName() + " comporta até " + plan.getMaxCollaborators()
                    + ". Escolha um plano maior ou desative quem saiu.");
        }
        return plan;
    }

    private String ensureCustomerFor(Company company) {
        String customerId = paymentGateway.ensureCustomer(company);
        if (!Objects.equals(company.getGatewayCustomerId(), customerId)) {
            company.setGatewayCustomerId(customerId);
            companyRepository.save(company);
        }
        return customerId;
    }

    /**
     * Grava a assinatura local e a cria/atualiza no provedor com o valor do
     * plano. Assinatura nova (ou reaberta depois de cancelada) nasce
     * {@code PENDING}: só a primeira cobrança paga a leva a ativa.
     */
    private Subscription applySubscription(Company company, Plan plan, BillingType billingType,
            LocalDate nextDueDate, boolean autoAdjustTier, String creditCardToken, String remoteIp) {
        String customerId = ensureCustomerFor(company);
        int activeChairs = chairRepository.findByCompanyIdAndActiveTrue(company.getId()).size();

        Subscription subscription = subscriptionRepository.findByCompanyId(company.getId())
                .orElseGet(() -> {
                    Subscription created = new Subscription();
                    created.setCompany(company);
                    created.setStatus(BillingStatus.PENDING);
                    return created;
                });
        if (subscription.getStatus() == BillingStatus.CANCELLED) {
            subscription.setStatus(BillingStatus.PENDING);
            subscription.setCancelledAt(null);
            subscription.setGatewaySubscriptionId(null);
        }

        subscription.setPlan(plan);
        subscription.setBillingType(billingType);
        subscription.setNextDueDate(nextDueDate);
        subscription.setAutoAdjustTier(autoAdjustTier);
        subscription.setGatewayProvider(paymentGateway.providerName());

        long amountCents = plan.calculateCycleAmountCents(activeChairs, false);
        GatewaySubscriptionResult gwSub = paymentGateway.createOrUpdateSubscription(
                customerId,
                subscription.getGatewaySubscriptionId(),
                billingType,
                amountCents,
                nextDueDate,
                "Assinatura FastRelax — " + plan.getName(),
                "company:" + company.getId(),
                creditCardToken,
                remoteIp);
        subscription.setGatewaySubscriptionId(gwSub.subscriptionId());

        Subscription saved = subscriptionRepository.save(subscription);
        auditLogService.record(company.getId(), "SUBSCRIPTION_SAVED", "SUBSCRIPTION", saved.getId(),
                "Configuração de assinatura B2B",
                "Plano: " + plan.getName() + ", forma: " + billingType + ", próximo vencimento: " + nextDueDate);
        return saved;
    }

    /**
     * Fatura do ciclo emitida pela plataforma como cobrança avulsa. Só em modo
     * simulado ou para empresas sem assinatura no provedor.
     */
    private Invoice issueLocalCycleInvoice(Company company, Subscription subscription, LocalDate dueDate,
            boolean includeSetup) {
        long activeCollaborators = collaboratorRepository.countByCompanyIdAndActiveTrue(company.getId());
        int activeChairs = chairRepository.findByCompanyIdAndActiveTrue(company.getId()).size();

        Plan plan = subscription.getPlan();
        if (subscription.isAutoAdjustTier()) {
            Plan matched = planService.resolveTierForCollaborators(activeCollaborators);
            if (!Objects.equals(matched.getId(), plan.getId())) {
                subscription.setPlan(matched);
                subscriptionRepository.save(subscription);
                plan = matched;
            }
        }

        long amountCents = plan.calculateCycleAmountCents(activeChairs, includeSetup);
        String refMonth = YearMonth.from(dueDate).toString();
        String description = buildInvoiceDescription(plan, refMonth, activeCollaborators, activeChairs,
                includeSetup);

        Invoice saved = createChargeInvoice(company, subscription, plan, refMonth, description,
                (int) activeCollaborators, activeChairs, amountCents, dueDate);

        if (!dueDate.isBefore(subscription.getNextDueDate())) {
            subscription.setNextDueDate(dueDate.plusMonths(1));
            subscriptionRepository.save(subscription);
        }

        auditLogService.record(company.getId(), "INVOICE_GENERATED", "INVOICE", saved.getId(),
                "Fechamento de ciclo (" + refMonth + ")",
                "Valor: R$ " + String.format("%.2f", amountCents / 100.0) + " | Colaboradores ativos: "
                        + activeCollaborators + " | Cadeiras ativas: " + activeChairs);
        return saved;
    }

    /** Taxa única de setup cobrada à parte da mensalidade da assinatura. */
    private Invoice issueSetupFeeCharge(Company company, Subscription subscription, Plan plan) {
        int activeChairs = chairRepository.findByCompanyIdAndActiveTrue(company.getId()).size();
        long amountCents = (long) activeChairs * plan.getSetupFeePerChairCents();
        if (amountCents <= 0) {
            throw new BusinessException("Não há taxa de setup a cobrar para esta empresa.");
        }
        String refMonth = YearMonth.now().toString();
        Invoice saved = createChargeInvoice(company, subscription, plan, refMonth,
                "Setup de " + activeChairs + " cadeira(s) — " + plan.getName(),
                (int) collaboratorRepository.countByCompanyIdAndActiveTrue(company.getId()), activeChairs,
                amountCents, LocalDate.now().plusDays(FIRST_DUE_DAYS));
        auditLogService.record(company.getId(), "SETUP_FEE_GENERATED", "INVOICE", saved.getId(),
                "Taxa de setup", "Valor: R$ " + String.format("%.2f", amountCents / 100.0));
        return saved;
    }

    private Invoice createChargeInvoice(Company company, Subscription subscription, Plan plan, String refMonth,
            String description, int collaborators, int chairs, long amountCents, LocalDate dueDate) {
        String customerId = ensureCustomerFor(company);

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setSubscription(subscription);
        invoice.setPlan(plan);
        invoice.setReferenceMonth(refMonth);
        invoice.setDescription(description);
        invoice.setActiveCollaboratorsCount(collaborators);
        invoice.setActiveChairsCount(chairs);
        invoice.setAmountCents(amountCents);
        invoice.setBillingType(subscription.getBillingType());
        invoice.setDueDate(dueDate);
        invoice.setStatus(InvoiceStatus.PENDING);
        invoice.setGatewayProvider(paymentGateway.providerName());
        Invoice saved = invoiceRepository.save(invoice);

        GatewayChargeResult charge = paymentGateway.createCharge(new GatewayChargeRequest(
                customerId,
                "invoice:" + saved.getId(),
                subscription.getBillingType(),
                amountCents,
                dueDate,
                description));

        saved.setGatewayChargeId(charge.chargeId());
        saved.setInvoiceUrl(charge.invoiceUrl());
        saved.setPixCopyPaste(charge.pixCopyPaste());
        saved.setPixQrCodeBase64(charge.pixQrCodeBase64());
        return invoiceRepository.save(saved);
    }

    private void applyInvoiceStatusTransition(Invoice invoice, InvoiceStatus targetStatus, String trigger) {
        if (targetStatus == null) {
            return;
        }
        Company company = invoice.getCompany();

        if (targetStatus == InvoiceStatus.PAID && invoice.getStatus() != InvoiceStatus.PAID) {
            invoice.setStatus(InvoiceStatus.PAID);
            invoice.setPaidAt(LocalDateTime.now());
            invoiceRepository.save(invoice);

            auditLogService.recordWebhook(company.getId(), invoice.getGatewayProvider(), "INVOICE_PAID",
                    "INVOICE", invoice.getId(), "Fatura #" + invoice.getId() + " paga (" + trigger + ")");

            // Sem nenhuma fatura vencida, libera a empresa na hora — inclusive a
            // que estava aguardando o primeiro pagamento (PENDING).
            boolean stillHasOverdue = invoiceRepository.existsByCompanyIdAndStatus(
                    company.getId(), InvoiceStatus.OVERDUE);
            if (!stillHasOverdue && company.getBillingStatus() != BillingStatus.ACTIVE) {
                company.setBillingStatus(BillingStatus.ACTIVE);
                company.setGraceUntil(null);
                companyRepository.save(company);

                subscriptionRepository.findByCompanyId(company.getId()).ifPresent(sub -> {
                    sub.setStatus(BillingStatus.ACTIVE);
                    subscriptionRepository.save(sub);
                });

                auditLogService.recordSystem(company.getId(), "BILLING_REACTIVATED", "COMPANY", company.getId(),
                        "Regularização financeira confirmada",
                        "Acesso operacional liberado após quitação da fatura #" + invoice.getId());
            }
            return;
        }

        if (targetStatus == InvoiceStatus.OVERDUE && invoice.getStatus() != InvoiceStatus.PAID) {
            invoice.setStatus(InvoiceStatus.OVERDUE);
            invoiceRepository.save(invoice);
            startGracePeriod(invoice, "Fatura #" + invoice.getId() + " vencida (" + trigger + ")");
            return;
        }

        if (targetStatus == InvoiceStatus.REFUNDED) {
            boolean wasPaid = invoice.getStatus() == InvoiceStatus.PAID;
            invoice.setStatus(InvoiceStatus.REFUNDED);
            invoiceRepository.save(invoice);
            if (wasPaid) {
                // Estorno ou chargeback desfaz o pagamento que liberou a empresa:
                // volta a correr a carência, como numa fatura vencida.
                startGracePeriod(invoice, "Pagamento da fatura #" + invoice.getId()
                        + " estornado ou contestado (" + trigger + ")");
            }
            return;
        }

        if (targetStatus == InvoiceStatus.CANCELLED && invoice.getStatus() != InvoiceStatus.PAID) {
            invoice.setStatus(InvoiceStatus.CANCELLED);
            invoiceRepository.save(invoice);
        }
    }

    private void startGracePeriod(Invoice invoice, String reason) {
        Company company = invoice.getCompany();
        if (company.getBillingStatus() != BillingStatus.ACTIVE) {
            return;
        }
        int graceDays = invoice.getPlan() != null ? invoice.getPlan().getGraceDays() : DEFAULT_GRACE_DAYS;
        LocalDateTime graceUntil = LocalDateTime.now().plusDays(graceDays);
        company.setBillingStatus(BillingStatus.PAST_DUE);
        company.setGraceUntil(graceUntil);
        companyRepository.save(company);

        subscriptionRepository.findByCompanyId(company.getId()).ifPresent(sub -> {
            sub.setStatus(BillingStatus.PAST_DUE);
            subscriptionRepository.save(sub);
        });

        auditLogService.recordSystem(company.getId(), "BILLING_PAST_DUE", "COMPANY", company.getId(),
                reason, "Iniciada carência de " + graceDays + " dia(s) até " + graceUntil);
    }

    private String buildInvoiceDescription(Plan plan, String refMonth, long activeCollaborators, int activeChairs,
            boolean includeSetup) {
        StringBuilder sb = new StringBuilder();
        sb.append("Mensalidade ").append(refMonth).append(" — ").append(plan.getName());
        sb.append(" (").append(activeCollaborators).append(" colab. ativos, ").append(activeChairs)
                .append(" cadeira(s))");
        if (includeSetup && activeChairs > 0 && plan.getSetupFeePerChairCents() > 0) {
            sb.append(" + Setup de ").append(activeChairs).append(" cadeira(s)");
        }
        return sb.toString();
    }

    private SubscriptionResponseDTO toSubscriptionResponse(Subscription sub) {
        Long companyId = sub.getCompany().getId();
        long activeCollaborators = collaboratorRepository.countByCompanyIdAndActiveTrue(companyId);
        int activeChairs = chairRepository.findByCompanyIdAndActiveTrue(companyId).size();
        return new SubscriptionResponseDTO(sub, activeCollaborators, activeChairs);
    }
}
