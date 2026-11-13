package br.rafaeros.fastrelax_api.features.billing;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    boolean existsByProviderAndEventId(String provider, String eventId);
}
