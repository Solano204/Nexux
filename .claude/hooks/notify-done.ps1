# Hook: Show a Windows toast notification when Claude finishes a task
# Triggered by Claude Code Notification event
# Exit 0 always

$input_json = $input | Out-String
$data = $input_json | ConvertFrom-Json

$message = $data.message
if (-not $message) { $message = "Task completed." }

# Truncate long messages for the notification
if ($message.Length -gt 200) {
    $message = $message.Substring(0, 197) + "..."
}

# Windows toast notification using BurntToast or fallback to balloon tip
try {
    # Try BurntToast module (install with: Install-Module BurntToast -Scope CurrentUser)
    if (Get-Module -ListAvailable -Name BurntToast -ErrorAction SilentlyContinue) {
        Import-Module BurntToast
        New-BurntToastNotification -Text "NEXUS — Claude Code", $message -AppLogo "$PSScriptRoot\..\..\README.md"
    } else {
        # Fallback: system tray balloon via .NET
        Add-Type -AssemblyName System.Windows.Forms
        $notify = New-Object System.Windows.Forms.NotifyIcon
        $notify.Icon = [System.Drawing.SystemIcons]::Information
        $notify.Visible = $true
        $notify.ShowBalloonTip(5000, "NEXUS — Claude Code", $message, [System.Windows.Forms.ToolTipIcon]::Info)
        Start-Sleep -Seconds 6
        $notify.Dispose()
    }
} catch {
    # Silent fail — notifications are optional
    Write-Host "NEXUS notify: $message"
}

exit 0
