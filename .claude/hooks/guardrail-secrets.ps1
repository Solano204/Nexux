# Hook: Block committing secrets/ directory or .jks / .p12 keystore files
# Receives JSON on stdin from Claude Code PreToolUse event
# Exit 2 = block | Exit 0 = allow

$input_json = $input | Out-String
$data = $input_json | ConvertFrom-Json

$tool_name = $data.tool_name
$command   = $data.tool_input.command

if ($tool_name -eq "Bash" -and $command) {

    # Block staging the secrets/ directory or keystore files
    $blocked_patterns = @(
        'git add.*secrets/',
        'git add.*\.jks',
        'git add.*\.p12',
        'git add.*\.pem',
        'git add.*\.key',
        'git add.*nexus-identity\.jks'
    )

    foreach ($pattern in $blocked_patterns) {
        if ($command -match $pattern) {
            Write-Host "NEXUS GUARDRAIL: Blocked attempt to stage a secret/keystore file."
            Write-Host "File matched pattern: $pattern"
            Write-Host "The secrets/ directory and keystore files must NEVER be committed to git."
            exit 2
        }
    }

    # Block writing the keystore password directly in a command
    if ($command -match 'NexusJWT2026KeyPass' -or $command -match 'NexusProd2026Secure') {
        Write-Host "NEXUS GUARDRAIL: Blocked command containing a plaintext production password."
        Write-Host "Use environment variable references instead of hardcoded values."
        exit 2
    }
}

exit 0
