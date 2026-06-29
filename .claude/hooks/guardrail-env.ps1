# Hook: Block any git command that would stage or commit .env
# Receives JSON on stdin from Claude Code PreToolUse event
# Exit 2 = block the action | Exit 0 = allow

$input_json = $input | Out-String
$data = $input_json | ConvertFrom-Json

$tool_name = $data.tool_name
$command   = $data.tool_input.command

if ($tool_name -eq "Bash" -and $command) {
    # Block: git add .env  /  git add -A  /  git commit -am  / git add .
    $dangerous_patterns = @(
        'git add \.env',
        'git add -A',
        'git add \.',
        'git commit -am',
        'git commit -a '
    )

    foreach ($pattern in $dangerous_patterns) {
        if ($command -match $pattern) {
            # Check if .env would actually be included
            Write-Host "NEXUS GUARDRAIL: Detected a git staging command that may include .env"
            Write-Host "ACTION BLOCKED: Stage files individually (e.g. git add src/) to avoid committing .env with production passwords."
            exit 2
        }
    }

    # Block explicit git add .env or git add .env.*
    if ($command -match 'git add.*\.env') {
        Write-Host "NEXUS GUARDRAIL: Blocked attempt to stage .env file."
        Write-Host "The .env file contains production passwords and must NEVER be committed."
        exit 2
    }
}

exit 0
