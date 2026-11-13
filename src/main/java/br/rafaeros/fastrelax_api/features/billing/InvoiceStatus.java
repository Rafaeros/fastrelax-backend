package br.rafaeros.fastrelax_api.features.billing;

public enum InvoiceStatus {

    PENDING("Pendente"),
    PAID("Paga"),
    OVERDUE("Vencida"),
    CANCELLED("Cancelada"),
    REFUNDED("Estornada");

    private final String label;

    InvoiceStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
