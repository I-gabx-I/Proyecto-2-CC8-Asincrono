@echo off
rem Compila TODO el codigo: src\ (servidor e ingesta) en out\ y tools\ en tools\out\
rem Siempre empieza de cero para no acumular .class viejos.
setlocal enabledelayedexpansion

if exist out rmdir /s /q out
if exist tools\out rmdir /s /q tools\out
mkdir out
mkdir tools\out

rem Lista de fuentes entre comillas y con / (la ruta del proyecto tiene espacios)
(for /r src %%f in (*.java) do (
    set "p=%%f"
    echo "!p:\=/!"
)) > out\fuentes.txt

javac --release 21 -encoding UTF-8 -d out @out\fuentes.txt
if errorlevel 1 goto error

(for %%f in (tools\*.java) do (
    set "p=%%~ff"
    echo "!p:\=/!"
)) > tools\out\fuentes.txt

javac --release 21 -encoding UTF-8 -cp out -d tools\out @tools\out\fuentes.txt
if errorlevel 1 goto error

del out\fuentes.txt tools\out\fuentes.txt
echo Compilacion OK
exit /b 0

:error
echo.
echo ERROR: la compilacion fallo
exit /b 1