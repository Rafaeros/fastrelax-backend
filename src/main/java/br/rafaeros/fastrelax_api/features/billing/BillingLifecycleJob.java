package br.rafaeros.fastrelax_api.features.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Job periódico de cobrança B2B: marca faturas vencidas ({@code PAST_DUE}) e
 * suspende empresas após os 5 dias de carência ({@code SUSPENDED}).
 */
@Component
@RequiredArgsConstructor
public class BillingLifecycleJob {

    private static final Logger log = LoggerFactory.getLogger(BillingLifecycleJob.class);

    private final BillingService billingService;

    /** Roda diariamente às 02:00 (e também configurável via propriedade). */
    @Scheduled(cron = "${app.billing.lifecycle-cron:0 0 2 * * *}")
    public void sweep() {
        int transitions = billingService.sweepOverdueAndGracePeriods();
        if (transitions > 0) {
            log.info("BillingLifecycleJob: {} transição(ões) de vencimento/carência aplicadas", transitions);
        }
    }
}
