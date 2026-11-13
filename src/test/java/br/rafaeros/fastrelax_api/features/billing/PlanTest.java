package br.rafaeros.fastrelax_api.features.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.rafaeros.fastrelax_api.features.chairs.Chair;
import br.rafaeros.fastrelax_api.features.companies.BillingStatus;
import br.rafaeros.fastrelax_api.features.companies.Company;
import br.rafaeros.fastrelax_api.features.companies.CompanyType;
import br.rafaeros.fastrelax_api.features.users.User;
import br.rafaeros.fastrelax_api.features.users.UserRole;

class PlanTest {

    @Test
    @DisplayName("Faixa com limite superior casa colaboradores dentro do intervalo")
    void matchesBoundedCollaboratorTier() {
        Plan plan = new Plan();
        plan.setName("Essencial 50");
        plan.setMinCollaborators(1);
        plan.setMaxCollaborators(50);
        plan.setMonthlyPriceCents(29900);
        plan.setIncludedChairs(1);
        plan.setExtraChairPriceCents(14900);
        plan.setSetupFeePerChairCents(15000);
        plan.setGraceDays(5);
        plan.setActive(true);

        assertFalse(plan.matchesCollaboratorCount(0));
        assertTrue(plan.matchesCollaboratorCount(1));
        assertTrue(plan.matchesCollaboratorCount(50));
        assertFalse(plan.matchesCollaboratorCount(51));
    }

    @Test
    @DisplayName("Calcula ciclo somando mensalidade base, cadeiras adicionais e setup opcional")
    void calculatesCycleAmountWithExtraChairsAndSetup() {
        Plan plan = new Plan();
        plan.setName("Corporativo 150");
        plan.setMinCollaborators(51);
        plan.setMaxCollaborators(150);
        plan.setMonthlyPriceCents(49900);
        plan.setIncludedChairs(1);
        plan.setExtraChairPriceCents(14900);
        plan.setSetupFeePerChairCents(15000);
        plan.setGraceDays(5);
        plan.setActive(true);

        // 1 cadeira inclusa, 0 adicionais, sem setup
        assertEquals(49900, plan.calculateCycleAmountCents(1, false));

        // 3 cadeiras ativas (2 extras * 14900 = 29800) + 49900 = 79700
        assertEquals(79700, plan.calculateCycleAmountCents(3, false));

        // 3 cadeiras ativas com setup (3 * 15000 = 45000) + 79700 = 124700
        assertEquals(124700, plan.calculateCycleAmountCents(3, true));
    }

    @Test
    @DisplayName("Suspensão financeira bloqueia uso da cadeira pela plataforma sem desligar chairs.active")
    void billingSuspensionBlocksPlatformUseWithoutChangingChairActive() {
        Company company = new Company();
        company.setName("Acme Ltda");
        company.setSlug("acme");
        company.setType(CompanyType.CORPORATE);
        company.setBillingStatus(BillingStatus.ACTIVE);
        company.setActive(true);

        Chair chair = new Chair();
        chair.setName("Cadeira 01");
        chair.setMacAddress("AA:BB:CC:DD:EE:01");
        chair.setCompany(company);
        chair.setActive(true);
        chair.setLastSeenAt(LocalDateTime.now());

        assertTrue(chair.canUse());
        assertTrue(chair.isActive());

        // Em carência (PAST_DUE), a empresa continua operacional
        company.setBillingStatus(BillingStatus.PAST_DUE);
        company.setGraceUntil(LocalDateTime.now().plusDays(5));
        assertEquals(BillingStatus.PAST_DUE, company.getBillingStatus());
        assertTrue(company.isOperational());
        assertTrue(chair.canUse());
        assertTrue(chair.isActive());

        // Suspensa por inadimplência (SUSPENDED): bloqueia agendamentos/sessões,
        // mas NUNCA altera chairs.active (relé NC permanece fechado para botão físico)
        company.setBillingStatus(BillingStatus.SUSPENDED);
        assertEquals(BillingStatus.SUSPENDED, company.getBillingStatus());
        assertFalse(company.isOperational());
        assertTrue(company.isPanelAccessEnabled());
        assertTrue(company.isBillingRestricted());
        assertFalse(chair.canUse());
        assertTrue(chair.isActive(), "chairs.active deve permanecer true mesmo com empresa SUSPENDED");

        // Regularização via webhook restaura a operação imediatamente
        company.setBillingStatus(BillingStatus.ACTIVE);
        company.setGraceUntil(null);
        assertEquals(BillingStatus.ACTIVE, company.getBillingStatus());
        assertTrue(company.isOperational());
        assertTrue(chair.canUse());
    }

    @Test
    @DisplayName("Empresa nova nasce sem plano pago: só gestor e RH entram, e com acesso restrito")
    void companyWithoutPaidPlanOnlyLetsAdminAndHrIn() {
        Company company = new Company();
        company.setType(CompanyType.CORPORATE);
        company.setActive(true);

        assertEquals(BillingStatus.PENDING, company.getBillingStatus());
        assertFalse(company.isOperational(), "sem plano pago, colaboradores não operam");
        assertTrue(company.isPanelAccessEnabled());
        assertTrue(company.isBillingRestricted());

        assertTrue(userWithRole(company, UserRole.COMPANY_ADMIN).isEnabled());
        assertTrue(userWithRole(company, UserRole.COMPANY_RH).isEnabled());
        assertFalse(userWithRole(company, UserRole.COMPANY_OPERATOR).isEnabled());

        // Primeiro pagamento confirmado libera a operação e tira a restrição.
        company.setBillingStatus(BillingStatus.ACTIVE);
        assertTrue(company.isOperational());
        assertFalse(company.isBillingRestricted());
        assertTrue(userWithRole(company, UserRole.COMPANY_OPERATOR).isEnabled());
    }

    @Test
    @DisplayName("Contrato cancelado ainda deixa gestor e RH entrarem para assinar de novo; bloqueio manual não")
    void cancelledContractKeepsPanelButManualBlockDoesNot() {
        Company company = new Company();
        company.setActive(true);
        company.setBillingStatus(BillingStatus.CANCELLED);
        assertTrue(userWithRole(company, UserRole.COMPANY_RH).isEnabled());
        assertTrue(company.isBillingRestricted());

        company.setActive(false);
        assertFalse(userWithRole(company, UserRole.COMPANY_ADMIN).isEnabled());
        assertFalse(company.isBillingRestricted(), "bloqueio manual não é restrição de cobrança");
    }

    private static User userWithRole(Company company, UserRole role) {
        User user = new User();
        user.setCompany(company);
        user.setRole(role);
        user.setActive(true);
        return user;
    }
}
