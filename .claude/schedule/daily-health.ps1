# Scheduling — Daily NEXUS Health Report
# Register as Windows Task Scheduler job:
#   Register-ScheduledTask -TaskName "NEXUS-DailyHealth" -Trigger (New-ScheduledTaskTrigger -Daily -At "09:00AM") -Action (New-ScheduledTaskAction -Execute "powershell" -Argument "-File C:\Users\GAMER\Music\PROJECT IA STARTUP\NEXUS\.claude\schedule\daily-health.ps1") -RunLevel Highest
#
# Or run manually: powershell -File .claude/schedule/daily-health.ps1

$LOG_FILE = "$PSScriptRoot\..\..\logs\health-$(Get-Date -Format 'yyyy-MM-dd').log"
New-Item -ItemType Directory -Path (Split-Path $LOG_FILE) -Force | Out-Null

Write-Host "NEXUS Scheduler — Daily health check at $(Get-Date)"

$prompt = @"
Run a health check on all 16 NEXUS services.
Hit each /actuator/health endpoint.
Ports: api-gateway=8080, config=8888, discovery=8761, identity=8083, account=8085, transaction=8086, fraud=8087, ledger=8088, notification=8089, ai-assistant=8090, ai-kyc=8091, analytics=8092, risk=8094, saga=8095, audit-query=8097, audit-write=8096 (/q/health).
Report: total UP, total DOWN, list any DOWN services with container log tail.
"@

$result = claude -p $prompt 2>&1
$result | Out-File -FilePath $LOG_FILE -Encoding utf8

Write-Host "Health report saved to: $LOG_FILE"
Write-Host $result
