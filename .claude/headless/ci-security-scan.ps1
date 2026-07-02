# Headless Claude Code — Pre-deploy Security Scan
# Runs the security-scanner agent headlessly and fails the pipeline if CRITICAL issues are found
# Usage: powershell -File .claude/headless/ci-security-scan.ps1
# Returns: exit 0 if no CRITICAL/HIGH, exit 1 if any found

Write-Host "NEXUS CI — Running headless security scan via Claude Code..."

$prompt = @"
You are running as the security-scanner agent for the NEXUS platform.
Scan all service directories for: hardcoded secrets, exposed actuator endpoints, CORS misconfigs, Dockerfiles running as root.
Return a JSON object: {"critical": N, "high": N, "medium": N, "low": N, "findings": [{"severity":"...","location":"...","issue":"..."}]}
"@

$result = claude --output-format json -p $prompt 2>&1

Write-Host $result

try {
    $json = $result | ConvertFrom-Json
    $critical = $json.critical
    $high = $json.high

    if ($critical -gt 0) {
        Write-Host "FAIL: $critical CRITICAL security issue(s) found — deploy blocked"
        exit 1
    } elseif ($high -gt 0) {
        Write-Host "WARNING: $high HIGH severity issue(s) found — review before deploying"
        exit 1
    } else {
        Write-Host "PASS: No critical or high severity security issues found"
        exit 0
    }
} catch {
    Write-Host "WARNING: Could not parse scan output"
    exit 1
}
