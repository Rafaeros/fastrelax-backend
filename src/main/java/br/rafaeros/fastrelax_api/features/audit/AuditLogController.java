package br.rafaeros.fastrelax_api.features.audit;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.rafaeros.fastrelax_api.core.dto.ApiResponseDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/audit-logs")
@RequiredArgsConstructor
@Tag(name = "Auditoria e LGPD")
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    @PreAuthorize("@access.isPlatformTeam()")
    @Operation(summary = "Lista eventos de auditoria da plataforma (somente Physical)")
    public ResponseEntity<ApiResponseDTO<Page<AuditLogResponseDTO>>> listPlatform(
            @RequestParam(name = "companyId", required = false) Long companyId,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseDTO.success(
                auditLogService.listForPlatform(companyId, pageable),
                "Registros de auditoria listados com sucesso"));
    }

    @GetMapping("/me")
    @PreAuthorize("@access.administersCompany()")
    @Operation(summary = "Lista eventos de auditoria da própria empresa (somente Gestor)")
    public ResponseEntity<ApiResponseDTO<Page<AuditLogResponseDTO>>> listMine(
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseDTO.success(
                auditLogService.listMine(pageable),
                "Registros de auditoria da empresa listados com sucesso"));
    }
}
