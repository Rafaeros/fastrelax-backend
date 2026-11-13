package br.rafaeros.fastrelax_api.features.billing;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<Plan, Long> {

    List<Plan> findByActiveTrueOrderByMinCollaboratorsAsc();

    Page<Plan> findAllByOrderByMinCollaboratorsAsc(Pageable pageable);
}
