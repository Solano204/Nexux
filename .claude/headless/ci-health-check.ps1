# Headless Claude Code — CI Health Check
# Runs Claude without UI to check all 16 NEXUS service health endpoints
# Usage: powershell -File .claude/headless/ci-health-check.ps1
# Returns: exit 0 if all UP, exit 1 if any DOWN

Write-Host "NEXUS CI — Running headless health check via Claude Code..."

$prompt = @"
Check the health of all 16 NEXUS services running on localhost.
For each service, curl http://localhost:<PORT>/actuator/health (or /q/health for port 8096).
Ports: 8080,8888,8761,8083,8085,8086,8087,8088,8089,8090,8091,8092,8094,8095,8097,8096.
Return a JSON object: {"total": 16, "up": N, "down": N, "services": [{"name":"...", "port":N, "status":"UP|DOWN"}]}
"@

# Run Claude headless and capture output
$result = claude --output-format json -p $prompt 2>&1

Write-Host $result

# Parse result and set exit code
try {
    $json = $result | ConvertFrom-Json
    $down_count = $json.down
    if ($down_count -gt 0) {
        Write-Host "FAIL: $down_count service(s) are DOWN"
        exit 1
    } else {
        Write-Host "PASS: All 16 services are UP"
        exit 0
    }
} catch {
    Write-Host "WARNING: Could not parse Claude output as JSON — check manually"
    exit 1
}
