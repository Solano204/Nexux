# Register all NEXUS Claude Code scheduled tasks in Windows Task Scheduler
# Run once as Administrator: powershell -ExecutionPolicy Bypass -File .claude/schedule/register-tasks.ps1

$BASE = "C:\Users\GAMER\Music\PROJECT IA STARTUP\NEXUS"

Write-Host "Registering NEXUS scheduled tasks..."

# 1. Daily health check at 9:00 AM
$trigger1 = New-ScheduledTaskTrigger -Daily -At "09:00AM"
$action1  = New-ScheduledTaskAction -Execute "powershell.exe" `
    -Argument "-ExecutionPolicy Bypass -File `"$BASE\.claude\schedule\daily-health.ps1`""
Register-ScheduledTask `
    -TaskName   "NEXUS-DailyHealth" `
    -Trigger    $trigger1 `
    -Action     $action1 `
    -RunLevel   Highest `
    -Force `
    -Description "Daily NEXUS platform health report via Claude Code"

Write-Host "Registered: NEXUS-DailyHealth (runs daily at 09:00)"

# 2. Security scan every Monday at 8:00 AM
$trigger2 = New-ScheduledTaskTrigger -Weekly -DaysOfWeek Monday -At "08:00AM"
$action2  = New-ScheduledTaskAction -Execute "powershell.exe" `
    -Argument "-ExecutionPolicy Bypass -File `"$BASE\.claude\headless\ci-security-scan.ps1`""
Register-ScheduledTask `
    -TaskName   "NEXUS-WeeklySecurity" `
    -Trigger    $trigger2 `
    -Action     $action2 `
    -RunLevel   Highest `
    -Force `
    -Description "Weekly NEXUS security scan via Claude Code"

Write-Host "Registered: NEXUS-WeeklySecurity (runs every Monday at 08:00)"
Write-Host ""
Write-Host "To list tasks: Get-ScheduledTask -TaskName 'NEXUS-*'"
Write-Host "To remove:     Unregister-ScheduledTask -TaskName 'NEXUS-DailyHealth' -Confirm:`$false"
