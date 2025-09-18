@echo off
setlocal EnableExtensions DisableDelayedExpansion

if not "%~2"=="" goto usage
pushd "%~dp0" || exit /b 1

if /i "%~1"=="-Test" goto test

set "pulsePort=8080"
if not "%~1"=="" set "pulsePort=%~1"
javac --release 17 -encoding UTF-8 -d out src\main\java\dev\qapulse\*.java
if errorlevel 1 goto done
java "-Dfile.encoding=UTF-8" -cp out dev.qapulse.PulseServer "%pulsePort%" .
goto done

:test
javac --release 17 -encoding UTF-8 -d out src\main\java\dev\qapulse\*.java src\test\java\dev\qapulse\*.java
if errorlevel 1 goto done
java "-Dfile.encoding=UTF-8" -cp out dev.qapulse.PulseTest .

:done
set "pulseResult=%errorlevel%"
popd
exit /b %pulseResult%

:usage
echo Usage: run.cmd [port] or run.cmd -Test
exit /b 2
