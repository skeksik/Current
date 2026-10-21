# Installs the desktop MSI per user, upgrades it with a newer one and uninstalls it, checking that the app's files live under
# %LOCALAPPDATA%\Programs\Current and that the app's own data folder (%LOCALAPPDATA%\Current, AppFolders.local()) survives.
# Windows only; runs in CI. Usage: pwsh scripts/check-msi-upgrade.ps1 -First <older.msi> -Second <newer.msi> -SecondVersion 0.1.1
param(
    [Parameter(Mandatory)] [string] $First,
    [Parameter(Mandatory)] [string] $Second,
    [Parameter(Mandatory)] [string] $SecondVersion
)
$ErrorActionPreference = 'Stop'
# Full Windows paths for msiexec (CI passes paths with forward slashes from Git Bash).
$First = (Resolve-Path $First).Path
$Second = (Resolve-Path $Second).Path

$installDir = Join-Path $env:LOCALAPPDATA 'Programs\Current'
$dataDir = Join-Path $env:LOCALAPPDATA 'Current'
$marker = Join-Path $dataDir 'local.json'
$logDir = Join-Path ([IO.Path]::GetTempPath()) 'current-msi-logs'
New-Item -ItemType Directory -Force $logDir | Out-Null

function Invoke-Msiexec([string] $Action, [string] $Msi, [string] $Name) {
    $log = Join-Path $logDir "$Name.log"
    $process = Start-Process msiexec.exe -ArgumentList @($Action, "`"$Msi`"", '/qn', '/l*v', "`"$log`"") -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        Get-Content $log -Tail 60
        throw "msiexec $Action $Name exited with $($process.ExitCode)"
    }
}

# Installed-apps entries named Current, per user or per machine.
function Get-Entries {
    @('HKCU:', 'HKLM:', 'HKLM:\Software\WOW6432Node') | ForEach-Object {
        Get-ItemProperty "$_\Software\Microsoft\Windows\CurrentVersion\Uninstall\*" -ErrorAction SilentlyContinue
    } | Where-Object { $_.DisplayName -eq 'Current' }
}

function Assert([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw "FAIL: $Message" }
    Write-Host "ok: $Message"
}

Invoke-Msiexec '/i' $First 'install-first'
Assert (Test-Path (Join-Path $installDir 'Current.exe')) "Current.exe is in $installDir"
Assert (-not (Test-Path (Join-Path $dataDir 'Current.exe'))) "nothing is installed into $dataDir"
Get-Entries | Format-List DisplayName, DisplayVersion, Publisher, InstallLocation, DisplayIcon, PSPath
# Windows Installer keeps ARPPRODUCTICON as the product's ProductIcon here, not as DisplayIcon in the Uninstall key (printed for the record).
Get-ItemProperty 'HKCU:\Software\Microsoft\Installer\Products\*' -ErrorAction SilentlyContinue |
    Where-Object { $_.ProductName -eq 'Current' } | Format-List ProductName, ProductIcon

New-Item -ItemType Directory -Force $dataDir | Out-Null
Set-Content -Path $marker -Value '{"marker":"ci"}'

Invoke-Msiexec '/i' $Second 'install-second'
$entries = @(Get-Entries)
$entries | Format-List DisplayName, DisplayVersion, Publisher, InstallLocation, PSPath
Assert ($entries.Count -eq 1) 'one installed-apps entry after the upgrade'
Assert ($entries[0].DisplayVersion -eq $SecondVersion) "the entry is at $SecondVersion"
Assert (Test-Path (Join-Path $installDir 'Current.exe')) "Current.exe is still in $installDir"
Assert (Test-Path $marker) "$marker survives the upgrade"

Invoke-Msiexec '/x' $Second 'uninstall'
Assert (@(Get-Entries).Count -eq 0) 'no installed-apps entry after uninstalling'
Assert (-not (Test-Path $installDir)) "$installDir is gone"
Assert (Test-Path $marker) "$marker survives the uninstall"
