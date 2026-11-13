package br.rafaeros.fastrelax_api.features.chairs.dtos;

import jakarta.validation.constraints.Size;

/**
 * Motivo registrado no {@code audit_log} quando a equipe da Physical ativa ou
 * desativa uma cadeira (ex.: manutenção ou risco elétrico).
 */
public record ToggleChairActiveRequestDTO(
    @Size(max = 500, message = "O motivo deve ter no máximo 500 caracteres")
    String reason
) {}
