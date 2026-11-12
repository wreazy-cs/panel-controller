@echo off
rem Starts the desktop app with a console that shows errors (for troubleshooting).
rem Everyday use: the desktop shortcut made by create-shortcut.ps1. Run "mvn package" once first.
cd /d "%~dp0"
if not exist target\lib\javafx (
    echo target\lib\javafx not found. Run: mvn package
    exit /b 1
)
java --enable-native-access=javafx.graphics,javafx.web,ALL-UNNAMED --module-path target\lib\javafx --add-modules javafx.controls,javafx.web -cp "target\panel-controller.jar;target\lib\cp\*" io.github.wreazycs.panelcontroller.gui.PanelApp %*
