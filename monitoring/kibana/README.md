# Kibana provisioning (Part 5)

Kibana has no file-based provisioning directory the way Grafana does (nothing
auto-loads on container start) - `saved-objects.ndjson` in this folder is a
standard Kibana Saved Objects export and has to be imported once, manually or
via one API call, after the stack is up.

## What's in `saved-objects.ndjson`

**3 index patterns** (indices verified directly from source, not guessed):
- `zipkin-span-*` — confirmed via docker-compose-prod.yml: `nexus-zipkin` runs
  with `STORAGE_TYPE: elasticsearch`, which uses Zipkin's default
  `zipkin-span-yyyy-MM-dd` daily index naming.
- `nexus-audit-*` — confirmed via `AuditEventDocument.java`
  (`@Document(indexName = "nexus-audit-*")`) and `AuditEventConsumer.java`
  (writes to `nexus-audit-{year}-{month}`).
- `transactions` — confirmed via `TransactionSearchDocument.java`
  (`@Document(indexName = "transactions")`).

(`nexus-analytics-user` also exists per `AnalyticsDocument.java` but wasn't
given a saved search here - nothing in the master prompt's cheatsheet needed
it specifically.)

**5 saved searches** (Kibana's lightest-weight visualization - a saved KQL
query + column set, not a full aggregation chart. Chosen over hand-authoring
full Kibana `visualization` saved objects because those need exact vis-state
JSON matching Kibana 8.13's schema, which isn't safely writable without a
running instance to validate against - risk of "never invent" over "look
complete"):
- `zipkin-duplicate-processing-search` — every `nexus.idempotency.duplicate=true`
  span platform-wide (the tag added in Part 1)
- `zipkin-slow-ai-spans-search` — any `gen_ai.*`-tagged span over 5s
- `zipkin-error-spans-search` — every span with `error=true`
- `audit-sar-review-search` — `requiresSarReview=true` audit events
- `audit-financial-events-search` — `isFinancialEvent=true` audit events

## Importing

Once the stack is up (`nexus-kibana` healthy on port 5601):

```bash
curl -X POST http://localhost:5601/api/saved_objects/_import \
  -H "kbn-xsrf: true" \
  --form file=@monitoring/kibana/saved-objects.ndjson
```

Or via the UI: **Stack Management → Saved Objects → Import**, select this
file.

## Part 5.2 — audit-trail dashboard

Not built as a full Kibana `dashboard` saved object for the same reason as
above (unverifiable vis-state schema without a live instance). The two audit
searches above (`audit-sar-review-search`, `audit-financial-events-search`)
are the base views a real dashboard would panel-ize - once the stack is up
and these searches return real data, the fastest path is: open one in
Discover, click "Visualize" on `category`/`severity`/`sourceService` to get
Kibana's auto-generated aggregation charts, then save those + the searches
together as a dashboard. That's a 5-minute UI task once there's real data to
look at, versus a much higher-risk hand-authored JSON blob right now.
