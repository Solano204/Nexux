---
name: context-reset
description: Guide for managing context window in the NEXUS project — when to /compact vs /clear
---

Help the user manage their Claude Code context window for the NEXUS platform.

## When to suggest /compact
Use `/compact` when:
- The conversation is long but the work is ongoing (e.g. debugging a service mid-session)
- Switching from one microservice to another in the same work session
- Context is growing but the current task is not finished

What it does: compresses older messages into a summary, keeps recent context.

## When to suggest /clear
Use `/clear` when:
- Switching from backend work to infrastructure work (completely different domain)
- Starting a totally new task (e.g. done with fraud service, now working on analytics)
- After a major task completes and you want a clean slate
- Context rot: Claude starts forgetting or confusing service names/ports

What it does: completely resets context (CLAUDE.md is re-read automatically).

## NEXUS-specific context tips

1. **Always specify the service** — say "fix nexus-fraud-service" not "fix the fraud logic"
2. **Port context to new sessions via CLAUDE.md** — all critical facts are already there
3. **One service per session** — working on 3 services in one session fills context fast
4. **Before /clear, save any decisions** to CLAUDE.md or a commit message so they survive the reset

## Context budget awareness
- 16 services × their files = context fills in ~20-30 tool reads
- Grep/Glob are cheaper than Read for exploration
- Ask Claude to read only specific files, not "read the whole service"

If the user types `/context-reset`, show them this guide and ask which situation they're in.
