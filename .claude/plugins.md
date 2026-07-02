# NEXUS — Recommended Claude Code Plugins

Install these plugins once to add capabilities to Claude Code for this project.
Run each command in your terminal (not inside Claude Code).

## Install commands

```bash
# Docker plugin — manage containers, images, volumes from Claude
claude plugin install @anthropic/docker

# GitHub plugin — PRs, issues, releases from Claude
claude plugin install @anthropic/github

# Spring Boot plugin — Spring-specific commands and scaffolding
claude plugin install @anthropic/spring-boot

# Kubernetes plugin — if you ever deploy NEXUS to k8s
claude plugin install @anthropic/kubernetes
```

## After installing

Plugins add new slash commands. Examples:
- `/docker ps` — list running containers
- `/docker logs nexus-fraud-service` — tail logs
- `/github pr create` — create a pull request
- `/github issues list` — list open issues

## Verify installed plugins
```bash
claude plugin list
```

## Notes
- Plugins are global (not per-project) — install once, available everywhere
- Plugin commands are additive — they don't replace skills in `.claude/skills/`
- If a plugin conflicts with a local skill, the local skill takes priority
