# 15 — Aislar un commit dentro de un working tree con trabajo mezclado

No es un audit de código — es el procedimiento que usé para commitear el
rollout de springdoc/Swagger (docs 14) sin tocar ni un byte del resto del
trabajo sin commitear que ya tenías en curso (Cognito, feature flags,
load shedding, outbox cleanup, terraform, etc. — ~500 archivos). Carlos
pidió específicamente que quedara documentado para reusarlo.

---

## El problema

Un working tree con trabajo de dos fuentes mezclado en los mismos
archivos: yo edito `SecurityConfig.java`/`application.yml`/controllers
para agregar Swagger, vos (u otra sesión) editás esos mismos archivos en
paralelo para otra cosa. `git diff <archivo>` muestra ambos cambios
entrelazados en el mismo hunk o en hunks separados del mismo archivo.

`git add <archivo>` en ese estado commitea las DOS cosas juntas — no hay
forma de decirle a git "solo esta parte del archivo". Y `git stash`/
`checkout`/`reset` para "limpiar primero" arriesga perder trabajo que no
es mío y que no está commiteado en ningún lado todavía.

## La técnica: escribir directo al índice, sin tocar el working tree

Git separa tres estados: working tree (lo que ves en el editor), índice
(lo que `git commit` va a usar), y HEAD (el último commit). Normalmente
`git add` copia working tree → índice. Pero el índice también se puede
escribir a mano, con el contenido que quieras, sin que eso pase por el
working tree:

```bash
# 1. Objeto blob en la base de datos de git, sin asociarlo a ningún commit todavía
git hash-object -w /ruta/a/tu/version-reconstruida.java

# 2. Apuntar esa ruta del índice a ese blob exacto
git update-index --cacheinfo 100644,<sha-del-paso-1>,ruta/relativa/en/el/repo/Archivo.java
```

Después de esto: `git diff --cached` muestra exactamente el contenido de
tu archivo reconstruido vs HEAD. `git diff` (sin `--cached`) sigue
mostrando el archivo real del working tree vs lo que acabás de stagear —
que es donde ves el trabajo del otro lado, todavía intacto, todavía sin
commitear, esperando su propio commit después. El working tree real
(`nexus-fraud-service/.../SecurityConfig.java` tal como está en disco)
**nunca se toca** — cero riesgo de perder nada, en el peor caso el commit
queda con algo de más o de menos y se corrige en un commit siguiente.

## El procedimiento completo, paso a paso

1. **`git status --porcelain=v1 -uall`** — nunca `-uall` con `ls`/`find`
   directo, y nunca asumas que el estado al principio de la conversación
   sigue siendo el estado real; volvé a correrlo antes de tocar nada.

2. **Por cada archivo que vos tocaste esta sesión**: `git diff -- archivo`.
   Clasificalo:
   - **Limpio** (el diff completo es tuyo, ninguna línea ajena): `git add`
     directo. La mayoría de los `SecurityConfig.java` y controllers de
     esta sesión cayeron acá — anotaciones puramente aditivas no chocan
     con casi nada.
   - **Mezclado**: seguí al paso 3.
   - **Archivo nuevo (untracked) con contenido de otra persona además del
     tuyo** (ver Sección "El caso que no se puede resolver" abajo).

3. **Para un archivo mezclado — reconstruir HEAD + tu cambio, nada más**:
   ```bash
   git show HEAD:ruta/al/archivo > /tmp/clean/archivo.ext
   ```
   Después aplicá tu edición exacta (la misma que ya hiciste, con el
   mismo `old_string`/`new_string` — herramienta `Edit` sobre el archivo
   temporal, no sobre el real) contra ese contenido de HEAD. Si el
   `old_string` no aparece en HEAD tal cual, es señal de que tu edición
   original ya se había aplicado sobre una base que incluía el cambio
   ajeno — en ese caso insertá tu bloque a mano en el punto estructural
   correcto (después del dependency de actuator, después del bloque
   `server:`, antes del siguiente método, etc.), no copies el hunk tal
   cual del diff mezclado.

4. **Verificar la reconstrucción antes de stagear**:
   ```bash
   git diff --no-index -- archivo/real.java /tmp/clean/archivo.java
   ```
   Ese diff tiene que mostrar *solo* el trabajo ajeno que estás dejando
   afuera — si aparece algo tuyo ahí, la reconstrucción está mal.

5. **Stagear**: `git hash-object -w` + `git update-index --cacheinfo`
   (ver arriba), un archivo a la vez.

6. **Validar el conjunto stageado, no el working tree** — `git show
   :ruta/archivo` (dos puntos, no doble slash) lee el blob que quedó en
   el índice. Corré el parser que corresponda contra ESO:
   ```python
   content = subprocess.run(["git","show", f":{f}"], capture_output=True).stdout
   xml.dom.minidom.parseString(content)   # pom.xml
   yaml.safe_load(content)                # application.yml
   ```
   Validar el working tree real no sirve de nada acá — podría estar bien
   solo porque el cambio ajeno (todavía sin commitear) lo tapa.

7. **Verificación cruzada final antes de cerrar**: la lista completa de
   archivos que ibas a tocar (guardada en un archivo aparte al arrancar)
   contra `git diff --cached --name-only` — `comm -23` entre las dos
   listas ordenadas tiene que dar vacío. Si algo quedó afuera, no lo vas
   a notar de otra forma una vez que hay 60+ archivos en juego.

## El caso que no se puede resolver con esta técnica

Un archivo **nuevo** (untracked, sin entrada en HEAD) donde otra persona
escribió el archivo entero y vos le agregaste algo encima (ej.
`FeatureFlagAdminController.java` esta sesión — Carlos escribió el
controller completo sin commitear, yo le agregué anotaciones Swagger
arriba de cada método). No hay HEAD contra el cual reconstruir "solo mi
parte" — el archivo entero es indivisible en git hasta que exista una
primera versión commiteada.

**La única opción correcta es no incluirlo en tu commit.** Reconstruir
"el archivo sin mis anotaciones" para commitearlo primero sería commitear
código ajeno en tu nombre sin que esa persona lo haya revisado. Dejalo
afuera, decilo explícitamente, seguí de largo — las anotaciones siguen
ahí en el working tree, sin perderse, esperando el commit donde el dueño
real de ese archivo lo commitee.

## Cuándo se justifica este nivel de esfuerzo

Con 2-3 archivos mezclados, alcanza con mirar el diff y reconstruir a
mano sin tanto ritual. Esta técnica completa (con verificación cruzada
de lista, validación de blob stageado, etc.) se justifica a partir de
~10-15 archivos, donde un error de "me olvidé de este uno" ya no se
detecta a simple vista. Esta sesión fueron 81 archivos (62 modificados +
19 nuevos) — sin el paso 7 (verificación cruzada), es prácticamente
seguro que algo se hubiera quedado afuera sin que nadie lo notara hasta
mucho después.
