-- =========================================================================
-- PLANOS POR FAIXA DE COLABORADORES, ASSINATURAS, FATURAS, WEBHOOKS E AUDITORIA
--
-- Modelo A (Corporativo B2B):
-- - Planos com faixas de colaboradores ativos (min_collaborators..max_collaborators),
--   mensalidade em centavos, franquia de cadeiras, valor por cadeira adicional,
--   taxa de setup por cadeira e dias de carência (padrão 5 dias).
-- - Assinatura recorrente por empresa, ligada ao gateway (porta hexagonal).
-- - Faturas mensais com medição de colaboradores ativos e cadeiras ativas no
--   fechamento do ciclo, QR Code / Pix Copia-e-Cola e link externo.
-- - Tabela idempotente de eventos de webhook (evita processar duas vezes).
-- - Audit log para suspensão, reativação, desativação de cadeira com motivo,
--   exportação de dados e anonimização LGPD.
-- =========================================================================
BEGIN;

CREATE TABLE plans (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description TEXT,
    min_collaborators INTEGER NOT NULL DEFAULT 1 CHECK (min_collaborators >= 0),
    max_collaborators INTEGER CHECK (max_collaborators IS NULL OR max_collaborators >= min_collaborators),
    monthly_price_cents BIGINT NOT NULL CHECK (monthly_price_cents >= 0),
    included_chairs INTEGER NOT NULL DEFAULT 1 CHECK (included_chairs >= 1),
    extra_chair_price_cents BIGINT NOT NULL DEFAULT 0 CHECK (extra_chair_price_cents >= 0),
    setup_fee_per_chair_cents BIGINT NOT NULL DEFAULT 0 CHECK (setup_fee_per_chair_cents >= 0),
    grace_days INTEGER NOT NULL DEFAULT 5 CHECK (grace_days BETWEEN 0 AND 60),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_plans_active_tiers
    ON plans (min_collaborators, max_collaborators)
    WHERE deleted_at IS NULL AND active = TRUE;

-- Faixas iniciais padrão do Modelo A (editáveis pelo SYSADMIN no painel)
INSERT INTO plans (
    name,
    description,
    min_collaborators,
    max_collaborators,
    monthly_price_cents,
    included_chairs,
    extra_chair_price_cents,
    setup_fee_per_chair_cents,
    grace_days
) VALUES
    ('Corporativo — Até 50 colaboradores', 'Faixa inicial para empresas com até 50 colaboradores ativos.', 1, 50, 49000, 1, 15000, 35000, 5),
    ('Corporativo — 51 a 150 colaboradores', 'Faixa intermediária para empresas de 51 a 150 colaboradores ativos.', 51, 150, 89000, 2, 15000, 35000, 5),
    ('Corporativo — 151 a 300 colaboradores', 'Faixa avançada para empresas de 151 a 300 colaboradores ativos.', 151, 300, 149000, 3, 15000, 35000, 5),
    ('Corporativo — Acima de 300 colaboradores', 'Faixa corporativa para grandes operações acima de 300 colaboradores ativos.', 301, NULL, 229000, 5, 12000, 35000, 5);

CREATE TABLE subscriptions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT NOT NULL UNIQUE REFERENCES companies(id),
    plan_id BIGINT NOT NULL REFERENCES plans(id),
    billing_type VARCHAR(20) NOT NULL DEFAULT 'PIX'
        CHECK (billing_type IN ('PIX', 'BOLETO', 'CREDIT_CARD')),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'PAST_DUE', 'SUSPENDED', 'CANCELLED')),
    gateway_provider VARCHAR(30) NOT NULL DEFAULT 'ASAAS',
    gateway_subscription_id VARCHAR(100),
    next_due_date DATE NOT NULL,
    auto_adjust_tier BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_at TIMESTAMP
);

CREATE INDEX idx_subscriptions_company ON subscriptions (company_id);
CREATE INDEX idx_subscriptions_next_due ON subscriptions (next_due_date) WHERE status IN ('ACTIVE', 'PAST_DUE');

CREATE TABLE invoices (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    subscription_id BIGINT REFERENCES subscriptions(id),
    plan_id BIGINT REFERENCES plans(id),
    reference_month VARCHAR(7),
    description VARCHAR(255) NOT NULL,
    active_collaborators_count INTEGER NOT NULL DEFAULT 0,
    active_chairs_count INTEGER NOT NULL DEFAULT 0,
    amount_cents BIGINT NOT NULL CHECK (amount_cents >= 0),
    billing_type VARCHAR(20) NOT NULL DEFAULT 'PIX'
        CHECK (billing_type IN ('PIX', 'BOLETO', 'CREDIT_CARD')),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PAID', 'OVERDUE', 'CANCELLED', 'REFUNDED')),
    due_date DATE NOT NULL,
    paid_at TIMESTAMP,
    gateway_provider VARCHAR(30) NOT NULL DEFAULT 'ASAAS',
    gateway_charge_id VARCHAR(100),
    invoice_url TEXT,
    pix_copy_paste TEXT,
    pix_qr_code_base64 TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_invoices_company_due ON invoices (company_id, due_date DESC);
CREATE INDEX idx_invoices_status_due ON invoices (status, due_date);
CREATE UNIQUE INDEX uq_invoices_gateway_charge
    ON invoices (gateway_provider, gateway_charge_id)
    WHERE gateway_charge_id IS NOT NULL;

CREATE TABLE webhook_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider VARCHAR(30) NOT NULL,
    event_id VARCHAR(120) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    payload TEXT NOT NULL,
    processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_webhook_events_provider_event UNIQUE (provider, event_id)
);

CREATE TABLE audit_logs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT REFERENCES companies(id),
    actor_type VARCHAR(30) NOT NULL CHECK (actor_type IN ('USER', 'COLLABORATOR', 'SYSTEM', 'WEBHOOK')),
    actor_id BIGINT,
    actor_name VARCHAR(180),
    action VARCHAR(80) NOT NULL,
    target_type VARCHAR(60) NOT NULL,
    target_id BIGINT,
    reason TEXT,
    details TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_logs_company_created ON audit_logs (company_id, created_at DESC);
CREATE INDEX idx_audit_logs_created ON audit_logs (created_at DESC);

COMMIT;
