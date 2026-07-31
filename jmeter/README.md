# JMeter load test (Part 6)

No JMeter test plan existed before this - `ORDERTEST/` and `ORDER_TEST/` (checked
first, per the master prompt's own hint) are manual testing checklists/notes,
not `.jmx` files, and their example payloads are **stale**: e.g.
`ORDERTEST/3-nexus-identity-service.md`'s register example uses
`firstName`/`lastName`/`phone`, which doesn't match the real
`RegisterRequest` record (`email, password, fullName, phoneNumber,
dateOfBirth, country`) verified directly from
`nexus-identity-service/.../web/dto/request/RegisterRequest.java` this
session. `nexus-load-test.jmx` uses the verified-real fields, not the doc's.

## Two thread groups, deliberately not one flow

A fresh registration is KYC-gated - `status=PENDING_KYC`, zero accounts,
until the onboarding saga runs full KYC (needs a real document image for
Rekognition) and creates accounts. A synthetic load test can't complete that
automatically. So:

- **Registration Flow**: register + login only, fresh synthetic users every
  iteration (`loadtest-${__UUID()}@nexus-loadtest.local`). Tests
  identity-service's write path under load.
- **Transaction Flow**: needs `test-users.csv` filled in with real,
  already-ACTIVE, already-KYC'd accounts (see the placeholder file next to
  this README). To get one, either complete a real onboarding flow once
  manually, or do what I did earlier this session to verify Part 1's fix
  live: reset an existing ACTIVE test user's `password_hash` directly in
  Postgres to a known BCrypt hash. That is NOT something to script into
  this test plan - it's a one-time manual seeding step, documented here so
  it isn't silently assumed to work.

## Known gap in the Registration Flow's login sampler

Its login call currently uses its own independent `${__UUID()}`, not the
exact email the register call in the same iteration used - it's testing
login load in isolation, not "log in as the user we just registered."
Wiring that correlation needs a Regex/JSON PostProcessor on the register
sampler extracting the email into a variable, which wasn't added because a
freshly-registered PENDING_KYC user still can't do anything past login
anyway (see above) - flagged as a known gap rather than silently wired to
look more complete than it is.

## Prometheus wiring

Added `nexus-pushgateway` (prom/pushgateway) to `docker-compose-prod.yml`
and a matching scrape job in `monitoring/prometheus.yml`
(`job_name: nexus-pushgateway`). Each sampler has a JSR223 PostProcessor
(Groovy, bundled with JMeter - no third-party plugin JAR to source/verify)
that pushes that sample's duration + response code to Pushgateway after
every request, so a running load test shows up in the same
Spring Boot Statistics / Infra Deep Dive dashboards live, per the master
prompt's ask - not a separate JMeter-only report.

## Running it (when you're ready - not run as part of this session)

```bash
apache-jmeter -n -t jmeter/nexus-load-test.jmx \
  -Jgateway.host=localhost -Jgateway.port=8080 \
  -Jpushgateway.url=http://localhost:9091 \
  -Jtestusers.csv=jmeter/test-users.csv \
  -l jmeter/results.jtl
```

**This file was hand-authored, not exported from a running JMeter GUI** -
open it in the JMeter GUI once and confirm it loads/parses cleanly before a
real run, per the same "verify before trusting" standard as everything else
built this session without a live service to check against.
