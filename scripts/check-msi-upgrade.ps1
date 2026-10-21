# Installs the desktop MSI per user, upgrades it with a newer one and uninstalls it, checking that the app's files live under
# %LOCALAPPDATA%\Current App and that the app's own data folder (%LOCALAPPDATA%\Current, AppFolders.local()) survives.
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

$installDir = Join-Path $env:LOCALAPPDATA 'Current App'
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
Get-Entries | Format-List DisplayName, DisplayVersion, Publisher, InstallLocation, DisplayIcon, PSPath
if (-not (Test-Path (Join-Path $installDir 'Current.exe'))) {
    Write-Host 'Current.exe found at:'
    @($env:LOCALAPPDATA, $env:APPDATA, $env:ProgramFiles, ${env:ProgramFiles(x86)}) | Where-Object { $_ } | ForEach-Object {
        Get-ChildItem -Path $_ -Filter 'Current.exe' -Recurse -ErrorAction SilentlyContinue -Depth 4 | ForEach-Object { $_.FullName }
    }
    Select-String -Path (Join-Path $logDir 'install-first.log') -Pattern 'INSTALLDIR|ALLUSERS' | Select-Object -First 30 | ForEach-Object { $_.Line }
}
Assert (Test-Path (Join-Path $installDir 'Current.exe')) "Current.exe is in $installDir"
Assert (-not (Test-Path (Join-Path $dataDir 'Current.exe'))) "nothing is installed into $dataDir"
$entry = @(Get-Entries)
Assert ($entry.Count -eq 1 -and $entry[0].DisplayName -eq 'Current') 'one installed-apps entry named Current'
$startMenu = Join-Path ([Environment]::GetFolderPath('Programs')) 'Current\Current.lnk'
$desktop = Join-Path ([Environment]::GetFolderPath('Desktop')) 'Current.lnk'
if (-not ((Test-Path $startMenu) -and (Test-Path $desktop))) {
    Write-Host 'Shortcuts found:'
    @([Environment]::GetFolderPath('Programs'), [Environment]::GetFolderPath('Desktop'),
      [Environment]::GetFolderPath('CommonPrograms'), [Environment]::GetFolderPath('CommonDesktopDirectory')) | ForEach-Object {
        Get-ChildItem -Path $_ -Filter 'Current*.lnk' -Recurse -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
    }
}
Assert (Test-Path $startMenu) "Start menu shortcut $startMenu"
Assert (Test-Path $desktop) "desktop shortcut $desktop"
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
Assert (-not (Test-Path $startMenu)) 'the Start menu shortcut is gone'
Assert (-not (Test-Path $desktop)) 'the desktop shortcut is gone'
