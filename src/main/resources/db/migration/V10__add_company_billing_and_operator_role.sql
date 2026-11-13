-- =========================================================================
-- MODELO DE NEGÓCIO B2B (CORPORATIVO) E PREPARAÇÃO PARA OPERADOR (MODELO B)
--
-- 1. companies:
--    - type: separa empresa corporativa (CORPORATE, Modelo A) de operador de
--      cadeira pay-per-use (OPERATOR, Modelo B).
--    - billing_status: separa a suspensão automática por inadimplência
--      (PAST_DUE -> SUSPENDED após 5 dias de carência) do bloqueio manual
--      (companies.active). Suspensão financeira NUNCA desliga o relé de
--      energia da cadeira (chairs.active continua intocado), deixando a
--      cadeira do cliente funcionar manualmente pelo botão físico.
--    - grace_until: fim da janela de 5 dias de carência quando há fatura
--      vencida (PAST_DUE).
--    - gateway_customer_id: identificador do cliente no gateway (ex.: Asaas).
--
-- 2. users:
--    - acrescenta o papel COMPANY_OPERATOR no CHECK de papéis e na regra de
--      vínculo obrigatório com empresa.
--
-- 3. collaborators:
--    - anonymized_at: carimbo de anonimização LGPD (mantém sessões e
--      avaliações como estatística sem vínculo pessoal).
-- =========================================================================
BEGIN;

ALTER TABLE companies
    ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'CORPORATE'
        CHECK (type IN ('CORPORATE', 'OPERATOR')),
    ADD COLUMN billing_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (billing_status IN ('ACTIVE', 'PAST_DUE', 'SUSPENDED', 'CANCELLED')),
    ADD COLUMN grace_until TIMESTAMP,
    ADD COLUMN gateway_customer_id VARCHAR(100);

CREATE INDEX idx_companies_billing_grace
    ON companies (billing_status, grace_until)
    WHERE deleted_at IS NULL AND billing_status = 'PAST_DUE';

ALTER TABLE users
    DROP CONSTRAINT IF EXISTS users_role_check,
    DROP CONSTRAINT IF EXISTS chk_users_company_role;

ALTER TABLE users
    ADD CONSTRAINT users_role_check CHECK (role IN (
        'SYSADMIN',
        'COMPANY_ADMIN',
        'COMPANY_RH',
        'COMPANY_OPERATOR'
    )),
    ADD CONSTRAINT chk_users_company_role CHECK (
        (role = 'SYSADMIN' AND company_id IS NULL) OR
        (role IN ('COMPANY_ADMIN', 'COMPANY_RH', 'COMPANY_OPERATOR') AND company_id IS NOT NULL)
    );

ALTER TABLE collaborators
    ADD COLUMN anonymized_at TIMESTAMP;

COMMIT;
