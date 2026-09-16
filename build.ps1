# Compila el servidor para Java 21. Uso: .\build.ps1
$ErrorActionPreference = "Stop"

if (Test-Path out) { Remove-Item -Recurse -Force out }

$files = Get-ChildItem -Recurse -Path server\src -Filter *.java | ForEach-Object { $_.FullName }
javac --release 21 -encoding UTF-8 -Xlint:all -d out $files

if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Compilacion OK -> out\"