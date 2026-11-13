package br.rafaeros.fastrelax_api.features.billing.dtos;

import jakarta.validation.constraints.Size;

/** Cancelamento de fatura pela equipe da plataforma (ex.: emitida no plano errado). */
public record CancelInvoiceRequestDTO(@Size(max = 255) String reason) {
}
