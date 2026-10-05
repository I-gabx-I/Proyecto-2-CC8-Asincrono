@echo off
rem Limpia lo generado:
rem   clean.bat          borra los compilados (out\ y tools\out\)
rem   clean.bat tiles    ademas borra TODAS las piramides de data\tiles\ (pide confirmacion)
rem Nunca toca data\input\ (las imagenes originales).

if exist out rmdir /s /q out
if exist tools\out rmdir /s /q tools\out
echo Compilados borrados.

if /i not "%~1"=="tiles" exit /b 0
if not exist data\tiles (
    echo No hay piramides que borrar.
    exit /b 0
)

echo.
echo Se van a borrar TODAS las piramides de data\tiles\:
dir /b data\tiles
echo Regenerar la de 93 GB puede tardar horas.
set /p ok=Escribe SI para confirmar: 
if /i not "%ok%"=="SI" (
    echo Cancelado.
    exit /b 0
)
rmdir /s /q data\tiles
echo Piramides borradas.