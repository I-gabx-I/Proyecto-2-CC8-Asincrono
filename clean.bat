@echo off
rem   clean.bat               borra los compilados (out\ y tools\out\)
rem   clean.bat tiles <id>    borra la piramide de una imagen
rem   clean.bat tiles         borra TODAS las piramides (pide confirmacion)
rem Nunca toca data\input\ (las imagenes originales).

if /i "%~1"=="tiles" goto tiles

if exist out rmdir /s /q out
if exist tools\out rmdir /s /q tools\out
echo Compilados borrados.
exit /b 0

:tiles
if not "%~2"=="" (
    if exist "data\tiles\%~2" (
        rmdir /s /q "data\tiles\%~2"
        echo Piramide %~2 borrada.
    ) else (
        echo No existe data\tiles\%~2
    )
    exit /b 0
)

if not exist data\tiles (
    echo No hay piramides que borrar.
    exit /b 0
)
echo Piramides en data\tiles\:
dir /b data\tiles
set /p ok=Escribe SI para borrarlas todas: 
if /i "%ok%"=="SI" (
    rmdir /s /q data\tiles
    echo Piramides borradas.
) else (
    echo Cancelado.
)