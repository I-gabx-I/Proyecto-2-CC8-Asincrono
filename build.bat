@echo off
if exist out rmdir /s /q out

javac --release 21 -encoding UTF-8 -d out --source-path src src\pimg\Main.java src\pimg\ingest\IngestMain.java
if errorlevel 1 (
    echo.
    echo ERROR: la compilacion fallo
    exit /b 1
)
echo Compilacion OK