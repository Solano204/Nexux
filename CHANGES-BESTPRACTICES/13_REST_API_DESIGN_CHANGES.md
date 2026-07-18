# 13 — Diseño y Revisión de REST APIs (NEXUS)

Prompt maestro: "PROMPT MAESTRO — Diseño y Revisión de REST APIs" — 11 fases (0 a 10).

Investigado por grep + lectura real de los 27 controllers y los 16
`SecurityConfig`/`GlobalExceptionHandler` del monorepo, no asumido. Sin
builds, sin levantar ningún servicio (Docker/local) — auditoría 100%
estática, como pidió Carlos.

---

## Sección 0 — Fase 0: Diagnóstico

**Superficie real**: 13 de los 16 módulos Java exponen REST propio (~90
endpoints). `nexus-config-service` y `nexus-discovery-service` no tienen
controllers propios — son Spring Cloud Config/Eureka estándar, sin
superficie a auditar. `audit-write-native` (Quarkus) es un consumer Kafka
puro — sin `@Path`/`@GET` en ningún lado, solo `/q/health` del framework.

Distribución por servicio (controllers): identity (4), account (3, +1
advisor), transaction (2), fraud (1), ledger (2), notification (2),
analytics (3), ai-assistant (2), ai-kyc (2), risk-scoring (1),
saga-orchestrator (1, solo interno), audit-query-jvm (2), api-gateway (2,
fallback + feature-flag admin).

**Richardson Maturity Model: Nivel 2 en toda la plataforma.** Verbos HTTP
correctos, jerarquía de recursos coherente, códigos de estado por familia
correctos en el camino feliz (confirmado endpoint por endpoint en Fases
1-3 abajo). **Cero HATEOAS** (`_links`/`_actions` no existen en ningún
DTO de respuesta) → no Nivel 3. Ver Fase 8: decisión consciente, no gap
por descuido — todos los consumidores actuales son first-party.

**Soporte nativo del framework — confirmado, no supuesto:**
- Spring Boot es 3.x confirmado por uso real de `org.springframework.http.ProblemDetail`
  (API nativa desde Spring Framework 6 / Boot 3, sin librería adicional) en
  `identity`, `account` y `transaction`. RFC 9457 ya tiene soporte nativo
  disponible — no hace falta ninguna dependencia nueva para Fase 3.
- **`springdoc-openapi`/`swagger` — cero matches en los 16 `pom.xml`.**
  Ningún servicio genera OpenAPI hoy. Gap total, confirmado por grep, no
  supuesto (Fase 9).
- Ningún `@JsonNaming`/`PropertyNamingStrategy` en todo el repo → confirma
  `camelCase` uniforme (default de Jackson) en los ~90 endpoints, sin
  ninguna inconsistencia de payload que corregir (Fase 1, punto 3 del
  prompt, ya resuelto de fábrica).

**Inventario de violaciones por fase (severidad):**

| Fase | Hallazgo | Severidad |
|---|---|---|
| 3 + 10 | IDOR real en 5 endpoints de `account-service` (balance/analytics/events/advisor) + `401`/`403` no mapeados (caían a `500`) | **Crítico — corregido esta sesión** |
| 10 | IDOR real en 4 de 5 endpoints de `ledger-service` (ni siquiera leen `X-User-Id`) + sin `GlobalExceptionHandler` | **Crítico — corregido en sesión de seguimiento** |
| 3 | Formato de error inconsistente en 3 capas distintas (RFC 9457 centralizado / RFC 9457 inline parcial / whitelabel default de Spring) | Importante |
| 9 | Sin OpenAPI en ningún servicio | Importante |
| 5 | Paginación offset (`Pageable`) en colecciones de alta escritura (historial de transacciones, eventos de cuenta, decisiones de fraude) | Importante |
| 10 | `analytics-service`: `accountId` de la URL nunca se usa, la query se filtra solo por `userId` — contrato engañoso, no es hueco de seguridad | Cosmético/Importante |
| 1 | Prefijo interno inconsistente: `/internal/v1/*` (9 servicios) vs `/internal/api/v1/accounts` (account-service, único caso) | Cosmético |
| 1 | Endpoints con verbo en el path (`/change-password`, `/batch/trigger`, `/re-verify/{id}`) | Cosmético — excepción aceptada, ver Fase 1 |
| 4 | Idempotencia HTTP-header no implementada (existe equivalente funcional vía campo de body en transaction-service) | Bajo |
| 6 | Sin `ETag`/`If-Match` expuesto al cliente (concurrencia optimista sí existe a nivel JPA/`@Version`) | Bajo — no aplica hoy, ver Fase 6 |

---

## Sección 1 — Fase 1: Nomenclatura de recursos

**Sustantivos/plural/guiones: cumple en el 100% de los ~90 endpoints.**
No hay un solo `/getX`/`/createX` — todo colección+sub-recurso
(`/accounts/{id}/events`, `/ledger/accounts/{id}/entries`). Sin camelCase
ni snake_case en ningún segmento de URL.

**Payload case: uniforme, confirmado en la Fase 0** — sin acción.

**Endpoints con verbo en el path — inventario real, no corregido:**
`/auth/register`, `/auth/login`, `/auth/logout` (identity),
`/users/me/change-password` (identity), `/password-reset/request`
(identity), `/risk/batch/trigger` (risk-scoring, interno),
`/kyc/re-verify/{userId}` (ai-kyc, interno), `/transactions/{id}/force-compensate`
(transaction, interno), `/accounts/{id}/create-defaults` (account, interno).
**Excepción aceptada, no aplicada**: son acciones de negocio sobre un
pseudo-recurso (`auth`) o endpoints puramente internos/admin sin
consumidor externo — el prompt mismo (Fase 1, punto 4) pide no tocar
endpoints ya consumidos sin coordinación, y renombrar `/auth/login` →
`POST /sessions` rompe todos los clientes reales (frontend + Postman
collections documentadas) por una ganancia cosmética. Sin acción.

**Hallazgo real — prefijo interno inconsistente**: `account-service` usa
`/internal/api/v1/accounts` mientras los otros 9 servicios con endpoints
internos usan `/internal/v1/...` (sin el segmento `api`). Confirmado por
grep de `@RequestMapping` en las 10 clases `Internal*Controller`. **No
aplicado** — corregirlo rompe a los 2 consumidores reales de ese path
(`saga-orchestrator` y `ai-assistant-service`, confirmados como clientes
de account-service en `10_ARCHITECTURE_PATTERNS_CHANGES.md`), y es un
cambio multi-servicio coordinado por una inconsistencia puramente
cosmética. Documentado como pendiente de bajo valor.

---

## Sección 2 — Fase 2: Métodos HTTP

Auditados los ~90 endpoints contra la tabla de garantías. **Sin
violaciones de semántica de método** — cero GET que mute estado, cero
POST usado para lectura pura, cero PUT usado para actualización parcial.

**Observación real, no un problema**: `PUT` no se usa en ningún lugar de
la plataforma. Todas las actualizaciones son o bien `PATCH` de campo
puntual (`PATCH /notifications/{id}/read`, `/read-all`) o acciones de
negocio vía `POST` (`/accounts/{id}/freeze`, `/unfreeze`,
`/finalize-transfer`) disparadas por el saga orchestrator, nunca un
reemplazo completo de recurso desde el cliente. Consistente con la
arquitectura real (mutaciones financieras van por comando explícito, no
por edición directa de recurso) — no es un hueco a llenar.

**Candidatos a idempotencia (Fase 4)**: `POST /transactions/transfer`,
`POST /transactions/payment`, `POST /accounts/{id}/reserve`,
`POST /ledger/postings/manual`, `POST /fraud/analyze` — todos con efecto
de negocio real, confirmados abajo.

Método `QUERY` (RFC 10008): no hay ningún caso de lectura compleja
forzada a POST-disfrazado-de-GET en el inventario actual (`GET /search`
en transaction-service usa un solo `@RequestParam`) — no aplica todavía.

---

## Sección 3 — Fase 3: Errores y RFC 9457

**Hallazgo real: 3 formatos de error distintos conviven hoy en la misma
plataforma**, confirmado leyendo el código, no la documentación:

1. **RFC 9457 completo y centralizado** (`identity`, `account`,
   `transaction`) — `@RestControllerAdvice` con `ProblemDetail`,
   `type`/`title`/`errorCode`/`timestamp` consistentes, catch-all de
   `Exception.class` → `500` genérico sin fugar detalle interno. Este es
   el patrón correcto — ya production-grade, sirve de referencia.
2. **RFC 9457 parcial e inline** (`fraud`, `ledger`) — cada método de
   controller arma su propio `ProblemDetail` en el `catch`, sin
   `@RestControllerAdvice` ni catch-all. Inconsistente en campos (algunos
   sin `title`/`errorCode`/`timestamp`) y sin red de seguridad: una
   excepción no anticipada en esos 2 servicios cae al manejador default
   de Spring Boot, no a un `ProblemDetail`.
3. **Whitelabel default de Spring Boot** (`notification`, `ai-assistant`,
   `ai-kyc`, `analytics`, `risk-scoring`, `saga-orchestrator`,
   `audit-query-jvm`, `api-gateway` — 8 de 13 servicios) — sin
   `GlobalExceptionHandler` alguno. Confirmado que
   `server.error.include-stacktrace`/`include-message` no están seteados
   en ningún `application*.yml` → quedan en el default seguro de Boot
   (`never`), así que **no hay fuga de stack trace**, pero el shape de
   error (`{timestamp, status, error, path}`) no tiene `type`/`errorCode`
   ni es `application/problem+json` — un cliente no puede manejar errores
   de forma uniforme entre estos 8 servicios y los otros 5.

**Regla "nunca 200 con error en el body": cumple en el 100% de los casos
revisados** — todos los `ResponseEntity.status(...)` encontrados usan el
código correcto (`404`/`409`/`422`/`503` según corresponda), sin ningún
`200` disfrazando una falla.

### Aplicado esta sesión — bug real encontrado auditando, no solo un gap de forma

Auditando qué pasaba en `account-service` cuando una excepción de auth se
lanzaba, encontré que **`UnauthorizedException` y `AccessDeniedException`
no tenían `@ExceptionHandler` registrado** pese a que sus propios
Javadocs dicen *"Maps to HTTP 401"* / *"Maps to HTTP 403"* — caían al
catch-all genérico y devolvían **`500`** en vez de `401`/`403`. Esto
viola directamente la regla no-negociable de esta fase: el código de
estado no reflejaba la naturaleza real del error, y ningún proxy/monitor
podía distinguir "no autenticado" de "error interno real".

**Corregido en `nexus-account-service/.../web/advice/GlobalExceptionHandler.java`**:
agregados los 2 handlers faltantes, mismo estilo que el resto del archivo
(`ProblemDetail` + `title`/`type`/`errorCode`/`timestamp`) — `401` para
`UnauthorizedException`, `403` para `AccessDeniedException`. Ver Sección
10 para el fix relacionado (el gap de autorización que hacía que
`AccessDeniedException` casi nunca se lanzara para empezar).

**No aplicado — extender RFC 9457 centralizado a los 8 servicios sin
`GlobalExceptionHandler`**: es el fix de mayor impacto/costo de esta
fase, pero escribir 8 manejadores nuevos requiere leer las excepciones de
dominio propias de cada servicio una por una para no inventar mapeos de
status incorrectos. Recomendación concreta para la próxima sesión:
empezar por `fraud-service` y `ledger-service` (ya tienen el hábito
`ProblemDetail`, solo falta centralizarlo en un `@RestControllerAdvice`
con catch-all — el salto más barato), después los 6 restantes.

---

## Sección 4 — Fase 4: Idempotencia

**El patrón central ya está implementado y funcionando en el endpoint
más sensible de la plataforma** (`POST /transactions/transfer` y
`/payment`) — confirmado leyendo `TransactionCommandService.initiateTransaction`:
recibe `idempotencyKey` (validado `@NotBlank @Size(min=8,max=64)` en el
DTO), hace `findByUserIdAndIdempotencyKey` ANTES de crear la transacción,
y devuelve la respuesta original si ya existe. Es exactamente el diseño
que pide esta fase — con una única diferencia de forma: **la key viaja en
el body del request, no en un header `Idempotency-Key`.** Funcionalmente
equivalente (mismo contrato: el cliente genera el token, el servidor
deduplica antes de ejecutar el efecto), y cambiar el transporte a header
ahora rompería al frontend real por una preferencia de estilo — no
aplicado.

**`POST /accounts/{id}/reserve` tiene una red de seguridad distinta pero
real**: constraint único `uq_active_reservation` en Postgres (confirmado
en `GlobalExceptionHandler.handleDataIntegrity`, mapea a `409 Duplicate
Reservation`). No es un key-store explícito, pero cumple la misma
garantía — un reintento no duplica la reserva de fondos, falla limpio con
`409`.

**Sin protección confirmada**: `POST /ledger/postings/manual`,
`POST /fraud/analyze`, `POST /fraud/review/{id}/outcome`. No inspeccioné
sus service layers en profundidad esta sesión — quedan como candidatos a
revisar antes de aplicar el patrón, no descartados ni confirmados como
vulnerables.

---

## Sección 5 — Fase 5: Paginación

**Offset (`Pageable`/`Page<T>` de Spring Data) usado en 3 colecciones que
son exactamente el caso que este prompt marca como candidato obligatorio
a cursor**: historial de transacciones (`GET /transactions`, alta
escritura constante), eventos de cuenta (`GET /accounts/{id}/events`,
append-only de alta frecuencia), decisiones de fraude paginadas por
usuario (`GET /fraud/decisions/user/{id}`). Los dos problemas que describe
el prompt aplican tal cual: degradación con la profundidad de página en
tablas que crecen sin parar, y riesgo de saltos/duplicados si se inserta
una fila nueva entre dos requests de paginación consecutivos.

`ledger-service` usa paginación manual `page`/`size` (no `Pageable`) en
`/entries` — mismo problema de offset, forma distinta.

**No migrado esta sesión** — cambiar el contrato de respuesta (de
`Page<T>` con `totalElements`/`totalPages` a cursor con `next_cursor`) es
un cambio de contrato público que rompe al frontend actual sin un plan de
compatibilidad hacia atrás, que el prompt pide explícitamente diseñar
antes de tocar el primer endpoint. Recomendación: pilotar en
`GET /transactions` (el candidato de mayor volumen real) antes de tocar
los otros dos.

**Offset sigue siendo aceptable, sin tocar**: no encontré ningún endpoint
de catálogo estático (ej. lista de merchants, tipos de cuenta) que
justificara paginación por página — no aplica hoy.

---

## Sección 6 — Fase 6: Concurrencia optimista

**El control de concurrencia ya existe, a nivel de persistencia, no de
HTTP.** `@Version` de JPA confirmado en `Account`, `Transaction`, `User`,
`Posting`, `RiskProfileJpaEntity` — y `account-service` ya mapea
`OptimisticLockingFailureException` → `409 Conflict` con
`Retry-After: 1` en su `GlobalExceptionHandler`. La garantía de fondo que
pide esta fase (nunca sobrescribir un lost update silenciosamente) **ya
está cubierta** para las escrituras concurrentes reales de la
plataforma (todas via comando/saga, nunca un PUT/PATCH de cliente con
body completo).

**`ETag`/`If-Match` no aplica todavía porque no hay ningún endpoint
"cliente lee el recurso, lo edita, lo reenvía completo"** — el patrón que
esta fase protege. El día que exista un endpoint tipo
`PATCH /accounts/{id}/limits` editado directamente por un usuario (no por
el saga), ahí sí correspondería exponer la versión como `ETag`. Sin
acción por ahora — documentado para cuándo aplicaría, no para aplicar hoy.

---

## Sección 7 — Fase 7: Versionado

**Confirmado**: todos los consumidores actuales (frontend propio,
`saga-orchestrator`, y los demás servicios NEXUS vía `X-Internal-Service`)
están bajo control directo de Carlos — no hay integrador externo
desacoplado del ciclo de release hoy.

**Estrategia ya adoptada, consistente**: versionado por URL (`/api/v1/`,
`/internal/v1/`) en 12 de 13 servicios (la única inconsistencia es el
segmento `/internal/api/v1/` de account-service, ya cubierto en Fase 1).
Con todos los consumidores propios, la disciplina de compatibilidad hacia
atrás (campos nuevos opcionales, nunca quitar/renombrar sin ciclo de
deprecación) es suficiente por ahora — no hace falta versionado formal
(`v2`) todavía.

**Criterio escrito para disparar versionado formal**: el día que un
consumidor externo real (partner, app de terceros, integración pública)
empiece a llamar al API fuera del ciclo de release de Carlos, ese es el
punto — no antes. Hasta entonces, cualquier cambio de contrato en
`/api/v1/**` debe revisarse contra "¿rompe al frontend actual?" antes de
mergear.

---

## Sección 8 — Fase 8: HATEOAS

**Candidatos genuinos identificados** (máquina de estados real donde la
acción válida depende del estado actual): `Transaction`
(`PENDING`→`COMPLETED`/`FAILED`/`REVERSED`, confirmado en los docs de
`AWS-DOCKER-WORKFLOWS/`), `FraudDecision` (`PENDING_REVIEW`→
aprobado/rechazado vía `/review/{id}/outcome`), estado de verificación
KYC.

**No aplicado, con la misma razón que reconoce el propio prompt (Fase 8,
punto 2)**: el único consumidor de estos 3 recursos es el frontend propio
de Carlos — no hay cliente externo que se beneficie de descubrir
transiciones vía `_links` en vez de tenerlas hardcodeadas. El retorno de
implementar HATEOAS hoy es bajo. Queda documentado como el primer
candidato si en algún momento se suma un consumidor externo real (mismo
disparador que Fase 7).

---

## Sección 9 — Fase 9: OpenAPI contract-first

**Confirmado en Fase 0: no existe una sola especificación OpenAPI en los
16 servicios.** Gap total, no parcial.

**Recomendación concreta, no aplicada esta sesión**: `springdoc-openapi-starter-webmvc-ui`
(o `-webflux-ui` para `api-gateway`, que es reactivo) — genera el
contrato automáticamente desde las anotaciones `@RestController`/
`@RequestMapping`/DTOs ya existentes, sin mantener YAML a mano y sin
reescribir nada. Es exactamente el caso que el prompt pide priorizar
("preferí lo que el framework ya ofrece nativamente"). **No lo agregué
yo mismo esta sesión** porque toca 13 `pom.xml` distintos y cada uno
necesitaría un build para confirmar que no rompe nada — más alcance del
que corresponde a una auditoría, y el propio prompt pide piloto +
confirmación antes de un cambio que toca tantos servicios a la vez.

**Piloto sugerido**: empezar por `account-service` y `transaction-service`
(los 2 más estables, con `GlobalExceptionHandler` completo — el
`ProblemDetail` de sus errores ya documenta bien en OpenAPI sin trabajo
extra), confirmar el resultado, después replicar al resto.

---

## Sección 10 — Fase 10: Seguridad y rate limiting

**El patrón `permitAll()` en casi todos los `SecurityConfig.java` es
arquitectura intencional, no un descuido** — confirmado cruzando con
`10_ARCHITECTURE_PATTERNS_CHANGES.md`: `api-gateway` es el único
validador de JWT real; downstream, cada servicio confía en el header
`X-User-Id` (rutas `/api/v1/**`) o `X-Internal-Service` contra allow-list
(rutas `/internal/**`) que el gateway ya validó. Por eso Spring Security
"deja pasar todo" a nivel de filtro — la autorización real vive en cada
controller, leyendo el header. Esto es correcto **si y solo si** cada
controller efectivamente valida ese header antes de tocar datos. Ahí es
donde encontré 2 problemas reales, de severidad muy distinta:

### Aplicado esta sesión — IDOR real en `account-service`

`AccountController`/`AccountAdvisorController` exponen 6 endpoints
`{accountId}`-scoped. Solo `GET /{accountId}` (detalle de cuenta)
verificaba que la cuenta perteneciera al usuario del `X-User-Id`. Los
otros 5 —`GET /{accountId}/balance`, `/events`, `/analytics`,
`GET /{accountId}/advisor/insights`, `POST /{accountId}/advisor/chat`—
**tomaban `accountId` de la URL y nunca comparaban contra el `userId` del
header**: cualquier usuario autenticado que conociera o adivinara el UUID
de cuenta de otra persona podía leer su balance, su historial y sus
insights financieros generados por IA. Confirmado leyendo el controller Y
el service layer completo (`AccountQueryService`, `AccountAdvisorService`)
— no hay ninguna verificación de propiedad en ningún punto de esas 5
rutas antes del fix.

**Corregido:**
- `AccountQueryService`: nuevo método `verifyOwnership(accountId, requestingUserId)`
  (reemplaza la verificación duplicada que solo vivía en `getAccountDetail`,
  ahora centralizada y reusada) — lanza `AccountNotFoundException` (`404`)
  o `AccessDeniedException` (`403`, ahora sí mapeado — ver Sección 3).
  `getBalanceCached`, `getAnalytics` y `getAccountEvents` ahora exigen
  `requestingUserId` y llaman a `verifyOwnership` antes de tocar cualquier
  dato — de paso, `getBalanceCached` quedó más simple (reusa la `Account`
  que ya trajo `verifyOwnership` en vez de volver a consultarla en el
  cache-miss).
- `AccountController`: los 3 endpoints ahora extraen `X-User-Id` y lo pasan.
- `AccountAdvisorController`: inyecta `AccountQueryService` y llama
  `verifyOwnership` antes de `getInsights` (que antes ni siquiera leía el
  header) y antes de abrir el stream SSE de `chat`.

Sin migración de datos, sin build ejecutado por mí (Carlos corre
`mvn package -pl nexus-account-service` cuando quiera verificar).

### Aplicado en la sesión de seguimiento — mismo patrón de bug en `ledger-service`, corregido con la opción CDC

`LedgerController` exponía 4 endpoints `{accountId}`-scoped
(`/balance`, `/entries`, `/summary/monthly`, y `getPosting` por
`transactionId`) que **ni siquiera leían el header `X-User-Id`** — cero
extracción, cero verificación. El único método que sí extraía `userId`
(`explainTransactions`) lo usaba solo para nombrar la sesión, nunca para
verificar que `accountId` le perteneciera. Mismo bug que tenía
`account-service`. Tampoco tenía `GlobalExceptionHandler`: el
`throw new RuntimeException(...)` de `extractUserId` caía al whitelabel
de Spring Boot.

**Corrección inicial descartada, corrección real encontrada**: mi
primera lectura (arriba, sesión anterior) decía que `LedgerQueryService`
no tenía ningún dato de `userId` local. Era incorrecto — no había leído
el modelo de dominio `ChartOfAccount`, que sí tiene columna `user_id`
(`chart_of_accounts.user_id`), poblada por
`AccountCreatedConsumer` (`infrastructure/kafka/AccountCreatedConsumer.java`),
un listener real de Kafka ya en producción sobre el tópico
`accounts.created`, "*published by nexus-account-service via Debezium
outbox*" (comentario textual del propio archivo). Es decir: **la opción
CDC que pediste ya estaba construida** — solo no se estaba usando para
autorización.

**Aplicado:**
- `ChartOfAccountRepository` inyectado en `LedgerQueryService` (ya
  existía, usado hoy solo por el consumer).
- `verifyAccountOwnership(accountId, requestingUserId)`: busca la cuenta
  local vía `findByAccountIdAndIsActiveTrue` (mismo método que ya usa el
  consumer para su chequeo de idempotencia) y compara `userId`. `404` si
  no existe localmente (aún no llegó el evento o la cuenta no existe),
  `403` si pertenece a otro usuario.
- `verifyPostingOwnership(postingId, requestingUserId)`: un posting tiene
  2 lados (débito/crédito, posiblemente en 2 cuentas distintas en una
  transferencia) — resuelve las cuentas involucradas vía
  `LedgerEntry.accountId` y autoriza si el usuario es dueño de
  **cualquiera** de los dos lados.
- `LedgerController`: los 4 endpoints ahora llaman a la verificación
  correspondiente antes de tocar cualquier dato; `explainTransactions`
  ahora también verifica antes de abrir el stream SSE.
- Nuevas `UnauthorizedException`/`AccessDeniedException` en
  `ledger-service` (no existían — `extractUserId` lanzaba
  `RuntimeException` genérica).
- Nuevo `GlobalExceptionHandler` (`web/advice/`, ledger-service no tenía
  ninguno) — mismo patrón `ProblemDetail` que account-service:
  `401`/`403`/`404` para las excepciones nuevas + catch-all `500` sin
  fuga de detalle interno. No interfiere con el manejo inline que ya
  tiene `InternalLedgerController` (ese sigue atrapando sus propias
  excepciones antes de que lleguen al advice).

**Sin llamada síncrona nueva a account-service** — se mantiene la
característica que destacaba `10_ARCHITECTURE_PATTERNS_CHANGES.md`
("cero llamadas salientes a otro servicio NEXUS" en el núcleo
financiero). El tradeoff real de esta opción, para que quede explícito:
hay una ventana de consistencia eventual entre que `account-service` crea
la cuenta y el consumer de Kafka la replica a `chart_of_accounts` — si
alguien llama a estos endpoints de ledger en esa ventana (milisegundos en
la práctica, el consumer ya corre hoy), verá `404` en vez de sus datos.
Aceptable para un ledger de solo lectura; no lo sería si esto protegiera
una escritura.

Sin migración de datos — el consumer y la columna `user_id` ya existían.
Sin build ejecutado por mí (Carlos corre `mvn package -pl nexus-ledger-service`
cuando quiera verificar).

### Anomalía real, no es un hueco de seguridad — `analytics-service`

`AnalyticsController` sí extrae `X-User-Id` en sus 3 endpoints
`{accountId}`-scoped, pero **nunca usa el `accountId` de la URL** — las
queries (`getMonthlyAnalyticsSafe`, `getSpendingTrend`, `getTopMerchants`)
se filtran solo por `userId`. No es un IDOR (no se puede leer el dato de
otro usuario), pero el contrato de la URL miente: `GET /accounts/{id}/monthly/...`
sugiere analítica por cuenta específica, y en realidad cualquier
`accountId` devuelve siempre el agregado del usuario completo. No lo toqué
— puede ser el diseño intencional (analítica a nivel usuario, no por
cuenta) o un bug de que nunca se conectó el filtro; solo Carlos sabe cuál
de las dos es la intención real de producto.

### Auditados en la misma ronda — `notification`, `analytics`, `ai-kyc`, `fraud`

Mismo método que account/ledger: leer cada controller `{accountId}`/
`{userId}`-scoped y su service layer completo, no solo el controller.

**`notification-service` — sin IDOR.** `NotificationController` y
`PreferencesController` ya escriben/leen consistentemente scopeados por
el `userId` extraído del header, no por un ID de la URL — `markAsRead`
incluso usa `findByNotificationIdAndUserId` (composite key), y
`updatePreferences` pisa explícitamente `updated.setUserId(userId)`
**antes** de guardar, así que un cliente no puede mandar el `userId` de
otra persona en el body y que se guarde. Buen patrón defensivo ya
existente. **Sí repetía el bug de Fase 3**: `extractUserId` lanzaba
`RuntimeException` cruda, sin `GlobalExceptionHandler` en todo el
servicio → auth faltante devolvía `500`. **Corregido**: nueva
`UnauthorizedException` + `GlobalExceptionHandler` (mismo patrón
`ProblemDetail`), wireado en los 2 controllers.

**`analytics-service` — sin IDOR, pero confirma la anomalía ya
documentada en la ronda anterior.** Releí `InsightsController`
(`GET /accounts/{accountId}/insights/{yearMonth}`) además de
`AnalyticsController` — **mismo patrón en los 4 endpoints de los 2
controllers**: `accountId` se captura del path y nunca se usa, todo se
resuelve por `userId`. Confirma que es diseño consistente (agregación a
nivel usuario en Mongo/Redis, no por cuenta), no un bug aislado — sigue
sin tocarse, es decisión de producto. **Mismo bug de Fase 3 que
notification, corregido igual**: `UnauthorizedException` +
`GlobalExceptionHandler` nuevos, wireados en ambos controllers.

**`ai-kyc-service` — sin IDOR, patrón ya correcto.**
`getStatus(verificationId)` usa `findByVerificationIdAndUserId`
(composite key, mismo patrón defensivo que notification). `verify` solo
escribe bajo el `userId` del propio caller. Único detalle menor: usa
`@RequestHeader("X-User-Id")` (no lectura manual), así que si falta el
header Spring devuelve `400` en vez de `401` — un matiz de status code,
no un hueco de seguridad, y no lo toqué (arreglarlo bien requeriría
inventariar las excepciones propias del pipeline de KYC que no leí esta
ronda, para no adivinar mapeos).

**`fraud-service` — no aplica IDOR (no tiene endpoints de cara al
usuario), pero encontré algo más serio: cero verificación de identidad
de servicio.** `InternalFraudController` es 100% `/internal/v1/fraud`.
Su `SecurityConfig` decía textualmente *"In production: IP-restricted to
Docker network CIDR only"* — a diferencia de `account-service` y
`transaction-service`, que además validan `X-Internal-Service` contra
una allow-list vía un filtro (`InternalServiceAuthFilter`) **antes** de
que la request llegue al controller. fraud-service no tenía ese filtro:
dependía 100% de que la red Docker fuera la única barrera. Con esa única
capa, endpoints destructivos como `blacklistMerchant`/`unblacklistMerchant`
y `recordReviewOutcome` (que puede marcar una decisión `REVIEW` como
`CLEARED` y dejar pasar la transacción) **no verifican identidad de
quién llama en absoluto** — `operatorId`/`reviewerId` son opcionales,
solo para el log de auditoría, nunca comparados contra nada.

**Aplicado**: porteado el mismo `InternalServiceAuthFilter` de
`account-service` a `fraud-service` — allow-list con los llamadores
reales/plausibles confirmados por código (`nexus-ai-assistant-service`,
único llamador síncrono confirmado — su `FraudServiceClient` ya manda
`X-Internal-Service` vía `InternalServiceHeaderInterceptor`, así que este
cambio no lo rompe) más `nexus-saga-orchestrator`, `nexus-audit-query-jvm`,
`nexus-transaction-service`, `nexus-api-gateway` como consumidores
plausibles según los comentarios del propio controller ("Compliance
dashboard", "Audit Service") — agregarlos a la allow-list no reduce
seguridad aunque no llamen todavía, solo la reduciría si me quedo corto y
bloqueo a alguien real que sí llama. Si en producción alguno de estos NO
debería tener acceso, hay que sacarlo de la lista explícitamente.

Nota aparte, no relacionada con seguridad: `FraudServiceClient.getRecentAlertSummaries`
llama a `GET /internal/v1/fraud/alerts/user/{userId}` — ese endpoint **no
existe** en `InternalFraudController` (los reales son `/decisions/**`,
no `/alerts/**`). Es un 404 silencioso hoy, absorbido por el `catch` del
cliente. Bug funcional real, no de seguridad — lo dejo anotado, no lo
arreglé (no reconstruí qué endpoint debería ser el correcto).

### Tercera ronda — `ai-assistant`, `risk-scoring`

**`ai-assistant-service` — sin IDOR, y por una razón estructural, no
suerte.** `AiAssistantController` (`/chat`, `/chat/analyze-document`) y
`DocumentAnalysisController` (`/documents/analyze`) no reciben ningún ID
de recurso ajeno desde el cliente — todo lo que identifica al usuario o
a la conversación sale del header `X-User-Id` o se deriva de él
server-side: `ChatService.chat` arma `conversationId = userId + ":" +
sessionId` **siempre con el `userId` del header como prefijo**, así que
aunque un atacante adivine el `sessionId` de otro usuario, la clave de
memoria de conversación resultante nunca coincide con la real (queda
`"atacante:sessionAdivinado"` ≠ `"víctima:sessionReal"`) — no hay
secuestro de sesión de chat posible. Buen diseño ya existente, no algo
que verifiqué al voleo: leí `ChatService.chat` completo para confirmarlo.
**Sí repetía el mismo bug de Fase 3** en los 2 controllers
(`RuntimeException` cruda, sin `GlobalExceptionHandler` en todo el
servicio) — corregido igual que las rondas anteriores.

**`risk-scoring-service` — mismo hueco que tenía `fraud-service` antes
del fix, y más expuesto todavía.** `InternalRiskController`
(`/internal/v1/risk`) no tenía ningún filtro de identidad de servicio —
ni siquiera el `SecurityConfig` mínimo que ya usaban los demás
(`.requestMatchers("/internal/**").permitAll()` sin ningún filtro
después). `GET /profiles/{userId}` devuelve el perfil de riesgo completo
de cualquier usuario a quien sea que llegue a la red del servicio;
`POST /batch/trigger` dispara el batch nocturno completo (respaldado por
OpenAI, con rate limiting documentado en
`10_ARCHITECTURE_PATTERNS_CHANGES.md` por un incidente real de cuota
agotada) sin ninguna verificación — cualquiera que lo alcance puede
generar costo/consumo de cuota a demanda.

**Diferencia importante con el fix de fraud-service**: ahí encontré un
llamador síncrono real (`ai-assistant-service`) que confirmaba que la
allow-list no rompía nada. Acá **no encontré ningún llamador HTTP real**
— busqué explícitamente un `RiskServiceClient` o similar y no existe;
`fraud-service` lee los datos de riesgo vía **Redis** (escrito por
risk-scoring, leído directo por fraud — confirmado en
`BehavioralAnalysisTool.java`), no vía este API REST. Lo que sugiere que
estos endpoints son de uso manual/admin hoy (el propio docstring del
controller dice "admin + inter-service"), no llamados en producción por
otro servicio.

**Aplicado de todos modos** — mismo filtro `InternalServiceAuthFilter`,
con una allow-list que dejé marcada explícitamente en el código como
**no verificada** (`nexus-fraud-service`, `nexus-saga-orchestrator`,
`nexus-api-gateway`, por plausibilidad arquitectónica, no por evidencia
de código como en fraud-service). Justificación: dejar un endpoint que
expone perfiles de riesgo completos y puede disparar gasto de OpenAI sin
ningún control de identidad no es defendible aunque hoy nadie lo esté
llamando — y si vos lo usás manualmente vía curl/Postman para admin, esto
significa que ahora necesitás mandar el header `X-Internal-Service` con
alguno de los 3 valores de la lista (o agregar el tuyo) para que te siga
funcionando.

### Cuarta ronda — `saga-orchestrator`, `audit-query-jvm`, `api-gateway`

**`saga-orchestrator` — mismo hueco que fraud/risk-scoring antes del
fix.** `InternalSagaController` (`/internal/v1/sagas`) exponía
`GET /onboarding/{userId}` (progreso de KYC del onboarding),
`GET /transfer/{transactionId}` y su historial paso a paso, sin ninguna
verificación de identidad de servicio — mismo `SecurityConfig` mínimo sin
filtro. **Aplicado**: mismo `InternalServiceAuthFilter`, allow-list
también sin llamador HTTP real confirmado (esto es 100% Kafka-driven en
producción, ver `01_SAGA_PATTERN_CHANGES.md`) — marcada igual como
placeholder en el código.

**`audit-query-jvm` — el hallazgo más severo de toda esta auditoría.**
Su propio `SecurityConfig` dice textualmente: *"Compliance endpoints
require COMPLIANCE_OFFICER role. Admin endpoints require ADMIN role.
`@PreAuthorize` on controllers enforces method-level security."*, y
tiene `@EnableMethodSecurity` prendido — pero `authorizeHttpRequests`
permite `/api/v1/audit/**` completo, y **ningún controller usa
`@PreAuthorize`** (`ComplianceController` lo importaba y nunca lo
aplicaba a ningún método — import muerto). Resultado real: `AuditController.getUserEvents`
(timeline completo de auditoría de cualquier usuario),
`ComplianceController.query` (búsqueda en lenguaje natural sobre el
audit trail completo, con `targetUserId` arbitrario en el body),
`getUserTimeline`, `getAlerts`, `getReports` — **cero verificación,
ni siquiera de header `X-User-Id`**, en un servicio cuyo propósito
completo es compliance/investigación regulatoria.

**Aplicado**: el rol SÍ existe de punta a punta y ya viaja limpio —
`identity-service`'s `User.roles` (`TEXT[]`, default `["USER"]`) →
`JwtIssuer` lo mete como claim `roles` en el JWT → gateway's
`JwtAuthenticationFilter` lo lee de `JwtClaims.roles()` y lo reenvía como
header `X-User-Roles` → `RequestSanitizationFilter` (global, corre en
`HIGHEST_PRECEDENCE`) confirma que **`X-User-Roles` se stripea del
request del cliente igual que `X-User-Id`**, así que es tan confiable
como ese header ya lo es en el resto de la plataforma. Con esa cadena ya
real, implementé el chequeo manual (mismo estilo que el resto de esta
auditoría, no `@PreAuthorize`/method security completo — habría
requerido un converter de `Authentication` que no existe en ningún otro
servicio del monorepo, más riesgo para un fix que ya cierra el hueco
real): nuevas `UnauthorizedException`(401)/`ForbiddenException`(403) +
`GlobalExceptionHandler`, y un `requireComplianceRole(request)` que exige
`COMPLIANCE_OFFICER` o `ADMIN` en `X-User-Roles`, wireado en los 6
endpoints de los 2 controllers.

**`api-gateway` — auditado, mayormente correcto, con 2 hallazgos
reales.**

1. `FeatureFlagAdminController` (`/internal/v1/feature-flags`, puede
   apagar features de toda la plataforma) — **ya está bien protegido**:
   chequeo de IP inline (`172.20.0.0/16` + localhost) directo en el
   controller, con un comentario que explica exactamente por qué no
   alcanza con el predicate de ruta (este controller lo sirve WebFlux
   directo, no pasa por el `RouteLocator`, así que los predicates
   per-ruta del gateway nunca lo ven). Diseño consciente, no un
   descuido — nada que tocar.
2. 2 bugs funcionales de ruteo, no de seguridad, **documentados, no
   tocados** (no reconstruí cuál era el comportamiento correcto
   pretendido): la ruta programática a `risk-scoring-service`
   (`/api/v1/risk/**`) apunta a endpoints (`/evaluate`, `/score/**`) que
   no existen en ningún controller real de `risk-scoring-service` (el
   único que existe es `/internal/v1/risk/**`); la ruta a
   `saga-orchestrator` usa `/internal/v1/saga/**` (singular) mientras el
   controller real está en `/internal/v1/sagas` (plural) — no matchean.

**Hallazgo adicional, fuera del alcance pedido pero encontrado
revisando cómo el gateway rutea a `identity-service` — no corregido,
necesita tu decisión.** `InternalController` de `identity-service`
(`/internal/v1/users/{userId}/identity`, `/kyc/status` — devuelve estado
de KYC e identidad) documenta en su propio Javadoc 2 mecanismos de
protección que **ninguno de los dos existe en el código real**:
- Dice que usa el header `X-Gateway-Internal` "set by the gateway after
  IP allowlist validation" — ese header está en la lista de headers
  protegidos de `RequestSanitizationFilter` (así que un cliente no lo
  puede falsificar), pero **ningún filtro del gateway lo setea jamás** —
  grep completo, cero resultados. El controller tampoco lo lee.
- `nexus-auth-lambda` (AWS, fuera de la red Docker) sí llama a
  `GET /internal/v1/users/{userId}/kyc/status` con un header
  `X-Plane-Bridge-Secret` ("shared secret from AWS Secrets Manager,
  **validated by identity service**" según el Javadoc del cliente
  Lambda) — pero `identity-service` **no valida ese header en ningún
  lado**, confirmado por grep en todo el módulo.

La única protección real hoy es el predicate `RemoteAddr=172.20.0.0/16`
en la ruta del gateway (`application.yml`, confirmado que sí está
presente ahí, a diferencia de los otros 2 casos). Para tráfico
Docker-a-Docker eso es razonable (mismo modelo que fraud/risk-scoring/saga
antes de mis fixes). El problema era el caller AWS Lambda, que viene de
fuera de la red Docker.

### Quinta ronda — cerrado: `X-Plane-Bridge-Secret` en `identity-service`

Investigué más antes de tocar nada, porque romper esto mal significa
romper el flujo de login real vía Lambda. Encontré que el secreto **ya
está completamente aprovisionado por Terraform** — no era una decisión
de arquitectura pendiente, era un cabo suelto ya documentado:

- `terraform/secrets.tf`: `random_password.plane_bridge_secret` (48
  caracteres) → `aws_secretsmanager_secret.plane_bridge`
  (`nexus-josue/plane-bridge-secret`). El comentario del propio archivo
  decía literalmente *"none of the current docker-compose-prod.yml
  services do this yet, so this is the value to wire into whichever
  service ends up validating the header"* — un TODO explícito.
- `terraform/lambda-auth.tf`: ese valor ya se inyecta como
  `PLANE_BRIDGE_SECRET` en el entorno de `nexus-auth-lambda`.
- `LocalPlaneBridgeClient.fetchKycStatus()` (Lambda) ya manda el header
  `X-Plane-Bridge-Secret` en cada llamada a
  `GET /internal/v1/users/{userId}/kyc/status` — confirmado, lado Lambda
  100% completo.
- `terraform/outputs.tf` ya genera `PLANE_BRIDGE_SECRET=...` en el bloque
  copy-paste para `.env` (`terraform output -raw env_block >> .env`).

Lo único que realmente faltaba era el lado de `identity-service`. Un
detalle importante que también encontré: el secreto Terraform está
pensado para viajar en `.env` como var plana (así lo genera el output),
pero seguí el patrón que ya usa este compose para credenciales igual de
sensibles (`AWS_ACCESS_KEY_ID`/`POSTGRES_PASSWORD`/etc. — todas
`_FILE` + Docker secret, no interpolación directa en `environment:`, para
que el valor real nunca aparezca en `docker inspect`) en vez del patrón
más simple que usan valores no-sensibles como `COGNITO_USER_POOL_ID`.
Confirmé que `docker-entrypoint.sh` ya resuelve genéricamente *cualquier*
`*_FILE` a su variable plana antes de arrancar la JVM — no hizo falta
tocar el entrypoint.

**Aplicado:**
- `InternalController.getKycStatus`: nuevo parámetro
  `@RequestHeader(value="X-Plane-Bridge-Secret", required=false)`,
  comparado con `MessageDigest.isEqual` (constante en tiempo — es un
  credential tipo bearer, no un ID) contra
  `nexus.identity.internal.plane-bridge-secret`. **Falla cerrado**: si la
  property está vacía (default), rechaza *todo*, no acepta un "vacío
  contra vacío" como match. Reusé la `UnauthorizedException` que
  `identity-service` ya tenía y ya mapea a `401` — no agregué una
  excepción nueva para esto.
- `getIdentitySummary` (el otro endpoint de este controller) **lo dejé
  como estaba** — ningún caller confirmado le manda este secret, y si
  fraud-service/account-service realmente lo llaman (el Javadoc lo dice,
  pero no encontré código cliente real para ninguno de los dos), vienen
  de dentro de la red Docker, donde `RemoteAddr` sigue siendo un límite
  razonable. Aplicar el mismo chequeo ahí sin evidencia real de que esos
  callers también manden el header habría sido adivinar y arriesgar
  romperlos.
- `nexus-identity-service-prod.yml`: nueva property
  `nexus.identity.internal.plane-bridge-secret: ${PLANE_BRIDGE_SECRET:}`.
- `docker-compose-prod.yml`: `PLANE_BRIDGE_SECRET_FILE` en el
  `environment` de identity-service + `plane_bridge_secret` en su
  `secrets:` + registrado en el bloque `secrets:` de nivel superior
  (`./secrets/plane_bridge_secret.txt`).
- `secrets/plane_bridge_secret.txt`: creado con un placeholder obvio
  (`REPLACE_ME_WITH_TERRAFORM_OUTPUT_PLANE_BRIDGE_SECRET`) — **no
  generé un secreto nuevo**, porque el real ya existe en Secrets
  Manager y debe ser el mismo que ya tiene `nexus-auth-lambda` (si
  generara uno distinto acá, el bridge dejaría de funcionar). Vos tenés
  que copiar el valor real (`terraform output -raw env_block` lo
  incluye) a este archivo — el archivo está gitignoreado (`secrets/`),
  igual que los demás.
- `terraform/secrets.tf` y `terraform/outputs.tf`: actualicé los 2
  comentarios que decían "nadie valida esto todavía" — ya no es cierto
  para `identity-service`, dejé anotado que los otros 3 Lambdas
  (`fraud-alert`, `health-monitor`, `payment-processor`) siguen sin
  validarlo.

**No corregido, mismo secreto, 3 integraciones más** — recién al leer
`terraform/secrets.tf` completo encontré que este mismo secreto
compartido está pensado para 3 Lambdas más
(`nexus-fraud-alert-lambda`, `nexus-health-monitor-lambda`,
`nexus-payment-processor-lambda`), cada uno llamando a un servicio Plane
A distinto — ninguno de esos 3 lo valida hoy. Fuera del alcance de lo
que pediste esta vez (identity-service específicamente), documentado acá
para que lo tengas en el radar.

**No pude verificar con certeza** (pregunta de infraestructura, no de
código): si el bridge de Lambda hoy efectivamente atraviesa algo que hace
que el gateway vea su IP como parte de `172.20.0.0/16`, o si esa
llamada nunca pasó ese chequeo y por eso el secret importa tanto. De
cualquier forma, con el secret ahora validado, deja de importar cuál de
las dos era la realidad.

### Sexta ronda — las otras 3 integraciones de `PLANE_BRIDGE_SECRET`

Investigué las 3 antes de tocar nada, mismo criterio que con
identity-service — resultado mejor de lo que el comentario de
`terraform/secrets.tf` sugería:

**`nexus-payment-processor-lambda` → `transaction-service` —
ya estaba completo, no era un gap.** `InternalTransactionController.bridgePublish`
ya validaba `X-Plane-Bridge-Secret` con `@Value("${nexus.plane-bridge-secret:}")`
— código real, funcionando, no un comentario aspiracional como los que
venía encontrando. El comentario de `terraform/secrets.tf` que decía
"ninguno de los 4 lo valida" estaba desactualizado para este caso.
**Aplicado**: cambié la comparación de `.equals()` a
`MessageDigest.isEqual` (tiempo constante) para que quede igual de duro
que el fix de identity-service — mismo tipo de credential, misma razón.
De paso, esto reveló que la convención real del proyecto para esta
property es `nexus.plane-bridge-secret` (plana, no anidada) — hasta
ahora usaba `nexus.identity.internal.plane-bridge-secret` en mi fix
anterior de identity-service. **Renombrado** a `nexus.plane-bridge-secret`
en `InternalController.java` y en `nexus-identity-service-prod.yml`, y
agregado el mismo default de dev que ya tenía transaction-service
(`nexus-identity-service-dev.yml`: `plane-bridge-secret: dev-bridge-secret`)
para no bloquear pruebas locales.

**`nexus-health-monitor-lambda` → `/actuator/health` en los 16
servicios — no es un gap real, no lo toqué.** Confirmado en
`service_registry.py`: el `health_path` de los 16 servicios es
`/actuator/health` (`/q/health` para el Quarkus) — el mismo endpoint que
cada `SecurityConfig` de la plataforma ya deja público a propósito, y que
el propio `healthcheck:` de cada servicio en `docker-compose-prod.yml`
golpea sin este header (`wget --spider http://localhost:PORT/actuator/health`).
Exigir el secret acá rompería los healthchecks reales de Docker, que no
lo mandan. El Lambda ya manda el header igual (inofensivo, sencillamente
ignorado hoy) — no hay nada que cerrar sin romper infraestructura real.

**`nexus-fraud-alert-lambda` → "notifica a Plane A" — la integración no
existe en código, nada que validar.** Busqué cualquier llamada HTTP de
este Lambda hacia un servicio Nexus (`internal/v1`, `HttpClient`,
`PLANE_BRIDGE_SECRET`) y no encontré ninguna — es 100% nativo de AWS
(SQS → DynamoDB/SNS/CloudWatch, sin bridge de vuelta). El comentario de
`terraform/secrets.tf` describe algo que nunca se construyó, mismo
patrón que ya había visto con `X-Gateway-Internal` en identity-service y
`@PreAuthorize` en audit-query-jvm — documentación que corrió por
delante del código real.

**Actualicé el comentario de `terraform/secrets.tf`** para reflejar el
estado real: 1 de 4 integraciones (`payment-processor` → transaction-service)
ya estaba validada antes de que yo tocara nada, ahora 2 de 4
(`auth-lambda` → identity-service, sumado la sesión pasada), 1 no aplica
(`health-monitor` → endpoint público a propósito), y 1 no existe todavía
(`fraud-alert`).

### Séptima ronda — nombre de secret desalineado en los 4 `template.yaml`

Pediste revisar específicamente el wiring de `PLANE_BRIDGE_SECRET` en
los templates de los otros 3 Lambdas — encontré un problema real, y de
paso confirmé que también afecta al 4to (`auth-lambda`, no pedido esta
vez pero mismo bug exacto).

**El bug**: los 4 `template.yaml` (SAM) resuelven el secret vía
`{{resolve:secretsmanager:nexus/plane-bridge-secret:...}}`, pero
`terraform/secrets.tf` — la fuente real, lo que efectivamente crea
`terraform apply` en AWS — lo provisiona como
**`nexus-josue/plane-bridge-secret`** (con el sufijo `-josue`). Mismo
nombre incorrecto (`nexus/plane-bridge-secret`, sin el sufijo) repetido
en los 4 `scripts/setup-localstack.sh` (crean el secret mock de
LocalStack con ese nombre) y en 2 lugares de documentación
(`terraform/README.md`, `DEPENDENCIES.md` de auth-lambda) — 9 archivos
en total, un solo error de nombre propagado por copy-paste.

**Impacto real, sin exagerar**: como el deploy real de los 4 Lambdas es
100% vía Terraform (`lambda-*.tf`, que setean `PLANE_BRIDGE_SECRET`
directo con `local.plane_bridge_secret_value`, sin pasar por
`template.yaml` para nada), esto **no afectó ningún secret validado
recién** en esta sesión (identity-service, transaction-service) — esos
usan el valor real de Terraform, correcto. El bug solo se activaría si
alguien corriera `sam deploy` directo contra AWS real usando estos
templates (fallaría al no encontrar el secret `nexus/plane-bridge-secret`,
que no existe) — el `setup-localstack.sh` de cada uno sigue siendo
autoconsistente para pruebas 100% locales (crea y lee el mismo nombre
falso), así que tampoco rompía nada ahí.

**Aplicado**: los 9 archivos corregidos a `nexus-josue/plane-bridge-secret`,
consistente con el nombre real que `terraform/secrets.tf` provisiona.

### Octava ronda — confirmado que `saga-orchestrator`/`audit-query-jvm`/`api-gateway` no necesitan nada de esto

Chequeado explícitamente, no asumido por similitud con la ronda anterior
de `X-Internal-Service`: **ninguno de los 3 tiene código que referencie
`PLANE_BRIDGE_SECRET`/`X-Plane-Bridge-Secret` en absoluto.**

- `saga-orchestrator` y `audit-query-jvm`: el único Lambda que los
  menciona es `health-monitor-lambda`, y solo como 2 entradas más en la
  lista de 16 servicios que chequea por `/actuator/health` — el mismo
  chequeo estándar que ya confirmé no necesita el secret. Ningún otro
  Lambda les llama nada.
- `api-gateway`: la única referencia es la ruta `/internal/v1/bridge/**`
  ya revisada (sexta ronda) — el gateway la proxea a
  `transaction-service` sin tocar el secret, por diseño correcto: quien
  valida es el servicio receptor, no el gateway. Nada más en
  `GatewayRoutesConfig.java` ni en el resto del servicio lo referencia.

Sin cambios — los únicos 2 servicios que necesitaban esto
(`identity-service`, `transaction-service`) ya estaban cerrados.

### Rate limiting

Ya confirmado en `10_ARCHITECTURE_PATTERNS_CHANGES.md`: rate limiter
Redis-backed por ruta en `api-gateway`, límites diferenciados por costo
real. **No verifiqué esta sesión** si las respuestas exponen
`X-RateLimit-Limit`/`X-RateLimit-Remaining`/`Retry-After` en el `429` —
fuera del alcance de esta pasada, queda como pendiente de confirmar en
una auditoría de Fase 10 dedicada.

---

## Resumen ejecutivo

**Aplicado** (código real, sin build ni ejecución):
- `account-service`: cerrado un IDOR real en 5 endpoints
  (`AccountController`, `AccountAdvisorController`,
  `AccountQueryService` — nuevo `verifyOwnership` centralizado).
- `account-service`: `401`/`403` ahora se devuelven correctamente en vez
  de `500` (`GlobalExceptionHandler` — 2 handlers nuevos para
  `UnauthorizedException`/`AccessDeniedException`).
- `ledger-service`: cerrado el mismo IDOR en 4 endpoints + `explain`,
  usando la replicación CDC (`accounts.created` vía Debezium outbox) que
  ya estaba corriendo — sin llamada síncrona nueva a account-service.
  Nuevo `GlobalExceptionHandler` (no existía ninguno).
- `notification-service` + `analytics-service`: sin IDOR (ya scopeaban
  bien por `userId`), pero mismo bug de `401`→`500` que account-service —
  corregido igual, `UnauthorizedException` + `GlobalExceptionHandler`
  nuevos en ambos.
- `fraud-service`: sin IDOR (no tiene endpoints de usuario), pero sus
  endpoints internos destructivos (blacklist de merchant, override de
  revisión de fraude) no verificaban identidad de servicio en absoluto —
  porteado el `InternalServiceAuthFilter` que ya usa account-service.
- `ai-assistant-service`: sin IDOR (diseño ya correcto — `conversationId`
  siempre prefijado por el `userId` del header, sin ID de recurso ajeno
  tomado del cliente). Mismo bug de `401`→`500`, corregido igual.
- `risk-scoring-service`: mismo hueco que fraud-service tenía — cero
  verificación de identidad de servicio en `/internal/v1/risk`, exponiendo
  perfiles de riesgo completos y el trigger del batch de OpenAI a
  cualquiera en la red. Porteado el mismo filtro; allow-list marcada
  explícitamente como no verificada (no encontré un llamador HTTP real).
- `saga-orchestrator`: mismo hueco, mismo fix — exponía progreso de KYC
  de onboarding y detalle de sagas de transferencia sin ningún chequeo.
- `audit-query-jvm`: **el hallazgo más severo de la auditoría** — todo el
  audit trail/timeline de cualquier usuario y la búsqueda de compliance
  en lenguaje natural, sin ninguna verificación, pese a que el propio
  `SecurityConfig` documentaba `@PreAuthorize` por rol que nunca se
  aplicó. Corregido con un chequeo de rol (`COMPLIANCE_OFFICER`/`ADMIN`)
  usando `X-User-Roles`, header que el gateway ya emite y protege contra
  spoofing — solo faltaba usarlo.
- `api-gateway`: auditado — `FeatureFlagAdminController` ya estaba bien
  protegido (chequeo de IP inline, diseño consciente). 2 rutas
  programáticas con paths que no matchean ningún controller real
  (`risk-scoring`, `saga-orchestrator`) — documentado, no tocado.
- `identity-service`: cerrado el gap de `X-Plane-Bridge-Secret` en
  `GET /internal/v1/users/{userId}/kyc/status` (el que llama
  `nexus-auth-lambda` desde fuera de la red Docker antes de emitir
  tokens Cognito). El secret ya estaba 100% aprovisionado por Terraform
  y el lado Lambda ya lo mandaba — solo faltaba validarlo del lado del
  servicio. Comparación de tiempo constante, falla cerrado si no está
  configurado. Mismo secreto compartido con otras 3 integraciones Lambda
  que siguen sin validarlo — fuera de alcance esta vez, documentado.

**Ya estaba bien, confirmado no re-hecho**: RFC 9457 nativo en 3
servicios, idempotencia real en transferencias/pagos, optimistic locking
JPA en 5 entidades, versionado por URL consistente, rate limiting
Redis-backed en el gateway, cero fuga de payload case, cero uso indebido
de `200`/verbos-en-URL/PUT-para-parcial.

**Pendiente, priorizado por severidad real:**
1. **Copiar el valor real del secret a `secrets/plane_bridge_secret.txt`**
   — hoy tiene un placeholder obvio
   (`REPLACE_ME_WITH_TERRAFORM_OUTPUT_PLANE_BRIDGE_SECRET`), así que
   `nexus-auth-lambda` va a recibir `401` de todas sus llamadas a
   `/kyc/status` hasta que pongas ahí el mismo valor que ya tiene el
   Lambda (`terraform output -raw env_block` lo incluye).
2. ~~Las otras 3 integraciones del mismo `PLANE_BRIDGE_SECRET`~~ —
   **ya revisadas, cerrado el tema**: `payment-processor-lambda` ya
   estaba validado (solo lo endurecí a comparación de tiempo constante);
   `health-monitor-lambda` apunta a `/actuator/health`, público a
   propósito en toda la plataforma, no corresponde exigir el secret ahí;
   `fraud-alert-lambda` no tiene ninguna llamada de vuelta a Plane A en
   su código — el comentario de terraform describía algo nunca
   construido. Nada pendiente en este frente.
3. ~~Nombre de secret desalineado en los 4 `template.yaml`~~ —
   **ya corregido**: los 4 SAM templates + 4 `setup-localstack.sh` +
   `terraform/README.md` + `DEPENDENCIES.md` de auth-lambda apuntaban a
   `nexus/plane-bridge-secret`, el real es `nexus-josue/plane-bridge-secret`.
   No afectaba nada corriendo hoy (el deploy real es 100% Terraform, no
   pasa por estos templates), pero rompería un `sam deploy` directo. 9
   archivos alineados.
4. **Confirmar las 3 allow-lists nuevas de `X-Internal-Service`**
   (`fraud-service`, `risk-scoring-service`, `saga-orchestrator`) contra
   la realidad de producción — solo la de fraud-service tiene 1 llamador
   confirmado por código; las otras 2 son 100% plausibles, sin ningún
   llamador HTTP real encontrado.
5. Extender `GlobalExceptionHandler` con RFC 9457 completo (no solo
   `Unauthorized`/`Forbidden`) a `fraud`, `ai-kyc`, `saga-orchestrator`,
   `api-gateway` — los que quedaron sin ninguna versión de esto. Empezar
   por `fraud` (ya tiene el hábito `ProblemDetail` inline, solo falta
   centralizar el resto de sus excepciones de dominio).
6. Arreglar las 2 rutas del gateway con paths que no matchean ningún
   controller real (`risk-scoring`, `saga-orchestrator`) — y el endpoint
   inexistente que llama `FraudServiceClient.getRecentAlertSummaries`.
7. OpenAPI vía `springdoc` — piloto en `account`+`transaction`.
8. Migrar paginación de historial de transacciones a cursor (offset hoy).
9. Aclarar con producto si el `accountId` ignorado en `analytics-service`
   es bug o diseño intencional.
10. Cosméticos de bajo valor, no tocar salvo que se pida explícitamente:
    prefijo interno inconsistente de account-service, verbos-en-path de
    endpoints de auth/admin.

**Todos los servicios con superficie REST propia ya fueron auditados con
este criterio**: `account`, `ledger`, `notification`, `analytics`,
`ai-kyc`, `fraud`, `ai-assistant`, `risk-scoring`, `saga-orchestrator`,
`audit-query-jvm`, `api-gateway`, `identity`, más `transaction` que ya
estaba limpio desde el diagnóstico original.
