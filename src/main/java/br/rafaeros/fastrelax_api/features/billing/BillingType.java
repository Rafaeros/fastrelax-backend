package br.rafaeros.fastrelax_api.features.billing;

public enum BillingType {

    PIX("Pix"),
    BOLETO("Boleto bancário"),
    CREDIT_CARD("Cartão de crédito");

    private final String label;

    BillingType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
