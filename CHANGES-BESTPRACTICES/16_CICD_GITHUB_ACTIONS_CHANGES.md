# 16 — CI/CD con GitHub Actions (NEXUS)

Prompt maestro: "CI/CD con GitHub Actions (uso general)" — 9 fases (0 a 8),
más Fase 9 de cierre. A diferencia de los docs 13-15, acá no arranqué de
cero: ya existían 6 workflows escritos en una sesión anterior (todavía sin
commitear) — el trabajo real fue auditarlos contra este prompt, no
inventarlos.

**Actualización del mismo día (2026-07-18, segunda ronda)**: después del
hardening inicial (Secciones 0-9 originales, abajo, sin editar para no
perder el rastro de qué se decidió y por qué), Carlos pidió explícitamente
un split adicional — un archivo de workflow por servicio en vez del
matrix consolidado en `ci-pr.yml`, más workflows nuevos por tipo de
evento (rama nueva, metadata de PR). Ver **Sección 10** al final para el
detalle completo de ese segundo pase — no repetido acá arriba para no
duplicar contenido ya escrito.

---

## Sección 0 — Fase 0: Diagnóstico

**Stack confirmado** (no supuesto): Java 25, Spring Boot 3.5.3, Maven
multi-módulo (15 servicios Spring Boot en el reactor raíz) + 1 servicio
Quarkus nativo (`audit-write-native`, Java 21, **fuera** del reactor —
confirmado por el comentario del propio `pom.xml` raíz, tiene su propio
Dockerfile en `src/main/docker/`) + Lambdas Java/Python en `LAMBDA/`
desplegadas por separado vía Terraform/SAM. Testing: JUnit 5 + Testcontainers
+ WireMock + `spring-cloud-starter-contract-verifier` + JaCoCo, todo ya en
el `pom.xml` raíz. Repositorio: monorepo único (`nexus-platform-config`
en GitHub).

**CI/CD parcial ya existente, auditado en vez de creado de cero**: 6
workflows (`ci-pr.yml`, `ci-develop.yml`, `cd-staging.yml`, `release.yml`,
`terraform-plan.yml`, `terraform-apply.yml`), escritos con buen criterio
de diseño (path-filtering por servicio, tiering CI progresivo, flags
explícitos de lo que es placeholder vs real) pero sin ninguno de los
fundamentos de seguridad de la Fase 2 — ver Sección 2.

**Alcance real confirmado con Carlos (2026-07-18), no asumido**:
- **Sin servidor remoto todavía** — `docker-compose-prod.yml` corre
  local/manual. Alcance de esta sesión: CI real y sólido (build+test+
  seguridad) que garantice que el código funciona antes de mergear. CD a
  un destino remoto queda explícitamente fuera de esta sesión, no
  inventado.
- **Nada configurado en GitHub/AWS todavía**: ni `TERRAFORM_CI_ROLE_ARN`,
  ni el Environment `production`, ni credenciales de ningún registro de
  contenedores.
- **ECR — evaluándose, no decidido**: Carlos puede crear un repositorio
  ECR si hace falta, pero pidió ver primero qué necesitaría (rol IAM,
  permisos, secrets de GitHub) antes de decidir — ver Sección 7.

---

## Sección 1 — Fase 1: Estructura de workflows

**Separación por responsabilidad — ya correcta, sin cambios
estructurales**: `ci-pr.yml` (todo push, build+test rápido),
`ci-develop.yml` (PR a develop, pirámide completa), `cd-staging.yml`
(push a develop, deploy a staging), `release.yml` (PR/push a main, tag +
release), `terraform-plan.yml`/`terraform-apply.yml` (separados de los
anteriores, dominio distinto). Un workflow, un propósito — no hay
monolito que hacer todo.

**Triggers auditados uno por uno — apropiados, sin ampliar
innecesariamente**: `ci-pr.yml` corre en cualquier push menos a `main`
(no en cada PR *y* cada push — evita duplicar ejecución), los demás están
acotados a su branch/path real. Agregué `workflow_dispatch: {}` a
`ci-pr.yml` y `terraform-plan.yml` — re-ejecutar sin necesitar un commit
nuevo, sin ampliar cuándo corren automáticamente.

**`pull_request_target` — confirmado, no se usa en ningún workflow**
(Fase 1 punto 3). Los 6 workflows usan `pull_request` o `push` sin
excepción — cero riesgo del vector de "PR de un fork corriendo con
secrets del repo base".

**Hallazgo real, no relacionado a seguridad**: `no existe la branch
`develop`` (confirmado con `git branch -a`, documentado también en
`CONTRIBUTING.md`) — `ci-develop.yml` y `cd-staging.yml` no pueden
dispararse todavía. Se endurecieron igual (Fase 1-4) para que estén listos
el día que `develop` exista, no para que junten óxido.

---

## Sección 2 — Fase 2: Seguridad fundamental

**Permisos explícitos mínimos — agregado a los 4 workflows que no lo
tenían**: `ci-pr.yml`, `ci-develop.yml`, `cd-staging.yml`, `release.yml`
no declaraban `permissions:` en absoluto — corrían con lo que sea que el
default del repo/org tenga configurado, invisible desde el archivo mismo.
Ahora: `contents: read` a nivel workflow como baseline, y cada job eleva
solo lo que genuinamente necesita — `pr-title-lint` en `ci-develop.yml`
usa `pull_request: read` (no necesita escribir nada), `tag-and-release` en
`release.yml` usa `contents: write` (crea el Release/tag, es el único job
de los 4 workflows que realmente necesita escribir). `terraform-plan.yml`
y `terraform-apply.yml` ya tenían esto bien desde antes — sin cambios ahí
más que el resto de esta fase.

**Cada action de terceros pineada por SHA completo, tabla completa
(resuelto contra la API real de GitHub, no inventado)**:

| Action | Tag usado | SHA pineado |
|---|---|---|
| `actions/checkout` | v4 | `34e114876b0b11c390a56381ad16ebd13914f8d5` |
| `actions/setup-java` | v4 | `c1e323688fd81a25caa38c78aa6df2d33d3e20d9` |
| `dorny/paths-filter` | v3 | `d1c1ffe0248fe513906c8e24db8ea791d46f8590` |
| `actions/upload-artifact` | v4 | `ea165f8d65b6e75b540449e92b4886f43607fa02` |
| `actions/github-script` | v7 | `f28e40c7f34bde8b3046d885e986cb6290c5673b` |
| `amannn/action-semantic-pull-request` | v5 | `e32d7e603df1aa1ba07e981f2a23455dee596825` |
| `madrapps/jacoco-report` | v1.8.0 | `e51ce1f46f7f8b5331593f935e59cbaf44b84920` |
| `aquasecurity/trivy-action` | v0.36.0 | `ed142fd0673e97e23eac54620cfb913e5ce36c25` |
| `mikepenz/release-changelog-builder-action` | v4 | `32e3c96f29a6532607f638797455e9e98cfc703d` |
| `hashicorp/setup-terraform` | v3 | `b9cd54a3c349d3f38e8881555d616ced269862dd` |
| `aws-actions/configure-aws-credentials` | v4 | `7474bc4690e29a8392af63c5b98e7449536d5c3a` |

El riesgo real que esto cierra: si cualquiera de estas actions es
comprometida y el mantenedor mueve el tag `v4`/`v3`/etc. a un commit
malicioso, un workflow que referencia `@v4` empieza a correr ese código
comprometido en el próximo run, con los permisos que le diste, sin que
vos lo hayas aprobado — es el vector de supply-chain más documentado de
GitHub Actions. Con el SHA fijo, el código que corre es exactamente el
que auditaste, para siempre, hasta que decidas vos mismo actualizar el
pin.

**Dos bugs reales encontrados al resolver los SHA, no solo hardening**:
1. `jacoco/[email protected]` en `ci-develop.yml` — no existe ningún
   repositorio `jacoco/report-action` en GitHub. Los inputs que el archivo
   ya pasaba (`paths`, `min-coverage-overall`, `min-coverage-changed-files`)
   coinciden exactamente con el schema real de `madrapps/jacoco-report` —
   quien escribió el workflow tenía la action correcta en mente, la
   referencia estaba mal. Reemplazado por la real, agregado el `token`
   que esa action necesita para comentar en el PR (faltaba).
2. `aquasecurity/trivy-action@0.24.0` — no existe ese tag (los tags reales
   van con prefijo `v`, y `0.24.0`/`v0.24.0` ya no está entre los
   disponibles). Actualizado a `v0.36.0`, la última estable al momento de
   esta sesión.

Sin este paso de resolver contra la API real, los dos hubieran fallado en
el primer run con "action not found" — un fallo confuso de diagnosticar
sin saber que la referencia nunca fue válida.

**OIDC — ya en uso donde corresponde, no agregado de nuevo**:
`terraform-plan.yml`/`terraform-apply.yml` ya usaban
`aws-actions/configure-aws-credentials` con `role-to-assume` (OIDC), no
credenciales estáticas — correcto desde que se escribieron. Ningún otro
workflow de los 6 toca AWS todavía, así que no hay otro lugar donde
aplicar esto hoy.

**Secrets — auditado, sin exposición encontrada**: `cd-staging.yml`'s
`docker login` ya usaba `echo "$PASSWORD" | docker login --password-stdin`
(la forma segura — password nunca aparece como argumento de línea de
comandos visible en el log) desde antes, sin cambios necesarios ahí.
Cambié `NEXUS_PUSHGATEWAY_URL` de `secrets.` a `vars.` en `ci-pr.yml` — es
una URL, no una credencial, no necesita vivir en Secrets; y agregué un
guard (`vars.NEXUS_PUSHGATEWAY_URL != ''`) para que el paso ni se
intente correr contra `localhost` en cada ejecución mientras esa variable
no exista — antes fallaba silenciosamente cada vez (`|| true` lo ocultaba).

**`timeout-minutes` explícito en los 22 jobs de los 6 workflows** — sin
excepción, incluyendo `approval-gate` en `release.yml` (`1440` minutos =
24h, es un gate humano esperando aprobación manual, no cómputo — un
timeout corto ahí cancelaría la espera legítima, uno ausente dejaría el
job "colgado" indefinidamente contando como minutos de runner activo
contra la cuota de la cuenta).

---

## Sección 3 — Fase 3: Testing y quality gates

**Ya aplicado desde antes, confirmado, sin cambios**: `mvn test`/`mvn
verify` fallan el workflow explícitamente si un test falla (comportamiento
default de Maven/Surefire, sin flags que lo silencien). `mvn` con
versiones fijas en `pom.xml`/`pluginManagement` ya es reproducible por
diseño del proyecto — no hay equivalente Java al problema de
`npm install` vs `npm ci` (Maven resuelve siempre contra las versiones
exactas declaradas, no hay lockfile separado que pueda quedar
desactualizado).

**Ya aplicado**: `ci-develop.yml` corre Trivy (`CRITICAL,HIGH` en warn,
`CRITICAL` en hard-fail) contra la imagen Docker de cada servicio — esto
ya era escaneo de seguridad real antes de este hardening, solo la
referencia a la action estaba rota (ver Sección 2).

**No aplicado — linting de código Java**: ningún workflow corre
Checkstyle/SpotBugs/PMD ni un linter equivalente. No decidí agregar uno
sin confirmarlo con Carlos primero — es una herramienta nueva a elegir y
configurar, no un ajuste de lo ya escrito. Queda en el checklist final
(Sección 9) como pendiente explícito, no agregado a ciegas.

**Ya aplicado**: `ci-develop.yml` tiene el coverage gate de JaCoCo (50%
líneas / 40% branches en archivos cambiados) — ver Sección 2 para el fix
de la referencia rota a la action.

---

## Sección 4 — Fase 4: Performance del pipeline

**Cache de dependencias — ya presente, un caso de doble-cacheo
eliminado**: `actions/setup-java` con `cache: 'maven'` ya cachea
`~/.m2/repository` con `restore-keys` de fallback internamente (wrapper
oficial sobre `actions/cache`, no hace falta un segundo paso manual).
`ci-pr.yml` tenía un paso `actions/cache` explícito adicional y
redundante — eliminado, `setup-java` ya cubre exactamente lo mismo.

**Paralelismo — ya correcto**: `strategy.matrix` en los 3 workflows con
build real (`ci-pr.yml`, `ci-develop.yml`, `cd-staging.yml`) ya corre cada
servicio afectado en paralelo, con `fail-fast: false` donde corresponde
(un servicio roto no cancela el test de los demás).

**Matrix strategy — ya aplicado** para las combinaciones servicio×job,
no hay versiones múltiples de runtime que testear en este proyecto
(un solo JDK 25 en todo el reactor).

**Concurrency groups con `cancel-in-progress` — agregado a los 6
workflows**, con criterio distinto según el caso: `true` en los 4 que son
build/test normal (`ci-pr.yml`, `ci-develop.yml`, `cd-staging.yml`,
`terraform-plan.yml` — un commit nuevo cancela la corrida anterior del
mismo branch/PR, ya no importa). `false` explícito en `release.yml` y
`terraform-apply.yml` — cancelar un `terraform apply` o un
tag-and-release a mitad de camino es peor que dejarlo terminar, ahí el
grupo de concurrencia solo sirve para *encolar*, no para cancelar.

**Detección de cambios por path (monorepo) — ya aplicado desde antes,
ahora deduplicado**: los 3 workflows con matrix (`ci-pr.yml`,
`ci-develop.yml`, `cd-staging.yml`) ya usaban `dorny/paths-filter` con la
misma lista de 15 servicios — copiada y pegada 3 veces. Extraída a
`.github/actions/detect-changed-services/action.yml`, ver Sección 5.

---

## Sección 5 — Fase 5: Workflows reutilizables y composite actions

**Composite action, no workflow reutilizable — la elección correcta acá**:
el patrón duplicado era un *paso* (el filtro de paths), no un *flujo*
completo de CI/CD — cada uno de los 3 workflows que lo usa hace algo
distinto con el resultado después (build+test simple, pirámide completa,
build+push+deploy). Un `workflow_call` reutilizable tendría sentido si
los 3 workflows enteros fueran casi idénticos, que no es el caso.
`.github/actions/detect-changed-services/action.yml` (composite, action
YAML local, no un workflow) captura solo el paso común.

**Deliberadamente excluye `audit-write-native` de la lista compartida** —
hallazgo real encontrado al unificar: `audit-write-native` es un módulo
Quarkus/GraalVM standalone, fuera del reactor Maven raíz (confirmado por
el propio comentario del `pom.xml` raíz). `ci-pr.yml` ya lo tenía en su
lista de filtro pero **nunca lo excluía de la lista final de servicios a
buildear** — si alguien tocaba `audit-write-native/**`, el job hubiera
intentado `mvn -B clean install -pl audit-write-native --also-make` desde
la raíz del reactor, que falla con "could not find the selected project
in the reactor" porque ese módulo no está en `<modules>`. Bug real,
nunca disparado porque nadie tocó ese directorio todavía desde que existe
el workflow — corregido sacándolo de la lista compartida en vez de
replicar el bug en los otros 2 workflows que la consumen ahora.

**Un cambio al patrón común se aplica en un solo lugar** — agregar un
servicio nuevo al monorepo ahora significa editar
`detect-changed-services/action.yml` una vez, no 3 archivos de workflow
por separado.

---

## Sección 6 — Fase 6: Entornos y aprobaciones de despliegue

**Fuera de alcance por decisión explícita de Carlos, no por omisión**:
sin servidor remoto, no hace falta CD real todavía — confirmado en Fase 0.

**Hallazgo de seguridad real, documentado en los propios workflows
(comentarios agregados en `release.yml` y `terraform-apply.yml`)**: los
dos jobs que referencian `environment: production`
(`release.yml`'s `approval-gate`/`tag-and-release`, `terraform-apply.yml`'s
`apply`) asumen que ese Environment ya existe en la configuración del
repo con un reviewer requerido. **Si no existe, GitHub lo crea
automáticamente la primera vez que se referencia, sin ninguna regla de
protección** — el "gate de aprobación manual" se auto-aprobaría en
silencio la primera vez que corra, exactamente lo opuesto de su
propósito. Confirmado con Carlos que este Environment no está configurado
todavía — hoy es inofensivo solo porque `TERRAFORM_CI_ROLE_ARN` tampoco
existe (el job falla antes de llegar a `apply` por falta de credenciales),
pero eso es un accidente de orden, no una protección real. **Acción
pendiente para Carlos, no algo que yo pueda hacer desde acá**: Settings →
Environments → New environment → nombre exacto `production` → agregar
reviewer requerido, antes de que exista un secret real o un cambio a
`terraform/**` llegue a `main`.

---

## Sección 7 — Fase 7: Gestión de artefactos y trazabilidad — ECR

**No implementado esta sesión — Carlos pidió ver el costo antes de
decidir.** Esto es lo que haría falta si en algún momento quiere subir
imágenes a ECR (sin que eso implique desplegar nada, solo almacenar
imágenes versionadas — separar esa decisión de la de CD es importante,
son cosas distintas):

**Del lado de AWS (Carlos, en la consola/CLI — no algo que yo pueda hacer
desde el repo)**:
1. Un repositorio ECR (`aws ecr create-repository --repository-name
   nexus/<servicio>` por cada servicio, o uno solo con tags por servicio —
   decisión de Carlos).
2. Un proveedor OIDC de GitHub en IAM, si no existe ya uno (el mismo que
   necesitaría `terraform-plan.yml`/`terraform-apply.yml` para
   `TERRAFORM_CI_ROLE_ARN` — se puede compartir el proveedor OIDC entre
   ambos usos, no hace falta uno por separado).
3. Un rol IAM de alcance mínimo, confiado solo en ese proveedor OIDC y
   solo para este repo (`repo:Solano204/nexus-platform-config:*` en el
   `sub` del trust policy, no un wildcard más amplio), con permisos
   limitados a `ecr:GetAuthorizationToken` +
   `ecr:BatchCheckLayerAvailability` + `ecr:PutImage` +
   `ecr:InitiateLayerUpload` + `ecr:UploadLayerPart` +
   `ecr:CompleteLayerUpload` sobre el ARN del repositorio ECR específico —
   nunca un rol con permisos amplios de ECR/administración.

**Del lado de GitHub (secrets/variables del repo)**:
- `ECR_PUSH_ROLE_ARN` (secret) — el ARN del rol del punto 3.
- `CONTAINER_REGISTRY` (variable, ya referenciada como placeholder en
  `cd-staging.yml`/`release.yml`) — `<account-id>.dkr.ecr.us-east-1.
  amazonaws.com`.

**Del lado del workflow**: reemplazar el `docker login` genérico de
`cd-staging.yml`/`release.yml` por `aws-actions/configure-aws-credentials`
(mismo patrón OIDC que ya usan los workflows de Terraform) +
`aws-actions/amazon-ecr-login` — no hace falta usuario/password, el login
a ECR es vía el rol asumido.

**Mi recomendación concreta**: no hace falta decidir esto hoy. Nada del
push de hoy depende de esto — `ci-pr.yml` (la que sí corre en cada push)
no toca ningún registro. Si más adelante querés simplemente *guardar*
imágenes versionadas sin desplegar nada, es un cambio acotado (los 3
puntos de arriba); si en cambio la intención es eventualmente desplegar
a algo real, vale la pena decidir el destino de deploy primero y diseñar
ECR como parte de eso, no por separado.

---

## Sección 8 — Fase 8: Monitoreo y troubleshooting de pipelines

**Notificación baseline — ya existe, sin configurar nada**: GitHub
notifica por email al autor de un push cuando su propio workflow falla,
comportamiento default de la plataforma, sin acción requerida.

**No agregado — integración con Slack/PagerDuty/etc. para fallos en
`main`**: no hay canal de alertas de equipo confirmado para este proyecto
(no encontré ninguna referencia a un webhook de Slack ni similar en el
repo). Sin una herramienta real a la que apuntar, agregar esto sería
inventar un destino — queda como pendiente explícito, no resuelto a
medias con una URL de ejemplo que no funciona.

**Debug logging**: para troubleshooting de un run que falla sin
explicación clara, GitHub soporta reintentar con logs verbose seteando
los repo secrets `ACTIONS_STEP_DEBUG=true` y `ACTIONS_RUNNER_DEBUG=true`
(Settings → Secrets → New repository secret, valor `true` en ambos) — no
requiere cambiar ningún workflow, es un flag de la plataforma.

---

## Sección 9 — Checklist final consolidada

| # | Acción | Impacto | Esfuerzo | Estado |
|---|---|---|---|---|
| 1 | `permissions:` mínimos explícitos en los 6 workflows | Alto (seguridad) | S | ✅ Hecho |
| 2 | Pinear 11 actions de terceros por SHA | Alto (seguridad) | M | ✅ Hecho |
| 3 | Corregir 2 referencias de action rotas (jacoco, trivy) | Alto (el workflow directamente fallaba) | S | ✅ Hecho |
| 4 | `timeout-minutes` en los 22 jobs | Medio (costo/runners colgados) | S | ✅ Hecho |
| 5 | `concurrency` + `cancel-in-progress` en los 6 workflows | Medio (velocidad/costo) | S | ✅ Hecho |
| 6 | Composite action `detect-changed-services` (dedup de 3 copias) | Medio (mantenibilidad) | S | ✅ Hecho |
| 7 | Corregir bug de `audit-write-native` en el reactor Maven | Alto (hubiera roto CI al primer touch) | S | ✅ Hecho |
| 8 | `NEXUS_PUSHGATEWAY_URL` de secret a variable + guard | Bajo | S | ✅ Hecho |
| 9 | `workflow_dispatch` en `ci-pr.yml`/`terraform-plan.yml` | Bajo (conveniencia) | S | ✅ Hecho |
| 10 | **Configurar Environment `production` con reviewer requerido en GitHub** | **Crítico — sin esto el gate de aprobación no protege nada** | S | ⏳ Pendiente, tuyo (consola de GitHub) |
| 11 | Configurar `TERRAFORM_CI_ROLE_ARN` (rol OIDC AWS) | Alto | M | ⏳ Pendiente, tuyo (consola AWS + GitHub) |
| 12 | Decidir + configurar ECR (ver Sección 7) | Medio | M | ⏳ Pendiente, decisión tuya |
| 13 | Linter de código Java (Checkstyle/SpotBugs/PMD) | Medio | M | ⏳ Pendiente, no decidido |
| 14 | Notificaciones de fallo en `main` (Slack/etc.) | Bajo | S | ⏳ Pendiente, sin destino real confirmado |
| 15 | Crear branch `develop` para activar `ci-develop.yml`/`cd-staging.yml` | Medio | S | ⏳ Pendiente, tuyo (`CONTRIBUTING.md` ya tiene el comando) |
| 16 | Decidir destino real de CD (si/cuando exista un servidor remoto) | Alto | L | ⏳ Explícitamente fuera de alcance por ahora |

**Prioridad sugerida**: #10 antes que cualquier cosa que toque
`terraform/**` en `main` — es la única acción de esta lista que, si se
salta, deja un gate de seguridad roto en silencio. El resto es
incremental, sin apuro.

---

## Sección 10 — Segundo pase (2026-07-18): split por servicio + por evento

Carlos pidió explícitamente, después de ver el hardening inicial, dos
cosas más — confirmadas con preguntas puntuales antes de tocar nada, no
asumidas:

1. Un archivo de workflow **por servicio**, no un matrix consolidado —
   quiere ver cada microservicio como su propia entrada en el tab de
   Actions, no un job adentro de un workflow compartido.
2. Workflows nuevos **por tipo de evento**, mencionando como ejemplo un
   proyecto anterior suyo con un archivo dedicado para "rama nueva" y
   otro para "pull request".

**`ci-pr.yml` (el matrix consolidado) — eliminado, reemplazado por 17
archivos**: `_reusable-service-ci.yml` (reusable, `workflow_call`,
contiene la lógica real una sola vez — checkout, JDK 25, `mvn` build+test,
upload de resultados, métricas a Pushgateway) + 16 archivos finos
`ci-<servicio>.yml` (uno por cada uno de los 15 servicios Spring Boot más
`nexus-tracing-common`), cada uno con:
- **Trigger nativo por path**, reemplazando lo que antes hacía
  `dorny/paths-filter` + un paso de JavaScript para expandir — GitHub ya
  filtra por `paths:` antes de correr el workflow en absoluto, no hace
  falta un paso separado adentro del job para lograr lo mismo.
- Path del propio servicio + `nexus-tracing-common/**` (la lib compartida
  — un cambio ahí re-dispara los 15, igual que antes) + `pom.xml` raíz
  (el BOM central — un bump de versión ahí puede romper cualquier
  servicio, y el matrix viejo **nunca** lo cubría, esto es una mejora
  real, no solo un refactor) + el propio archivo del workflow y el
  reusable (si cambiás cómo se buildea, se re-testea a sí mismo).
- `concurrency` + `permissions: contents: read` propios, no heredados
  solo del reusable.

**`audit-write-native` — workflow propio, no llama al reusable**: es
Quarkus/GraalVM, fuera del reactor Maven raíz — el reusable asume
`mvn -pl <servicio> --also-make` desde la raíz, que falla para este
módulo (el mismo bug real de la Sección 5 original, ahora imposible de
repetir por accidente porque este módulo ni siquiera comparte el archivo
reusable). Build standalone (`cd audit-write-native && mvn package`),
JDK 21 (no 25 — confirmado en su propio `pom.xml`), modo JVM únicamente
(no `-Pnative` — un build GraalVM nativo real tarda varios minutos y
pide más memoria de la que un runner default de GitHub garantiza de
forma confiable para una app de este tamaño; queda anotado como posible
workflow separado más lento, no inventado sin confirmar que hace falta).

**Workflows nuevos por tipo de evento, uno por archivo**:

- **`pr-title-lint.yml`** — extraído de adentro de `ci-develop.yml`
  (antes era un job ahí, mezclado con el build). Corre en **cualquier**
  PR, no solo hacia `develop` — el lint de Conventional Commits importa
  para `release/*`/`hotfix/*` → `main` igual que para `feature/*` →
  `develop`, ya que `release.yml` genera el changelog leyendo esos
  títulos.
- **`pr-metadata.yml`** — 3 jobs independientes, ninguno bloqueante
  (labels/tamaño/descripción son higiene, no un quality gate):
  auto-labeling por servicio tocado (`.github/labeler.yml`, un label por
  cada uno de los 15 servicios + terraform/lambda/ci-cd/docs/shared-lib),
  label de tamaño XS-XL por líneas cambiadas
  (`codelytv/pr-size-labeler`, `fail_if_xl: false` — informativo, nunca
  bloquea), y un warning (no bloqueo) si la descripción del PR está vacía.
- **`on-branch-created.yml`** — el otro ejemplo puntual de Carlos. Usa el
  evento `create` de GitHub (rama o tag — filtrado a solo ramas acá).
  **Limitación real, explicada en el propio archivo**: para cuando este
  evento dispara, la rama ya existe — no hay forma de "bloquear" la
  creación de una rama desde un workflow, solo advertir. Válida contra la
  convención de `CONTRIBUTING.md` (`main`/`develop`/`feature/*`/
  `release/*`/`hotfix/*`, con `wip-*` reconocido explícitamente como
  legacy). Enforcement real (que una rama con nombre inválido ni se
  pueda crear) necesitaría un **ruleset** de GitHub (Settings → Rules →
  Rulesets → Branch naming) — configuración del lado de GitHub que
  Carlos tendría que hacer él mismo, no algo que un archivo de workflow
  pueda lograr.

**Lo que NO se dividió por servicio, a propósito**: `ci-develop.yml`
(pirámide completa en PR hacia `develop`) y `cd-staging.yml` (build+push+
deploy) se dejaron con su matrix consolidado — son eventos mucho menos
frecuentes que un push cualquiera (PR hacia una rama específica, no cada
push), y un solo check consolidado ahí es el patrón esperado para un gate
de merge en la UI de PRs de GitHub. Dividir esos también en 15+ archivos
cada uno hubiera significado 30+ archivos adicionales sin una razón de
visibilidad tan clara como la de `ci-pr.yml` — no hecho sin que Carlos lo
pida explícitamente.

**Verificación**: los 28 archivos (25 workflows + 1 composite action +
1 config de labeler) parsean como YAML válido
(`yaml.safe_load` contra cada uno). No corrí ninguno en GitHub real
todavía — eso depende de que el token tenga el scope `workflow`, pendiente
del lado de Carlos (ver conversación — el push anterior fue rechazado
por falta de ese scope).

---

## Resumen ejecutivo

**Qué se auditó y qué se creó**: los 6 workflows ya existentes (escritos
en una sesión anterior, nunca commiteados) se auditaron fase por fase
contra el prompt maestro de CI/CD — no se reescribieron de cero. Se
agregó 1 composite action nueva (`detect-changed-services`) para eliminar
la triplicación del filtro de paths. Se encontraron y corrigieron 3 bugs
reales que hubieran fallado en el primer run (2 referencias de action
inexistentes, 1 bug de reactor Maven con `audit-write-native`) — ninguno
relacionado a seguridad per se, pero todos hubieran hecho fallar CI de
forma confusa la primera vez que alguien los disparara.

**Qué riesgos de seguridad se mitigaron**: permisos explícitos mínimos en
los 4 workflows que no los tenían, las 11 actions de terceros usadas en
todo el CI/CD ahora están pineadas por SHA (cierra el vector de
supply-chain más documentado de GitHub Actions), y se documentó
explícitamente — en comentarios dentro de los workflows mismos, no solo
acá — el riesgo real de que el Environment `production` se auto-cree sin
protección la primera vez que se referencia si no se configura a mano
primero.

**Qué queda explícitamente pendiente, y por qué**: CD real a un servidor
remoto (Fase 6 completa) está fuera de alcance por decisión de Carlos, no
por falta de tiempo — no hay servidor remoto todavía. ECR (Fase 7) quedó
documentado con el costo exacto (qué recursos de AWS, qué secrets de
GitHub) para que Carlos decida con información completa, no implementado
a ciegas. Configurar el Environment `production` con reviewer requerido
es la única acción de la lista con urgencia real — bloquea antes de que
cualquier cambio a `terraform/**` llegue a `main`.

**Segundo pase (Sección 10)**: a pedido de Carlos, `ci-pr.yml` (matrix
consolidado) se reemplazó por 17 archivos — 1 workflow reusable + 16
finos, uno por servicio/módulo — más 3 workflows nuevos por tipo de
evento (`pr-title-lint.yml`, `pr-metadata.yml`, `on-branch-created.yml`).
Total: 25 workflows + 1 composite action + 1 config de labeler, los 28
validados como YAML correcto. Nada de esto corrió todavía en GitHub real
— el push sigue bloqueado del lado de Carlos por el scope `workflow`
faltante en el token actual.
