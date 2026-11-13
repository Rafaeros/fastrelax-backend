package br.rafaeros.fastrelax_api.features.companies;

/**
 * Modalidade do tenant na plataforma.
 *
 * <ul>
 * <li>{@link #CORPORATE} — Modelo A (B2B): colaboradores cadastrados agendam e
 * usam as cadeiras; cobrança por faixa de colaboradores ativos.</li>
 * <li>{@link #OPERATOR} — Modelo B (Pay-per-use): público anônimo escaneia o QR
 * da cadeira e paga via Pix.</li>
 * </ul>
 */
public enum CompanyType {

    CORPORATE("Corporativo (B2B)"),
    OPERATOR("Operador Pay-per-use");

    private final String label;

    CompanyType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
