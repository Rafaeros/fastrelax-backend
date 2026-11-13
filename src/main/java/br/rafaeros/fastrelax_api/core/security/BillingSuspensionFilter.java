package br.rafaeros.fastrelax_api.core.security;

import java.io.IOException;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import br.rafaeros.fastrelax_api.features.companies.BillingStatus;
import br.rafaeros.fastrelax_api.features.companies.Company;
import br.rafaeros.fastrelax_api.features.users.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Restringe o alcance do gestor e do RH quando a empresa está fora da operação
 * plena por motivo financeiro: sem plano pago ({@code PENDING}), suspensa após a
 * carência ({@code SUSPENDED}) ou com contrato cancelado ({@code CANCELLED}).
 *
 * <p>
 * Nesses estados sobra o necessário para regularizar: cobrança
 * ({@code /billing/**}), a própria conta, a leitura dos dados da empresa e a
 * exportação dos colaboradores. Colaboradores e operadores já são barrados
 * antes, em {@code isEnabled()}, no {@link SecurityFilter}.
 *
 * <p>
 * A comparação é por caminho exato (e método, onde importa): um caminho com
 * barra no fim ou maiúsculas diferentes cai fora da lista e é recusado, ou
 * seja, a regra falha fechada.
 */
@Component
public class BillingSuspensionFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_ANY_METHOD = Set.of(
            "/users/me",
            "/users/me/first-access-password",
            "/users/me/password",
            "/imports/collaborators/export",
            "/auth/logout",
            "/auth/refresh");

    /** Só leitura: editar o cadastro da empresa não ajuda a regularizar nada. */
    private static final Set<String> ALLOWED_READ_ONLY = Set.of("/companies/me");

    @Override
    protected void doFilterInternal(@org.springframework.lang.NonNull HttpServletRequest request,
            @org.springframework.lang.NonNull HttpServletResponse response,
            @org.springframework.lang.NonNull FilterChain filterChain)
            throws ServletException, IOException {

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof User user) {
            Company company = user.getCompany();
            if (company != null && company.isBillingRestricted() && !isAllowedWhileRestricted(request)) {
                response.setStatus(HttpStatus.FORBIDDEN.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("""
                        {"status":"error",\
                        "message":"%s",\
                        "timestamp":"%s"}""".formatted(messageFor(company), java.time.LocalDateTime.now()));
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAllowedWhileRestricted(HttpServletRequest request) {
        String path = request.getServletPath();
        if (ALLOWED_ANY_METHOD.contains(path) || path.startsWith("/billing/")) {
            return true;
        }
        return ALLOWED_READ_ONLY.contains(path) && "GET".equalsIgnoreCase(request.getMethod());
    }

    private String messageFor(Company company) {
        if (company.getBillingStatus() == BillingStatus.PENDING) {
            return "Sua empresa ainda não tem um plano ativo. Escolha um plano em Faturas para liberar o acesso.";
        }
        return "O acesso da empresa está bloqueado por pendência financeira. Regularize em Faturas ou exporte os dados.";
    }
}
