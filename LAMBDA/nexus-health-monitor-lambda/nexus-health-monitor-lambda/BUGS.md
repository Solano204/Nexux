# nexus-health-monitor-lambda — Source Code Issues Found

No bugs that prevent the service from running, but two code quality issues worth cleaning up, plus one missing deployment file.

---

## ISSUE 1 (MEDIUM) — Duplicate module tree: `src/response/analysis/` is dead code

**Files:**
- `src/analysis/alert_decider.py` ← used by `handler.py`
- `src/analysis/scenario_analyzer.py` ← used by `handler.py`
- `src/response/analysis/alert_decider.py` ← **never imported anywhere**
- `src/response/analysis/scenario_analyzer.py` ← **never imported anywhere**

**Problem:**  
`handler.py` imports from `analysis.*` (top-level `src/analysis/`). The `src/response/analysis/` directory contains identical copies that are never imported. These are leftover artefacts from a refactoring that moved code from `src/response/analysis/` → `src/analysis/` but didn't delete the originals.

**Impact:** No runtime impact (dead code is never loaded). However, if someone edits one copy thinking they're updating the live code, the other stays stale — a future maintenance trap.

**Fix:** Delete the dead copies:
```bash
rm -rf src/response/
```

---

## ISSUE 2 (MEDIUM) — `src/service/` directory is dead code

**Files:**
- `src/service/dashboard_manager.py` ← **never imported anywhere**
- `src/service/utils/logging_config.py` ← **never imported anywhere**

**Problem:**  
`handler.py` imports `from cloudwatch.dashboard_manager import ensure_dashboard`. The `src/service/dashboard_manager.py` is a separate, differently-scoped copy that is never called.

`src/service/utils/logging_config.py` duplicates `src/utils/logging_config.py`.

Again, these are leftover refactoring artefacts.

**Fix:**
```bash
rm -rf src/service/
```

---

## ISSUE 3 (LOW) — CI deploy stage uses inline parameters; no `samconfig.toml`

The existing CI deploy step hardcodes parameters via `--parameter-overrides`. A `samconfig.toml` has been created and is included in this zip. The CI workflow has also been improved to:
- Add a `staging` environment before production
- Remove the inline parameters in favour of `samconfig.toml`
- Use `--config-env` for consistency with other Lambdas

---

## Summary checklist

- [ ] **ISSUE 1:** `rm -rf src/response/` — delete dead duplicate modules
- [ ] **ISSUE 2:** `rm -rf src/service/` — delete dead duplicate utilities
- [ ] **ISSUE 3:** Add `samconfig.toml` from this zip; update CI to use `--config-env`

None of these prevent the Lambda from running in production.
