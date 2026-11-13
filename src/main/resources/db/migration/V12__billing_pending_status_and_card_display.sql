-- =========================================================================
-- COBRANÇA: EMPRESA SEM PLANO PAGO E CARTÃO RECORRENTE
--
-- 1. billing_status ganha PENDING ("Aguardando assinatura"): empresa
--    corporativa que ainda não tem plano pago. Colaboradores não entram;
--    gestor e RH entram só para escolher o plano e pagar. Passa a ser o
--    padrão de empresa nova — a primeira cobrança confirmada a leva a ACTIVE.
--
-- 2. Empresas corporativas que hoje estão ACTIVE sem nenhuma fatura paga
--    passam a PENDING. ATENÇÃO: em produção isso bloqueia o app dos
--    colaboradores dessas empresas até o RH assinar um plano.
--    Operador pay-per-use (type = OPERATOR) não tem plano e não é afetado.
--
-- 3. subscriptions guarda só bandeira e últimos 4 dígitos do cartão, para
--    exibição. Número, validade e CVV vão direto ao provedor de pagamento,
--    que devolve um token; nem o token fica no banco — a assinatura no
--    provedor é que mantém o cartão.
-- =========================================================================
BEGIN;

ALTER TABLE companies DROP CONSTRAINT IF EXISTS companies_billing_status_check;
ALTER TABLE companies
    ADD CONSTRAINT companies_billing_status_check
        CHECK (billing_status IN ('PENDING', 'ACTIVE', 'PAST_DUE', 'SUSPENDED', 'CANCELLED')),
    ALTER COLUMN billing_status SET DEFAULT 'PENDING';

ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_status_check;
ALTER TABLE subscriptions
    ADD CONSTRAINT subscriptions_status_check
        CHECK (status IN ('PENDING', 'ACTIVE', 'PAST_DUE', 'SUSPENDED', 'CANCELLED')),
    ADD COLUMN card_brand VARCHAR(20),
    ADD COLUMN card_last4 VARCHAR(4);

UPDATE companies c
SET billing_status = 'PENDING'
WHERE c.type = 'CORPORATE'
  AND c.billing_status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM invoices i
      WHERE i.company_id = c.id AND i.status = 'PAID'
  );

COMMIT;
