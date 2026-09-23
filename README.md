# FastRelax API

Backend Spring Boot (multi-tenant) da FastRelax: cadastro de empresas/colaboradores, agendamento de sessões de massagem, controle das cadeiras (via [fastrelax-firmware](../fastrelax-firmware)) e consumo pelo [fastrelax-web](../fastrelax-web).

## Stack

- Java 21, Spring Boot 3.5 (Web, Security, Data JPA, Validation)
- PostgreSQL + Flyway
- JWT (access + refresh token) para autenticação
- Multi-tenant por `company_id`, isolamento reforçado nos repositórios `...Scoped`

## Configuração

```bash
cp .env.example .env
```

Preencha `.env` com:
- `DB_URL` / `DB_USER` / `DB_PASS` — conexão com o PostgreSQL
- `JWT_SECRET` / `AES_SECRET` — segredos de assinatura de token e criptografia (gere valores próprios, nunca reaproveite os do exemplo)
- `CHAIR_DEVICE_TOKEN` — segredo compartilhado com o firmware do ESP32 (heartbeat e comandos do relé)
- `CORS_ALLOWED_ORIGINS` — origens liberadas no navegador
- Bloco de e-mail (`MAIL_*`) — convite de primeiro acesso e recuperação de senha; desligado (`MAIL_ENABLED=false`) cai na senha temporária exibida na tela

Veja os comentários em `.env.example` para o detalhe de cada variável.

## Rodando localmente

```bash
./mvnw spring-boot:run
```

API sobe em `http://localhost:8090/api/v1` (porta e context-path em `application.properties`). Migrações Flyway rodam automaticamente no boot.

## Testes

```bash
./mvnw test
```

## Estrutura

```
src/main/java/br/rafaeros/fastrelax_api/
├── core/        # segurança (JWT, filtros), multi-tenancy, exceções, criptografia, e-mail
└── features/    # um pacote por domínio: auth, chairs, collaborators, companies,
                 # dashboard, departments, evaluation, firmwares, imports,
                 # locations, notifications, settings, users
```

Cada feature segue o padrão Controller → Service → Repository → DTOs, com `Specifications` para filtros dinâmicos quando necessário.

## Migrações

Flyway em `src/main/resources/db/migration`, `V1` a `V9` na versão atual. Nova migração = novo arquivo `V{n}__descricao.sql`, nunca edite uma já aplicada.

## Multi-tenancy

Isolamento por `company_id` em todas as entidades `CompanyOwned`/`SoftDeletableCompanyEntity`. Sempre usar os métodos `...Scoped` dos repositórios ao buscar por id — um recurso de outra empresa deve responder 404, nunca 403 (não revela existência).
