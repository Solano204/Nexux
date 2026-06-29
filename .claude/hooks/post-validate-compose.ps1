# Hook: After editing docker-compose-prod.yml, validate its syntax
# Receives JSON on stdin from Claude Code PostToolUse event
# Exit 0 always (post-hooks are informational, not blocking)

$input_json = $input | Out-String
$data = $input_json | ConvertFrom-Json

$file_path = $data.tool_input.file_path

# Only validate if a docker-compose file was edited
if ($file_path -and ($file_path -match "docker-compose" -or $file_path -match "docker-compose-prod")) {

    # Check if docker is available
    $docker_available = Get-Command "docker" -ErrorAction SilentlyContinue

    if ($docker_available) {
        Write-Host "NEXUS: Validating $file_path ..."

        $result = & docker compose -f $file_path config --quiet 2>&1
        $exit_code = $LASTEXITCODE

        if ($exit_code -eq 0) {
            Write-Host "NEXUS: docker-compose-prod.yml syntax is valid."
        } else {
            Write-Host "NEXUS WARNING: docker-compose-prod.yml has errors:"
            Write-Host $result
        }
    } else {
        Write-Host "NEXUS: Docker not found — skipping compose validation."
    }
}

exit 0
