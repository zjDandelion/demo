# Repository Guidelines

## Project Structure & Module Organization

This is a Java 8 / Spring Boot 2.7.18 microservices platform with three Maven modules:

- `na-payment-contract/` — shared DTOs and interface contracts consumed by both services (e.g. `PaymentView`, `PaymentStatusView`). Declared in the root `dependencyManagement` so versions stay in sync.
- `na-order-service/` — order lifecycle, catalog, security, and the payment-orchestration integration layer. Packaged as `na-order-service.jar`.
- `na-payment-service/` — independent payment processing with a mock channel. Packaged as `na-payment-service.jar`.

Standard Spring Boot layout applies inside each module: `src/main/java`, `src/main/resources`, `src/test/java`, `src/test/resources`. Generated build artifacts live in `target/` and are gitignored.

Supporting directories at the repo root:

- `scripts/` — Docker image build and deployment helpers (`deploy.sh`, `deploy.cmd`, `backup.sh`, `postgres/init-databases.sh`).
- `docs/` — design plan and implementation notes.
- `tests/` — replayable verification assets: `tests/e2e/e2e.js` (headless-Chrome E2E, set `E2E_BASE`) and `tests/drill/m6-*.sh` (fault-drill scripts run from WSL against the deployed stack).
- `data/`, `logs/`, `backups/` — runtime artifacts; all gitignored.

## Build, Test, and Development Commands

The project ships a Maven Wrapper — no local Maven installation is required.

```bash
# Full build + tests (run from repo root)
.\mvnw.cmd clean package

# Run order service locally (defaults to port 8090 when 8080 is occupied)
.\mvnw.cmd -pl na-order-service spring-boot:run -Dspring-boot.run.arguments=--server.port=8090

# Run payment service locally
.\mvnw.cmd -pl na-payment-service spring-boot:run

# Docker Compose deployment (requires WSL2 or native Docker)
docker compose up -d --build --wait --wait-timeout 300
```

Local dev uses H2 file-based storage at `data/orderdb.mv.db`. Docker deployments use PostgreSQL via the `docker` Spring profile.

## Coding Style & Naming Conventions

- **Indentation:** 4 spaces (no tabs).
- **Package name:** `com.example.na` across all modules; module-specific sub-packages follow (`ordering`, `payment`, `catalog`, `user`, `config`, `shared`, `security`).
- **Class naming:** PascalCase. Service classes end in `Service`, repositories in `Repository`, controllers in `Controller`, DTOs have no special suffix, ports end in `Port`.
- **Test classes:** Suffix `*Test` (e.g. `OrderServiceTest`, `SecurityTest`). Surefire scans them by convention — no extra config needed.
- **Resource files:** YAML application configs live in `src/main/resources`; test configs in `src/test/resources` (e.g. `application-test.yml`).

## Testing Guidelines

- **Framework:** JUnit 5 via Spring Boot Test.
- **Profiles:** Tests run under the `test` Spring profile, which activates an in-memory H2 database regardless of the active environment config.
- **Running tests:** `.\mvnw.cmd test` (root) or `.\mvnw.cmd test -pl na-order-service` (module-specific).
- **Coverage target:** All public service methods and payment orchestration paths should have tests. Key areas covered today: order creation with concurrency-safe stock deduction, order cancellation with stock reversal, payment idempotency, cross-service authorization checks, and security guardrails (unauthenticated 401 JSON, login redirects).
- **New tests:** Place them in the same package as the class under test, or in a parallel `test/java` tree mirroring the main source structure. Name them `*Test.java`.

## Commit & Pull Request Guidelines

Commit messages follow conventional format based on observed project history: `type: short description`. Types include `feat`, `fix`, `refactor`, `test`, `docs`, `chore`. Keep the subject line under 72 characters.

Pull requests should:

- Reference the issue or ticket number in the description.
- Include a brief summary of the change and its motivation.
- Pass all CI checks (`mvnw clean package`) before requesting review.
- For UI or flow changes, attach a screenshot or screen recording.
- Update relevant docs in `docs/` when behavior or architecture shifts.

## Docker & Deployment Notes

Deployment is orchestrated with `docker-compose.yml` (services: `na-postgres`, `na-payment-service`, `na-order-service`). Database passwords and the internal service token are loaded from `.env`. A Postgres init script in `scripts/postgres/init-databases.sh` creates the two databases on first boot.

The build stage runs all tests inside the container; a test failure prevents image replacement, protecting a running deployment.

Data volume `na-pg-data` persists across `docker compose down`. Use `docker compose down -v` only when intentional data wipe is desired.

## Configuration Tips

- Copy `.env.example` to `.env` and fill in `POSTGRES_PASSWORD`, `NA_ORDER_DB_PASSWORD`, `NA_PAYMENT_DB_PASSWORD`, and `INTERNAL_SERVICE_TOKEN` before running Docker Compose.
- `.env` is gitignored; never commit real credentials.
- The `application-docker.yml` profile overrides datasource URLs for containerized PostgreSQL; local dev defaults to H2.
- When adding a new Spring Boot module, declare it in the root `pom.xml` `<modules>` block and add it to `.gitignore` if it produces runtime-generated directories.
