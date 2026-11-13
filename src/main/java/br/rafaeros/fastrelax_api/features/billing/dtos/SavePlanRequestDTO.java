package br.rafaeros.fastrelax_api.features.billing.dtos;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SavePlanRequestDTO(
    @NotBlank(message = "O nome do plano é obrigatório")
    @Size(min = 2, max = 120, message = "O nome deve ter entre 2 e 120 caracteres")
    String name,

    @Size(max = 1000, message = "A descrição deve ter no máximo 1000 caracteres")
    String description,

    @NotNull(message = "O mínimo de colaboradores da faixa é obrigatório")
    @Min(value = 0, message = "O mínimo de colaboradores não pode ser negativo")
    Integer minCollaborators,

    /** Opcional: nulo significa faixa aberta ("acima de N colaboradores"). */
    @Min(value = 1, message = "O máximo de colaboradores deve ser maior que zero")
    Integer maxCollaborators,

    @NotNull(message = "O valor mensal é obrigatório")
    @Min(value = 0, message = "O valor mensal não pode ser negativo")
    Long monthlyPriceCents,

    @NotNull(message = "A franquia de cadeiras inclusas é obrigatória")
    @Min(value = 1, message = "Informe ao menos 1 cadeira inclusa")
    Integer includedChairs,

    @NotNull(message = "O valor por cadeira adicional é obrigatório")
    @Min(value = 0, message = "O valor por cadeira adicional não pode ser negativo")
    Long extraChairPriceCents,

    @NotNull(message = "A taxa de setup por cadeira é obrigatória")
    @Min(value = 0, message = "A taxa de setup não pode ser negativa")
    Long setupFeePerChairCents,

    @NotNull(message = "Os dias de carência são obrigatórios")
    @Min(value = 0, message = "A carência não pode ser negativa")
    @Max(value = 60, message = "A carência máxima é de 60 dias")
    Integer graceDays
) {}
