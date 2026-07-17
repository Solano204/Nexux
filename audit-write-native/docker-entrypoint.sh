#!/bin/sh
# Resolves Docker Compose file-based secrets before starting the JVM.
#
# Compose's own ${VAR} interpolation in docker-compose-prod.yml happens on
# the HOST at parse time, reading from .env — so passing secrets through
# `environment:` there means the real value ends up in `docker inspect`/
# `docker compose config` output regardless of anything done at the image
# level. Compose's native `secrets:` mechanism avoids that: it mounts a
# file at /run/secrets/<name> inside the container instead, never setting
# an env var and never showing up in inspect output.
#
# Quarkus's own ${FOO} properties expect a plain env var though, not a
# file path — so for every FOO_FILE=/run/secrets/<name> this container was
# given, read the file and export FOO=<contents> before handing off to
# java. `exec "$@"` at the end replaces this script's PID with the JVM's,
# so java still ends up as PID 1 and still receives SIGTERM directly.
set -e

for var in $(env | grep -E '^[A-Z0-9_]+_FILE=' | cut -d= -f1); do
  target="${var%_FILE}"
  path="$(eval echo \$"$var")"
  export "$target=$(cat "$path")"
done

# MONGODB_URI embeds the password inline (mongodb://nexus:<pw>@host:port/db)
# so it can't be built by Compose's host-side ${VAR} interpolation without
# putting the real password in compose config. Assembled here instead, from
# MONGO_PASSWORD (resolved above) + MONGO_HOST/MONGO_PORT/MONGO_DB (plain,
# non-secret vars).
if [ -n "${MONGO_DB:-}" ] && [ -n "${MONGO_PASSWORD:-}" ]; then
  export MONGODB_URI="mongodb://nexus:${MONGO_PASSWORD}@${MONGO_HOST:-nexus-mongodb}:${MONGO_PORT:-27017}/${MONGO_DB}?authSource=admin"
fi

exec "$@"
