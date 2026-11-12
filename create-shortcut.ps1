# Creates a "Panel Controller" shortcut on the desktop, with the logo and without a console window.
# Run once after "mvn package":  powershell -ExecutionPolicy Bypass -File create-shortcut.ps1
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not (Test-Path (Join-Path $here 'target\panel-controller.jar'))) {
    Write-Host 'target not found. Build first: mvn package'
    exit 1
}
$javaw = (Get-Command javaw.exe -ErrorAction SilentlyContinue).Source
if (-not $javaw) {
    Write-Host 'javaw.exe not found. Is Java 25 or newer installed?'
    exit 1
}

$arguments = '--enable-native-access=javafx.graphics,javafx.web,ALL-UNNAMED ' +
             '--module-path "target\lib\javafx" --add-modules javafx.controls,javafx.web ' +
             '-cp "target\panel-controller.jar;target\lib\cp\*" ' +
             'io.github.wreazycs.panelcontroller.gui.PanelApp'

$desktop = [Environment]::GetFolderPath('Desktop')
$path = Join-Path $desktop 'Panel Controller.lnk'
$link = (New-Object -ComObject WScript.Shell).CreateShortcut($path)
$link.TargetPath = $javaw
$link.Arguments = $arguments
$link.WorkingDirectory = $here
$link.IconLocation = (Join-Path $here 'docs\panel-controller.ico')
$link.Description = 'Panel Controller'
$link.Save()
Write-Host "Shortcut created: $path"
