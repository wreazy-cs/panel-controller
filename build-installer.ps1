# Builds the Windows installer: dist\Panel Controller-<version>.exe
#
# The installer carries its own Java runtime, so the target computer needs no Java.
# Requirements on the BUILD computer only: JDK 25+ (jpackage), Maven build done (mvn package),
# and for the .exe: WiX Toolset 5 (dotnet tool install --global wix --version 5.0.2, plus
# wix extension add -g WixToolset.Util.wixext/5.0.2 WixToolset.UI.wixext/5.0.2).
#
#   powershell -ExecutionPolicy Bypass -File build-installer.ps1                -> installer (.exe)
#   powershell -ExecutionPolicy Bypass -File build-installer.ps1 -Type msi      -> MSI (for silent/IT installs)
#   powershell -ExecutionPolicy Bypass -File build-installer.ps1 -Type app-image -> runnable folder only (no WiX needed)
param([ValidateSet('exe', 'msi', 'app-image')][string]$Type = 'exe')
$AppImage = $Type -eq 'app-image'
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

$version = ([xml](Get-Content pom.xml)).project.version
if (-not (Test-Path target\panel-controller.jar)) { throw 'target\panel-controller.jar yok. Once: mvn package' }

$jpackage = Join-Path $env:JAVA_HOME 'bin\jpackage.exe'
if (-not $env:JAVA_HOME -or -not (Test-Path $jpackage)) {
    $javaExe = (Get-Command java -ErrorAction Stop).Source
    $jpackage = Get-ChildItem 'C:\Program Files\Java\*\bin\jpackage.exe' -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
    if (-not $jpackage) { throw 'jpackage bulunamadi (JDK 25+ gerekir)' }
}

# staging: the main jar and its class-path libraries; JavaFX goes beside them as modules
$stage = Join-Path $here 'target\installer'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory "$stage\input", "$stage\javafx" | Out-Null
Copy-Item target\panel-controller.jar "$stage\input\"
Copy-Item target\lib\cp\*.jar "$stage\input\"
Copy-Item target\lib\javafx\*.jar "$stage\javafx\"

# the Java modules the app uses (JavaFX itself is loaded from the javafx folder)
$modules = 'java.base,java.desktop,java.prefs,java.net.http,java.logging,java.xml,java.scripting,' +
           'jdk.httpserver,jdk.xml.dom,jdk.unsupported,jdk.charsets,jdk.localedata,jdk.crypto.mscapi,jdk.zipfs'

$type = $Type
$out = Join-Path $here 'dist'
New-Item -ItemType Directory $out -Force | Out-Null

$common = @(
    '--type', $type,
    '--name', 'Panel Controller',
    '--app-version', $version,
    '--vendor', 'Samet Safak',
    '--description', 'PlatinMarket magazalari icin yayin, yedek ve kontrol araci',
    '--copyright', 'Samet Safak, Apache License 2.0',
    '--icon', "$here\docs\panel-controller.ico",
    '--input', "$stage\input",
    '--main-jar', 'panel-controller.jar',
    '--main-class', 'io.github.wreazycs.panelcontroller.gui.PanelApp',
    '--app-content', "$stage\javafx",
    '--add-modules', $modules,
    '--jlink-options', '--strip-debug --no-man-pages --no-header-files',
    '--java-options', '--enable-native-access=javafx.graphics,javafx.web,ALL-UNNAMED',
    '--java-options', '--module-path=$APPDIR\..\javafx',
    '--java-options', '--add-modules=javafx.controls,javafx.web',
    '--dest', $out
)
if (-not $AppImage) {
    $common += @(
        '--win-per-user-install',       # no administrator rights needed
        '--win-dir-chooser',
        '--win-menu', '--win-menu-group', 'Panel Controller',
        '--win-shortcut', '--win-shortcut-prompt',
        '--win-upgrade-uuid', '6f1c2b9e-3d7a-4c55-9a1e-2b8f0c4d7e61',   # fixed: new versions replace old ones
        '--about-url', 'https://github.com/wreazy-cs/panel-controller',
        '--license-file', "$here\LICENSE"
    )
}

& $jpackage @common
if ($LASTEXITCODE -ne 0) { throw "jpackage failed ($LASTEXITCODE)" }
Write-Host "Done: $out"
Get-ChildItem $out | Format-Table Name, Length -AutoSize
