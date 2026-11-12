@echo off
rem Starts the desktop app. Run "mvn package" once first (it copies the JavaFX jars to target\lib).
cd /d "%~dp0"
if not exist target\lib (
    echo target\lib not found. Run: mvn package
    exit /b 1
)
java --enable-native-access=javafx.graphics --module-path target\lib --add-modules javafx.controls -cp target\panel-controller-0.1.0-SNAPSHOT.jar io.github.wreazycs.panelcontroller.gui.PanelApp %*
