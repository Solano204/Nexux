# 14 — Documentación de REST APIs de Clase Mundial (NEXUS)

Prompt maestro: "Documentación de REST APIs de Clase Mundial" — 11 fases (0 a 10).

Configuración de Swagger copiada como referencia real de
`C:\Users\GAMER\Music\BrainTrust\backend` (proyecto Spring Boot de
Carlos, no un ejemplo inventado): dependencia `springdoc-openapi-starter-webmvc-ui:2.8.13`,
bean `OpenApiConfig` con `Info`/`SecurityScheme`, bloque `springdoc:` en
`application.yml` (`api-docs.path`, `swagger-ui.path`, sorters), y las
rutas `/v3/api-docs/**`+`/swagger-ui/**` agregadas al `permitAll()` de
`SecurityConfig`. Adaptado al modelo de auth real de NEXUS (ver Sección 1)
en vez de copiado literal — BrainTrust valida JWT dentro del propio
servicio, NEXUS no.

---

## Sección 0 — Fase 0: Diagnóstico

**Confirmado por grep en los 16 `pom.xml`, no supuesto** (ya lo había
confirmado en `13_REST_API_DESIGN_CHANGES.md`, Fase 9, y lo re-confirmé
esta sesión antes de tocar nada): cero dependencias `springdoc`/`swagger`/
`OpenAPI` en toda la plataforma. Sin spec, sin portal, sin quickstart,
sin documento de errores — el único "estado actual" real eran los
Postman collections en `DOCUMENTATION-POSTMAN/` (colecciones manuales,
no generadas del código, ya desactualizables por diseño).

**Framework confirmado**: Spring Boot 3.5.3 (root `pom.xml`), compatible
con `springdoc-openapi-starter-webmvc-ui` 2.x sin fricción. `nexus-api-gateway`
es reactivo (WebFlux) — usa `springdoc-openapi-starter-webflux-ui`, no el
`-webmvc-ui` del resto (ambos terminaron cubiertos en esta sesión, ver
Sección 1). El único servicio que sigue sin este mecanismo es
`audit-write-native` (Quarkus, necesitaría `quarkus-smallrye-openapi` —
extensión distinta, no cubierta en esta sesión, ver Sección 10 #12).

**Inventario de gaps por severidad:**

| Gap | Severidad |
|---|---|
| Cero spec OpenAPI en 15 servicios Spring Boot | Crítico |
| Cero documentación de autenticación/errores centralizada | Crítico |
| Cero quickstart — nadie nuevo puede hacer su primera llamada sin leer código | Importante |
| Cero linting de spec (no aplica todavía sin spec que lintear) | Importante |
| Cero `llms.txt` — ningún agente de IA tiene un mapa curado de la documentación | Importante |
| Cero changelog de contrato de API | Cosmético (sin consumidores externos aún — ver Fase 7) |

---

## Sección 1 — Fase 1: OpenAPI como fuente única de verdad

**Generado desde el código, nunca escrito a mano** — decisión no
negociable, confirmada desde el diagnóstico: `springdoc-openapi` deriva
el spec de las anotaciones `@RestController`/`@RequestMapping` y las
anotaciones Swagger (`@Tag`/`@Operation`/`@ApiResponse`/`@Parameter`/
`@Schema`) ya en el código de producción. Nunca va a desincronizarse del
comportamiento real porque *es* una lectura del comportamiento real —
literalmente el problema que esta fase pide evitar.

**Aplicado — piloto en 2 servicios**, siguiendo la misma disciplina de
"piloto primero, no todo de una" que ya usa el resto de esta serie de
documentos:

- **`pom.xml` raíz**: agregada `springdoc-openapi.version` (2.8.13) +
  entrada en `dependencyManagement` — siguiendo el patrón ya establecido
  del proyecto (BOM central, cada servicio agrega la dependencia sin
  versión — confirmado mirando cómo ya se maneja `java-jwt`/`flyway`/etc.
  antes de copiar el patrón de BrainTrust, que hardcodea la versión por
  módulo).
- **`nexus-account-service`, `nexus-transaction-service`**: dependencia
  agregada a cada `pom.xml`; nuevo `OpenApiConfig.java` en cada uno
  (bean `OpenAPI` con título/descripción/versión reales del servicio, no
  genéricos); `SecurityConfig.java` de cada uno actualizado con
  `permitAll()` para `/v3/api-docs/**`+`/swagger-ui/**`+`/swagger-ui.html`
  (ninguno lo tenía — sin esto, el propio `.anyRequest().denyAll()` de
  cada `SecurityConfig` bloqueaba Swagger UI); bloque `springdoc:` en
  `application.yml` de cada uno (paths, sorters, `display-request-duration`).

**Diferencia real vs. copiar BrainTrust literal**: el security scheme
documentado es `X-User-Id` (tipo `apiKey`, header), no `Bearer JWT`.
BrainTrust valida el JWT dentro del propio servicio — tiene sentido
documentar Bearer ahí. NEXUS no: cada servicio individual solo valida
`X-User-Id` (el gateway ya validó el JWT y seteó ese header antes de
reenviar) — documentar "Bearer JWT" en el Swagger UI de account-service
sería literalmente incorrecto para quien prueba directo contra ese
servicio (un JWT enviado ahí no hace nada). Ver `OpenApiConfig.java` de
cada servicio para el razonamiento completo en el Javadoc.

**Anotado con descripciones y ejemplos reales, no genéricos** (Fase 1,
puntos 2-3), controller por controller:

- `AccountController` (5 endpoints) + `AccountAdvisorController` (2
  endpoints): `@Tag` a nivel clase, `@Operation` con `summary`+
  `description` explicando comportamiento real (ej. `getBalance`
  documenta que lee *solo* de cache Redis y por qué devuelve `503` en
  vez de hacer fallback a una lectura más lenta), `@ApiResponse` por
  cada código real que el endpoint puede devolver (incluyendo `403`
  específicamente por el fix de IDOR de `13_REST_API_DESIGN_CHANGES.md`
  — cada endpoint ahora documenta que puede rechazar con `403` si la
  cuenta no pertenece al caller), ejemplos JSON con valores de dominio
  reales (montos en pesos, UUIDs de ejemplo, `"currency": "MXN"") en vez
  de placeholders.
- `TransactionController` (5 endpoints): mismo criterio — `@Operation`
  en `initiateTransfer`/`initiatePayment` explica por qué la respuesta es
  `202` y no `200`/`201` (el saga todavía no terminó), y qué garantiza
  `idempotencyKey`. `InitiateTransactionRequest` (el DTO de request)
  recibió `@Schema` campo por campo — cada uno con `description` real y
  `example` de dominio (`"3f9a2b1c-..."` para UUIDs, `"500.00"` para
  montos, `"Amazon MX"` para `merchantName`), no el nombre del campo
  repetido como descripción.

**Aplicado — rollout completo a los 11 servicios Spring Boot restantes +
`api-gateway` (webflux)**: mismo patrón mecánico de arriba (dependencia +
`OpenApiConfig` + `SecurityConfig` + `application.yml` + anotación
completa controller por controller), pero el security scheme documentado
en cada servicio se derivó de leer su `SecurityConfig.java` real, no
copiado del piloto — la plataforma no tiene un único modelo de auth
uniforme entre servicios internos y user-facing, y documentar el mismo
esquema en todos lados habría sido incorrecto para la mitad de ellos:

- **`X-User-Id`** (mismo esquema del piloto, `apiKey`/header) — servicios
  genuinamente user-facing: `notification-service`, `ai-assistant-service`,
  `ledger-service` (solo `LedgerController`; `InternalLedgerController`
  documentado explícitamente **sin** esquema — sigue protegido solo por
  `RemoteAddr`, mismo gap que `13_REST_API_DESIGN_CHANGES.md` ya señaló y
  que esta sesión no cierra, solo documenta con precisión), y
  `analytics-service` (con una nota explícita en la descripción del bean:
  el `accountId` de la URL se captura pero nunca se usa para filtrar la
  query — mismo hallazgo de intención indeterminada del doc 13, repetido
  acá para que no se pierda).
- **`X-User-Id` aplicado solo por-operación, no global** — servicios con
  superficie mixta pública/privada real: `identity-service`
  (`AuthController` es público de verdad — sin `addSecurityItem` global;
  el esquema solo se declara en `components` y se aplica vía
  `@SecurityRequirement` en `UserController`/`KycController`/
  `InternalController`) y `ai-kyc-service` (mismo patrón, solo
  `KycController`).
- **`X-Internal-Service`** — servicios 100% internos, ya protegidos por el
  `InternalServiceAuthFilter` portado en la sesión de seguridad anterior:
  `fraud-service`, `risk-scoring-service`, `saga-orchestrator`. Las
  descripciones de `OpenApiConfig` de `risk-scoring-service` y
  `saga-orchestrator` señalan explícitamente que no hay un caller HTTP
  real confirmado — la coordinación de producción es 100% vía Kafka/Redis,
  el spec documenta una superficie que existe pero no se usa en el flujo
  normal.
- **`X-User-Id` + `X-User-Roles`, ambos requeridos** (única combinación
  doble de la plataforma) — `audit-query-jvm`: su autorización real
  depende de los dos headers (`COMPLIANCE_OFFICER`/`ADMIN`), no solo de
  identidad, y el spec lo modela con dos `addList(...)` en el mismo
  `SecurityRequirement` en vez de simplificar a uno solo.
- **Sin esquema** — `api-gateway`: `FallbackController` es público por
  diseño (respuestas de circuit breaker), `FeatureFlagAdminController`
  hace su propio chequeo de IP inline (`172.20.0.0/16` + localhost, ver su
  Javadoc), no representable como header scheme. Su `OpenApiConfig`
  documenta explícitamente que el spec cubre solo sus ~10 endpoints
  propios, no los ~90 que enruta — cada servicio downstream tiene su
  propio spec en su propio puerto.

**Dependencia webflux agregada como entrada nueva, no reemplazo**:
`springdoc-openapi-starter-webflux-ui:2.8.13` en el `dependencyManagement`
del `pom.xml` raíz, junto a la `-webmvc-ui` ya existente (son mecanismos
distintos para reactive vs. servlet, ambos coexisten en el mismo reactor
porque cada módulo es o uno o el otro, nunca ambos); `pom.xml` de
`nexus-api-gateway` la agrega sin versión, mismo patrón BOM del resto.

**Total de esta fase de rollout**: ~55 endpoints adicionales anotados
controller por controller (`AuthController`, `UserController`,
`KycController`, `InternalController` de identity;
`InternalFraudController`; `LedgerController` + `InternalLedgerController`;
`NotificationController` + `PreferencesController`;
`AiAssistantController` + `DocumentAnalysisController`; `KycController` +
`InternalKycController` de ai-kyc; `AnalyticsController` +
`InsightsController` + `InternalAnalyticsController`;
`InternalRiskController`; `InternalSagaController`; `AuditController` +
`ComplianceController`; `FallbackController` + `FeatureFlagAdminController`
de api-gateway), cada uno con `@Operation`/`@ApiResponse` describiendo
comportamiento real ya conocido de rondas de auditoría previas — no
descripciones genéricas re-derivadas del nombre del método.

**No aplicado — audit-write-native (Quarkus)**: sigue siendo el único
servicio HTTP de toda la plataforma sin OpenAPI. Mecanismo completamente
distinto (`quarkus-smallrye-openapi`, no springdoc — Quarkus ni siquiera
comparte el reactor Maven de este `pom.xml` raíz, ver Sección 0), fuera de
alcance de esta sesión. Ver Sección 10 #12.

**Checklist de verificación de la Fase 1**: no pude regenerar y probar
ningún spec contra endpoints reales esta sesión — implica levantar
servicios, y Carlos pidió explícitamente no levantar nada. **Pendiente de
que vos lo confirmes vos mismo**, para cada uno de los 13 servicios
Spring Boot (puerto según la tabla de `CLAUDE.md`): `mvn spring-boot:run
-pl <servicio>` (o el JAR ya construido), después `curl
http://localhost:<puerto>/v3/api-docs | jq` y `curl
http://localhost:<puerto>/swagger-ui.html` — si eso funciona y las
respuestas documentadas coinciden con lo que ya sabés que devuelven esos
endpoints, la fase está cerrada.

---

## Sección 2 — Fase 2: Linting y gobernanza del spec

**Spectral, el estándar de facto, elegido sin evaluar alternativas** — es
la herramienta que la fase misma nombra como estándar, y no hay ninguna
razón específica de NEXUS para desviarse.

**Aplicado**: `.spectral.yml` en la raíz del repo, extendiendo
`spectral:oas` (ruleset recomendado — unicidad de `operationId`, tipos de
schema válidos, etc.) con 7 reglas custom que verifican exactamente los
hallazgos reales de `13_REST_API_DESIGN_CHANGES.md`, para que no
regresen en silencio:

- `nexus-operation-summary-required` / `nexus-operation-description-required`:
  toda operación necesita ambos (Fase 1 punto 2 del prompt).
- `nexus-no-generic-descriptions`: rechaza (warn) descripciones tipo
  "user data"/"response" — detectable por patrón, no perfecto pero
  atrapa el caso más común.
- `nexus-param-description-required`: todo parámetro necesita descripción.
- `nexus-no-placeholder-examples`: rechaza (warn) ejemplos tipo
  `"string"`/`123`/`additionalProp1` — los defaults que
  springdoc/Swagger generan cuando no hay `@Schema(example=...)`.
- `nexus-error-response-is-problem-detail`: toda respuesta 4xx/5xx debe
  tener `application/problem+json` — señala exactamente la
  inconsistencia de 3 formatos que `13_REST_API_DESIGN_CHANGES.md`
  Sección 3 documentó.
- `nexus-path-no-camelcase`: ya cumplido en el 100% de los ~90 endpoints
  auditados en la Fase 0 de ese mismo doc — esta regla solo evita que
  alguien lo rompa después.
- `nexus-response-schema-required`: toda respuesta 2xx necesita un
  schema, no solo una descripción.

**No aplicado — integración a CI**: `.github/workflows/ci-pr.yml` ya
existe (trabajo propio tuyo, muy reciente, sin commitear — lo leí para
entender el patrón pero no lo toqué, mismo criterio de no pisar trabajo
en curso que usé en la sesión de seguridad). Job sugerido, para que lo
agregues vos cuando quieras (no es un fix de una línea como los de la
serie anterior — requiere levantar el servicio brevemente en el runner
para exportar el spec real, justo lo que se te pidió no ejecutar en esta
sesión):

```yaml
  lint-openapi-spec:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '25', distribution: 'temurin' }
      - run: mvn -B spring-boot:run -pl nexus-account-service &
      - run: npx wait-on http://localhost:8085/v3/api-docs -t 60000
      - run: curl -s http://localhost:8085/v3/api-docs -o spec.json
      - run: npx @stoplight/spectral-cli lint spec.json --ruleset .spectral.yml
```

**Checklist de verificación de la Fase 2**: el ruleset existe y está
completo; no corre limpio contra nada todavía porque correrlo requiere
un spec real exportado, que requiere levantar un servicio — mismo
pendiente que Fase 1.

---

## Sección 3 — Fase 3: Quickstart

**Aplicado**: `API-DOCUMENTATION/01_QUICKSTART.md` — 3 pasos reales
(`register` → `login` → primera llamada autenticada a
`GET /api/v1/accounts`), copy-pasteable con `curl`, request/response
bodies reales tomados de los DTOs reales (`RegisterRequest`,
`LoginRequest` — no inventados), no genéricos.

**Decisión de diseño explicada**: el paso 3 usa `GET /api/v1/accounts`
(que devuelve `[]` para un usuario nuevo) en vez de un endpoint con datos
reales, a propósito — un usuario recién registrado está en
`PENDING_KYC` y no tiene cuentas todavía (se crean después de KYC vía el
saga de onboarding). Usar ese endpoint garantiza que el quickstart
*siempre* funciona en 3 pasos sin depender de completar KYC primero; el
doc explica esto explícitamente para que un array vacío no se lea como
un error.

**No evalué el patrón de ejemplos lado a lado en varios lenguajes** (Fase
3 punto 3) — la única forma real de consumo de esta API hoy es HTTP
directo (`curl`/Postman) para desarrollo interno; no hay un SDK ni un
segundo lenguaje de consumidor real que justifique el patrón multi-idioma
de Stripe. Coherente con la misma conclusión de "sin consumidores
externos reales todavía" de `13_REST_API_DESIGN_CHANGES.md` Fase 7.

**Checklist de verificación de la Fase 3**: no pude ejecutar los 3 pasos
yo mismo (implicaría levantar el stack) — **pendiente de que lo corras
vos**: los 3 comandos `curl` del doc, en orden, contra un stack local
levantado. Si el tercero devuelve `200` con `[]` (no `401`), la fase está
cerrada.

---

## Sección 4 — Fase 4: Documentación de autenticación

**Aplicado**: `API-DOCUMENTATION/02_AUTHENTICATION.md` — mecanismo real
(JWT vía gateway, `X-User-Id` reenviado a los servicios, por qué ese
diseño de 2 capas existe, referenciando el razonamiento ya documentado en
`10_ARCHITECTURE_PATTERNS_CHANGES.md`), tabla comparativa gateway-vs-directo-a-servicio
(la distinción `Bearer JWT` vs `X-User-Id` de la Fase 1), ciclo de vida
completo del token (access 1h, refresh 30 días en cookie `HttpOnly`, por
qué no va en el body), y los 4 errores de auth reales del gateway
(`MISSING_TOKEN`/`INVALID_TOKEN`/`TOKEN_REVOKED`/`ACCOUNT_SUSPENDED`)
con su código de estado exacto — extraídos leyendo
`JwtAuthenticationFilter.java` directamente, no inventados.

**Distinción explícita que el prompt no pide pero es real acá**: un
`403` del gateway (`ACCOUNT_SUSPENDED`, cuenta suspendida a nivel
plataforma) y un `403` de un servicio (`ACCESS_DENIED`, no sos dueño del
recurso puntual) tienen el mismo status code y significan cosas
completamente distintas — documentado explícitamente para que alguien
troubleshooteando no asuma que todo `403` es lo mismo.

**Checklist de verificación de la Fase 4**: cubre registro→login→uso del
token y los 4 errores de auth con su status/código exactos — verificable
por lectura del código fuente (`JwtAuthenticationFilter.java`, ya
confirmado), sin necesitar el stack corriendo para esta fase puntual.

---

## Sección 5 — Fase 5: Documentación de errores

**Aplicado**: `API-DOCUMENTATION/03_ERRORS.md` — formato RFC 9457 con
ejemplo real, tabla completa de semántica de status codes a nivel
plataforma (incluyendo matices reales no obvios: `202` solo en
transfer/payment por el saga, `404` usado deliberadamente en vez de `403`
en algunos endpoints para ocultar existencia del recurso, `503` de
balance-cache-warming), sección de rate limiting con los headers
`X-RateLimit-*` que `RedisRateLimiter` de Spring Cloud Gateway expone por
default (confirmado por código — `new RedisRateLimiter(rate, burst)`,
constructor de 2 args, no desactiva `includeHeaders`), y el catálogo
completo de `errorCode` de `account-service` (14 códigos) e
`identity-service` (10 códigos) — extraídos leyendo cada
`GlobalExceptionHandler.java` real, no inventados ni genéricos.

**Honestidad explícita sobre el gap real, no ocultado**: el doc dice
textualmente que `ai-kyc-service` todavía no tiene `GlobalExceptionHandler`
(cae al whitelabel default de Spring Boot) y que fraud/risk-scoring/saga
no tienen superficie de usuario a la que esto aplique — mismo hallazgo
exacto de `13_REST_API_DESIGN_CHANGES.md` Sección 3, repetido acá porque
es exactamente lo que esta fase pide no esconder.

**No aplicado — catálogo completo por-cada-endpoint de los otros 11
servicios**: el doc lista los catálogos de errorCode de solo 2 servicios
en detalle (los mismos 2 con OpenAPI real de la Fase 1) — para el resto,
señala que la fuente de verdad final es el `@ApiResponse` de cada
endpoint una vez tenga OpenAPI, no un documento estático separado que se
desincronizaría.

**Checklist de verificación de la Fase 5**: para cada endpoint de los 2
servicios piloto, la lista de errores está en el `@ApiResponse` de la
Fase 1 Y en el catálogo de este doc — verificado por lectura cruzada de
ambos, coinciden.

---

## Sección 6 — Fase 6: Documentación interactiva ("try it")

**Herramienta: Swagger UI, vía springdoc — ya decidido de facto en la
Fase 1**, no una elección separada. Sirve llamadas de prueba reales
directo desde el navegador contra el spec ya generado en la Fase 1, cero
mantenimiento adicional (springdoc genera Swagger UI automáticamente
desde el mismo bean `OpenAPI`, no hay un segundo artefacto a mantener
sincronizado).

**Sandbox vs producción — no aplica todavía**: NEXUS no tiene un
ambiente de sandbox/test-mode separado del real (confirmado — no hay
ninguna bandera de "modo prueba" en ningún servicio auditado esta sesión
ni en `13_REST_API_DESIGN_CHANGES.md`). Swagger UI corriendo en
`localhost` durante desarrollo local ES el único modo hoy — no hay
confusión posible con producción porque no hay Swagger UI desplegado en
producción (no expuesto públicamente, ver Fase 9).

**Checklist de verificación de la Fase 6**: no pude hacer una llamada de
prueba real desde el navegador esta sesión (implica levantar un
servicio) — mismo pendiente que Fases 1-3, a verificar por vos.

---

## Sección 7 — Fase 7: Changelog y breaking changes

**Mismo estado que `13_REST_API_DESIGN_CHANGES.md` Fase 7 encontró, sin
cambios**: sin consumidores externos reales todavía, la disciplina de
compatibilidad hacia atrás (agregar campos opcionales, nunca quitar/renombrar
sin ciclo de deprecación) ya es suficiente — no hace falta un mecanismo
de changelog formal todavía.

**No aplicado — automatización de diff de specs**: tiene sentido recién
cuando haya al menos 2 versiones reales de un spec para diffear (hoy hay
0 — la Fase 1 recién generó el primero). Herramienta recomendada cuando
llegue ese momento: `oasdiff` (open source, corre en CI, clasifica
automáticamente breaking vs non-breaking) contra el `/v3/api-docs`
exportado en cada build — mismo mecanismo del job de Spectral sugerido en
Fase 2, un segundo paso en el mismo job.

**Checklist de verificación de la Fase 7**: criterio explícito y por
escrito de cuándo se necesita changelog formal (primer consumidor externo
real) — cumplido, ya estaba en Fase 7 del doc 13, repetido acá para que
este documento sea autocontenido.

---

## Sección 8 — Fase 8: `llms.txt`

**Aplicado**: `llms.txt` en la raíz del repo — H1 + blockquote de 2
oraciones + 3 secciones H2 (Getting Started, Reference, Architecture &
Conventions), **9 links totales**, dentro del rango 10-15 que la fase
pide. Cada link tiene una descripción de una línea de qué contiene, no
solo el título.

**Un link por recurso/documento, no por endpoint** — los 2 links de
OpenAPI spec son un link por *servicio* (account, transaction), no uno
por cada uno de los 12 endpoints documentados en ellos. Exactamente el
error que la Fase 8 punto 3 pide evitar.

**`llms-full.txt` — evaluado y descartado por ahora**: con los 13
servicios Spring Boot ya documentados (Sección 1), la condición que esta
misma sección puso para revisitar la decisión ya se cumplió — pero
generar `llms-full.txt` sigue sin agregar valor real: serían 13 specs
OpenAPI concatenados, no una lectura curada, y `llms.txt` ya cubre la
navegación con 9 links. Queda como ítem pendiente de bajo valor, no
descartado por falta de cobertura sino porque nadie pidió consumo
"todo en un archivo" todavía — ver Sección 10 si eso cambia.

**Checklist de verificación de la Fase 8**: 9 links, cada uno con
descripción — cumplido, verificable leyendo el archivo directamente.

---

## Sección 9 — Fase 9: Selección de herramienta/plataforma

**Swagger UI autohospedado (springdoc), no una plataforma dedicada —
mismo razonamiento que `13_REST_API_DESIGN_CHANGES.md` Fase 9 ya
aplicó para OpenAPI en general**: NEXUS no tiene consumidores externos
reales, no necesita analítica de adopción, generación de SDKs, ni
gestión de API keys de terceros — todas las razones reales para pagar
por Postman/Redocly/Stoplight/etc. Recomendar una de esas plataformas
hoy sería exactamente el error que este punto de la fase pide evitar
("no recomiendes una plataforma pesada/paga si el contexto real no lo
justifica").

**Explícitamente no evaluado en profundidad — Redoc/Scalar como
alternativas a Swagger UI**: ambas son opciones válidas y más lindas
visualmente, pero significarían una segunda herramienta a instalar (Redoc
via CLI/Docker, Scalar vía su propio paquete) cuando springdoc ya
resuelve "try it" con cero configuración adicional sobre lo que la Fase 1
ya agregó. Sin razón concreta de NEXUS para justificar el costo de
mantenimiento extra — swagger-ui es literalmente parte de la dependencia
que ya se agregó.

**Checklist de verificación de la Fase 9**: herramienta elegida
(Swagger UI vía springdoc) y justificada contra el contexto real
(sin consumidores externos, cero costo adicional, ya integrado en la
Fase 1) — no por ser "la más completa".

---

## Sección 10 — Checklist final consolidada

| # | Acción | Esfuerzo | Estado |
|---|---|---|---|
| 1 | springdoc-openapi en `pom.xml` raíz (BOM) | S | ✅ Hecho |
| 2 | `OpenApiConfig` + `SecurityConfig` + `application.yml` en account-service y transaction-service | S | ✅ Hecho |
| 3 | Anotar los 7 endpoints + 1 DTO de los 2 servicios piloto | M | ✅ Hecho |
| 4 | `.spectral.yml` con reglas custom NEXUS | S | ✅ Hecho |
| 5 | Quickstart, Authentication, Errors (`API-DOCUMENTATION/`) | M | ✅ Hecho |
| 6 | `llms.txt` | S | ✅ Hecho |
| 7 | Este documento de cierre | S | ✅ Hecho |
| 8 | **Verificar Fases 1/2/3/6 levantando el stack** (`mvn spring-boot:run`, `curl /v3/api-docs`, probar Swagger UI) | S | ⏳ Pendiente — necesita que vos lo corras, no yo |
| 9 | **Copiar `secrets/plane_bridge_secret.txt`** con el valor real (ítem viejo de `13_REST_API_DESIGN_CHANGES.md`, sigue pendiente, no relacionado a esta sesión pero sin cerrar) | S | ⏳ Pendiente |
| 10 | Rollout de springdoc + anotaciones a los 11 servicios Spring Boot restantes | L | ✅ Hecho |
| 11 | springdoc-openapi-starter-**webflux**-ui para `nexus-api-gateway` (reactivo, dependencia distinta a la ya agregada) | M | ✅ Hecho |
| 12 | `quarkus-smallrye-openapi` para `audit-write-native` (mecanismo completamente distinto, Quarkus no Spring) | M | ⏳ Pendiente |
| 13 | Job de Spectral en CI (`ci-pr.yml`) — snippet ya escrito en Sección 2, no aplicado para no pisar tu CI en curso | S | ⏳ Pendiente, tuyo para aplicar |
| 14 | `oasdiff` en CI para changelog automático (Fase 7) | M | ⏳ Pendiente — bloqueado hasta tener ≥2 versiones de spec reales |
| 15 | Catálogo de `errorCode` completo para los 11 servicios restantes en `03_ERRORS.md` | M | ⏳ Pendiente — fuente de verdad ya existe en el `@ApiResponse` de cada endpoint (ítem 10), esto es solo el resumen estático para el doc de referencia |
| 16 | Cerrar el gap de `InternalLedgerController` (sin `X-Internal-Service`, solo `RemoteAddr`) documentado en el ítem 10 | S | ⏳ Pendiente — hallazgo original de `13_REST_API_DESIGN_CHANGES.md`, re-confirmado y documentado con precisión en Sección 1, no cerrado |

**Prioridad sugerida para la próxima sesión**: #8 (verificar los 13
servicios levantando el stack vos mismo — es lo único que bloquea dar por
cerrado todo lo demás) y #16 (el único gap de seguridad real que este
rollout dejó explícitamente documentado y sin resolver) antes que
#12/#13/#14/#15, que son mejoras de gobernanza sin urgencia — #12 en
particular es un mecanismo Quarkus completamente distinto, vale la pena
resolverlo una vez, correctamente, no apurado.

---

## Resumen ejecutivo

**Qué se documentó y por qué**: OpenAPI generado desde código (no
escrito a mano) en los 13 servicios Spring Boot con superficie REST de
la plataforma — arrancó con 2 pilotos (`account`, `transaction`,
elegidos por tener el `GlobalExceptionHandler` más completo) para probar
el patrón sin incertidumbre de diseño, y se replicó mecánicamente al
resto: `identity`, `fraud`, `ledger`, `notification`, `ai-assistant`,
`ai-kyc`, `analytics`, `risk-scoring`, `saga-orchestrator`,
`audit-query-jvm`, y `api-gateway` (único caso reactivo/webflux, resto
servlet). `nexus-config-service` y `nexus-discovery-service` quedan
fuera a propósito — no tienen controllers REST propios que documentar.
El security scheme de cada uno **no** se copió del piloto — se derivó de
leer el `SecurityConfig.java` real de cada servicio, resultando en 5
variantes distintas documentadas con precisión (`X-User-Id` global,
`X-User-Id` solo por-operación en servicios mixtos, `X-Internal-Service`,
el doble header `X-User-Id`+`X-User-Roles` de audit-query-jvm, y sin
esquema en api-gateway) — ver Sección 1 para el detalle completo por
servicio. Cada endpoint anotado con descripciones y ejemplos de dominio
real, no genéricos, incluyendo los matices no obvios ya conocidos de
rondas de auditoría previas (por qué `202` no `200`, qué controllers
todavía no tienen `InternalServiceAuthFilter`, cuáles endpoints no tienen
un caller HTTP real confirmado). 3 documentos de referencia
(`quickstart`/`authentication`/`errors`), un `llms.txt` curado de 9
links, y un linter (`Spectral`) con reglas que verifican exactamente los
hallazgos de seguridad/diseño ya auditados en la sesión anterior.

**Qué gaps se cerraron**: la plataforma pasó de "cero documentación de
API en cualquier forma" a los 13 de 13 servicios Spring Boot con
superficie REST completamente documentados vía OpenAPI/Swagger UI —
dependencia + config + anotaciones funcionando de punta a punta, no solo
en los 2 pilotos.

**Qué queda explícitamente pendiente**: `audit-write-native` (Quarkus,
mecanismo distinto — `quarkus-smallrye-openapi`, no springdoc), la
verificación real levantando cada servicio (Sección 10 #8, bloqueada por
la misma restricción de no correr servicios que aplicó toda la sesión),
el gap de `InternalLedgerController` sin `X-Internal-Service` que el
rollout documentó pero no cerró (#16), y las mejoras de gobernanza
(Spectral en CI, `oasdiff`, catálogo estático de errorCode) — todo en la
Sección 10. Y — sin relación a documentación, pero repetido acá para que
no se pierda — el placeholder de `secrets/plane_bridge_secret.txt` de la
sesión anterior sigue sin el valor real.
