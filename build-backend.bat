@echo off
chcp 65001 >nul
title Lion Agent Backend Build
echo Packaging backend...
echo.
cd /d "%~dp0"

rem Pin JDK 21 (pom.xml java.version=17, JDK 21 can compile release 17).
rem Change this path to switch JDK.
set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.1"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] java not found: %JAVA_HOME%\bin\java.exe
    echo Please install JDK 21 or fix JAVA_HOME in this script.
    pause
    exit /b 1
)

echo Using JAVA_HOME=%JAVA_HOME%
"%JAVA_HOME%\bin\java.exe" -version
echo.

rem maven.test.skip=true: skip test compile/run, and avoid offline surefire plugin resolution
call mvn clean package -Dmaven.test.skip=true
if errorlevel 1 (
    echo.
    echo [ERROR] Backend build FAILED.
    pause
    exit /b 1
)

echo.
echo Backend build completed. Output: target\lion-langchain4j-agent-0.0.1-SNAPSHOT.jar
echo.
pause
