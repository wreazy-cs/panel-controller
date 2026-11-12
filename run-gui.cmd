@echo off
rem Starts the desktop app. Run "mvn package" once first (it copies the libraries to target\lib).
cd /d "%~dp0"
if not exist target\lib\fx (
    echo target\lib\fx not found. Run: mvn package
    exit /b 1
)
java --enable-native-access=javafx.graphics,ALL-UNNAMED --module-path target\lib\fx --add-modules javafx.controls -cp "target\panel-controller-0.1.0-SNAPSHOT.jar;target\lib\cp\*" io.github.wreazycs.panelcontroller.gui.PanelApp %*
