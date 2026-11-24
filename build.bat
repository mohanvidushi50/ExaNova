@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
where mvn >nul 2>&1
if not errorlevel 1 (
  call mvn -q compile
  exit /b !ERRORLEVEL!
)
if not exist "target\classes" mkdir "target\classes"
set SOURCES=
for /r src %%F in (*.java) do set SOURCES=!SOURCES! "%%F"
for /r web %%F in (*.java) do set SOURCES=!SOURCES! "%%F"
javac -encoding UTF-8 --release 21 --add-modules jdk.httpserver -d "target\classes" !SOURCES!
exit /b !ERRORLEVEL!