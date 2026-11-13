package br.rafaeros.fastrelax_api.features.billing;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.rafaeros.fastrelax_api.core.dto.ApiResponseDTO;
import br.rafaeros.fastrelax_api.features.billing.dtos.CancelInvoiceRequestDTO;
import br.rafaeros.fastrelax_api.features.billing.dtos.CreditCardSubscriptionRequestDTO;
import br.rafaeros.fastrelax_api.features.billing.dtos.PayInvoiceWithCardRequestDTO;
import br.rafaeros.fastrelax_api.features.billing.dtos.GenerateInvoiceRequestDTO;
import br.rafaeros.fastrelax_api.features.billing.dtos.InvoiceResponseDTO;
import br.rafaeros.fastrelax_api.features.billing.dtos.PlanResponseDTO;
import br.rafaeros.fastrelax_api.features.billing.dtos.SaveMySubscriptionRequestDTO;
import br.rafaeros.fastrelax_api.features.billing.dtos.SavePlanRequestDTO;
import br.rafaeros.fastrelax_api.features.billing.dtos.SaveSubscriptionRequestDTO;
import br.rafaeros.fastrelax_api.features.billing.dtos.SimulateInvoiceEventRequestDTO;
import br.rafaeros.fastrelax_api.features.billing.dtos.SubscriptionResponseDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/billing")
@RequiredArgsConstructor
@Tag(name = "Planos, Assinaturas e Faturas (B2B)")
public class BillingController {

    private final PlanService planService;
    private final BillingService billingService;

    // =========================================================================
    // PLANOS (SYSADMIN + listagem de planos ativos)
    // =========================================================================

    @GetMapping("/plans")
    @PreAuthorize("@access.isPlatformTeam()")
    @Operation(summary = "Lista todos os planos cadastrados com paginação (somente Physical)")
    public ResponseEntity<ApiResponseDTO<Page<PlanResponseDTO>>> listPlans(
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseDTO.success(
                planService.findAll(Objects.requireNonNull(pageable)),
                "Planos listados com sucesso"));
    }

    @GetMapping("/plans/active")
    @PreAuthorize("@access.isPlatformTeam() or @access.operatesCompany()")
    @Operation(summary = "Lista as faixas de planos ativas ordenadas por número de colaboradores")
    public ResponseEntity<ApiResponseDTO<List<PlanResponseDTO>>> listActivePlans() {
        return ResponseEntity.ok(ApiResponseDTO.success(
                planService.listActive(),
                "Planos ativos listados com sucesso"));
    }

    @PostMapping("/plans")
    @PreAuthorize("@access.isPlatformTeam()")
    @Operation(summary = "Cria uma faixa de plano comercial (somente Physical)")
    public ResponseEntity<ApiResponseDTO<PlanResponseDTO>> createPlan(
            @RequestBody @Valid SavePlanRequestDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseDTO.success(planService.create(dto), "Plano criado com sucesso"));
    }

    @PutMapping("/plans/{id}")
    @PreAuthorize("@access.isPlatformTeam()")
    @Operation(summary = "Atualiza uma faixa de plano comercial (somente Physical)")
    public ResponseEntity<ApiResponseDTO<PlanResponseDTO>> updatePlan(
            @PathVariable Long id,
            @RequestBody @Valid SavePlanRequestDTO dto) {
        return ResponseEntity.ok(ApiResponseDTO.success(planService.update(id, dto),
                "Plano atualizado com sucesso"));
    }

    @PatchMapping("/plans/{id}/toggle-active")
    @PreAuthorize("@access.isPlatformTeam()")
    @Operation(summary = "Ativa ou desativa um plano comercial (somente Physical)")
    public ResponseEntity<ApiResponseDTO<PlanResponseDTO>> togglePlanActive(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponseDTO.success(planService.toggleActive(id),
                "Status do plano alterado com sucesso"));
    }

    @DeleteMapping("/plans/{id}")
    @PreAuthorize("@access.isPlatformTeam()")
    @Operation(summary = "Remove um plano comercial (soft delete, somente Physical)")
    public ResponseEntity<ApiResponseDTO<Void>> deletePlan(@PathVariable Long id) {
        planService.softDelete(id);
        return ResponseEntity.ok(ApiResponseDTO.success("Plano removido com sucesso"));
    }

    // =========================================================================
    // ASSINATURAS
    // =========================================================================

    @GetMapping("/subscriptions/me")
    @PreAuthorize("@access.operatesCompany()")
    @Operation(summary = "Assinatura vigente da própria empresa, incluindo contagem de colaboradores e cadeiras")
    public ResponseEntity<ApiResponseDTO<SubscriptionResponseDTO>> getMySubscription() {
        return ResponseEntity.ok(ApiResponseDTO.success(
                billingService.findMine().orElse(null),
                "Assinatura consultada com sucesso"));
    }

    @PostMapping("/subscriptions/me")
    @PreAuthorize("@access.operatesCompany()")
    @Operation(summary = "Assina ou troca de plano com Pix ou boleto (plano ativo e que comporte os colaboradores ativos)")
    public ResponseEntity<ApiResponseDTO<SubscriptionResponseDTO>> saveMySubscription(
            @RequestBody @Valid SaveMySubscriptionRequestDTO dto) {
        return ResponseEntity.ok(ApiResponseDTO.success(
                billingService.saveMySubscription(dto),
                "Assinatura da empresa atualizada com sucesso"));
    }

    @PostMapping("/subscriptions/me/credit-card")
    @PreAuthorize("@access.operatesCompany()")
    @Operation(summary = "Assina (ou troca o cartão) com cobrança recorrente no cartão de crédito. "
            + "O cartão é tokenizado no provedor e não é gravado.")
    public ResponseEntity<ApiResponseDTO<SubscriptionResponseDTO>> subscribeWithCreditCard(
            @RequestBody @Valid CreditCardSubscriptionRequestDTO dto,
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponseDTO.success(
                billingService.subscribeWithCreditCard(dto, resolvePayerIp(dto.remoteIp(), request)),
                "Cartão cadastrado e assinatura atualizada"));
    }

    /**
     * IP de quem paga, exigido pelo provedor na análise antifraude. O web
     * repassa o IP do navegador (a chamada chega aqui vinda do servidor Next);
     * só é aceito se parecer um IP, senão vale o endereço da conexão.
     */
    private static String resolvePayerIp(String declared, HttpServletRequest request) {
        String ip = (declared != null && declared.matches("[0-9a-fA-F:.]{3,45}"))
                ? declared
                : request.getRemoteAddr();
        if (ip == null || ip.isBlank() || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
            return "127.0.0.1";
        }
        return ip;
    }

    @GetMapping("/subscriptions/company/{companyId}")
    @PreAuthorize("@access.isPlatformTeam()")
    @Operation(summary = "Busca a assinatura de uma empresa específica (somente Physical)")
    public ResponseEntity<ApiResponseDTO<SubscriptionResponseDTO>> getCompanySubscription(
            @PathVariable Long companyId) {
        return ResponseEntity.ok(ApiResponseDTO.success(
                billingService.findByCompany(companyId).orElse(null),
                "Assinatura consultada com sucesso"));
    }

    @PostMapping("/subscriptions")
    @PreAuthorize("@access.isPlatformTeam()")
    @Operation(summary = "Cria ou atualiza a assinatura recorrente de uma empresa (somente Physical)")
    public ResponseEntity<ApiResponseDTO<SubscriptionResponseDTO>> saveSubscription(
            @RequestBody @Valid SaveSubscriptionRequestDTO dto) {
        return ResponseEntity.ok(ApiResponseDTO.success(
                billingService.saveSubscription(dto),
                "Assinatura salva com sucesso"));
    }

    @PostMapping("/subscriptions/company/{companyId}/cancel")
    @PreAuthorize("@access.isPlatformTeam()")
    @Operation(summary = "Cancela a assinatura de uma empresa (somente Physical)")
    public ResponseEntity<ApiResponseDTO<SubscriptionResponseDTO>> cancelSubscription(
            @PathVariable Long companyId) {
        return ResponseEntity.ok(ApiResponseDTO.success(
                billingService.cancelSubscription(companyId),
                "Assinatura cancelada com sucesso"));
    }

    // =========================================================================
    // FATURAS
    // =========================================================================

    @GetMapping("/invoices/me")
    @PreAuthorize("@access.operatesCompany()")
    @Operation(summary = "Lista as faturas da própria empresa (acessível ao gestor mesmo em suspensão financeira)")
    public ResponseEntity<ApiResponseDTO<Page<InvoiceResponseDTO>>> listMyInvoices(
            @RequestParam(name = "status", required = false) InvoiceStatus status,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseDTO.success(
                billingService.listMyInvoices(status, Objects.requireNonNull(pageable)),
                "Faturas listadas com sucesso"));
    }

    @PostMapping("/invoices/me/generate")
    @PreAuthorize("@access.operatesCompany()")
    @Operation(summary = "Abre a fatura em aberto da própria empresa (vencimento e valor definidos pelo servidor)")
    public ResponseEntity<ApiResponseDTO<InvoiceResponseDTO>> generateMyInvoice(
            @RequestBody(required = false) GenerateInvoiceRequestDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseDTO.success(
                        billingService.generateMyInvoice(dto),
                        "Fatura gerada com sucesso"));
    }

    @PostMapping("/invoices/me/{invoiceId}/pay-with-card")
    @PreAuthorize("@access.operatesCompany()")
    @Operation(summary = "Paga no cartão uma fatura em aberto da própria empresa, sem sair da aplicação. "
            + "O cartão é tokenizado no provedor e não é gravado.")
    public ResponseEntity<ApiResponseDTO<InvoiceResponseDTO>> payMyInvoiceWithCard(
            @PathVariable Long invoiceId,
            @RequestBody @Valid PayInvoiceWithCardRequestDTO dto,
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponseDTO.success(
                billingService.payMyInvoiceWithCreditCard(invoiceId, dto, resolvePayerIp(dto.remoteIp(), request)),
                "Pagamento enviado"));
    }

    @PostMapping("/invoices/me/{invoiceId}/pay-with-saved-card")
    @PreAuthorize("@access.operatesCompany()")
    @Operation(summary = "Paga uma fatura em aberto da própria empresa usando o cartão já cadastrado na assinatura.")
    public ResponseEntity<ApiResponseDTO<InvoiceResponseDTO>> payMyInvoiceWithSavedCard(
            @PathVariable Long invoiceId,
            @RequestParam(name = "remoteIp", required = false) String remoteIp,
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponseDTO.success(
                billingService.payMyInvoiceWithSavedCard(invoiceId, resolvePayerIp(remoteIp, request)),
                "Pagamento enviado"));
    }

    @PostMapping("/invoices/{invoiceId}/cancel")
    @PreAuthorize("@access.isPlatformTeam()")
    @Operation(summary = "Cancela uma fatura não paga e a cobrança dela (somente equipe da plataforma)")
    public ResponseEntity<ApiResponseDTO<InvoiceResponseDTO>> cancelInvoice(
            @PathVariable Long invoiceId,
            @RequestBody(required = false) @Valid CancelInvoiceRequestDTO dto) {
        return ResponseEntity.ok(ApiResponseDTO.success(
                billingService.cancelInvoice(invoiceId, dto != null ? dto.reason() : null),
                "Fatura cancelada"));
    }

    @GetMapping("/invoices")
    @PreAuthorize("@access.isPlatformTeam()")
    @Operation(summary = "Lista faturas de todas as empresas ou de uma empresa específica (somente Physical)")
    public ResponseEntity<ApiResponseDTO<Page<InvoiceResponseDTO>>> listPlatformInvoices(
            @RequestParam(name = "companyId", required = false) Long companyId,
            @RequestParam(name = "status", required = false) InvoiceStatus status,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseDTO.success(
                billingService.listInvoicesForPlatform(companyId, status, Objects.requireNonNull(pageable)),
                "Faturas listadas com sucesso"));
    }

    @PostMapping("/invoices/company/{companyId}/generate")
    @PreAuthorize("@access.isPlatformTeam()")
    @Operation(summary = "Emite fatura para a empresa medindo colaboradores e cadeiras ativas no ciclo (somente Physical)")
    public ResponseEntity<ApiResponseDTO<InvoiceResponseDTO>> generateInvoice(
            @PathVariable Long companyId,
            @RequestBody(required = false) GenerateInvoiceRequestDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseDTO.success(
                        billingService.generateInvoiceForCompany(companyId, dto),
                        "Fatura gerada com sucesso"));
    }

    @PostMapping("/invoices/{invoiceId}/simulate")
    @PreAuthorize("@access.isPlatformTeam()")
    @Operation(summary = "Simula eventos do ciclo de cobrança (MARK_OVERDUE, EXPIRE_GRACE_PERIOD, CONFIRM_PAYMENT) para homologação")
    public ResponseEntity<ApiResponseDTO<InvoiceResponseDTO>> simulateInvoiceEvent(
            @PathVariable Long invoiceId,
            @RequestBody @Valid SimulateInvoiceEventRequestDTO dto) {
        return ResponseEntity.ok(ApiResponseDTO.success(
                billingService.simulateInvoiceEvent(invoiceId, dto.action()),
                "Evento simulado aplicado com sucesso"));
    }

    @PostMapping("/lifecycle/sweep")
    @PreAuthorize("@access.isPlatformTeam()")
    @Operation(summary = "Executa imediatamente a varredura de faturas vencidas e expiração de carência (somente Physical)")
    public ResponseEntity<ApiResponseDTO<Integer>> triggerSweep() {
        int count = billingService.sweepOverdueAndGracePeriods();
        return ResponseEntity.ok(ApiResponseDTO.success(count,
                count + " transição(ões) de cobrança processada(s)"));
    }

    // =========================================================================
    // WEBHOOK DO GATEWAY
    // =========================================================================

    @PostMapping("/webhooks/asaas")
    @Operation(summary = "Recebe notificações de cobrança do Asaas (exige o token do webhook; idempotente e com reconsulta na API)")
    public ResponseEntity<ApiResponseDTO<Void>> asaasWebhook(
            @RequestHeader Map<String, String> headers,
            @RequestBody String rawBody) {
        billingService.handleWebhook(headers, rawBody);
        return ResponseEntity.ok(ApiResponseDTO.success("Webhook processado"));
    }
}
