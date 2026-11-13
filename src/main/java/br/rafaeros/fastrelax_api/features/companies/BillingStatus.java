package br.rafaeros.fastrelax_api.features.companies;

/**
 * Ciclo de vida financeiro da empresa (tenant).
 *
 * <p>
 * Separado de {@code Company#active} para distinguir suspensão por cobrança de
 * bloqueio administrativo manual:
 * <ul>
 * <li>{@link #PENDING} — empresa corporativa sem plano pago: ainda não assinou
 * ou a primeira cobrança não foi confirmada. Colaboradores não entram; gestor e
 * RH entram só para escolher o plano e pagar.</li>
 * <li>{@link #ACTIVE} — contrato em dia, operação liberada.</li>
 * <li>{@link #PAST_DUE} — fatura vencida; aviso ao gestor e operação mantida
 * durante os dias de carência ({@code graceUntil}).</li>
 * <li>{@link #SUSPENDED} — fim da carência: app de colaboradores e rotina
 * operacional bloqueados, exceto para gestor e RH pagarem a fatura e
 * exportarem dados. O relé de energia da cadeira ({@code chairs.active})
 * <b>não</b> é alterado: a cadeira continua funcionando manualmente pelo botão.</li>
 * <li>{@link #CANCELLED} — contrato encerrado; gestor e RH ainda entram para
 * assinar de novo ou exportar dados.</li>
 * </ul>
 */
public enum BillingStatus {

    PENDING("Aguardando assinatura"),
    ACTIVE("Em dia"),
    PAST_DUE("Em carência"),
    SUSPENDED("Suspenso por inadimplência"),
    CANCELLED("Cancelado");

    private final String label;

    BillingStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** Verdadeiro enquanto colaboradores, RH e cadeiras podem operar normalmente. */
    public boolean allowsOperation() {
        return this == ACTIVE || this == PAST_DUE;
    }
}
