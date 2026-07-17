@echo off
rem Builds CC:TV with JDK 17 regardless of the Java version on PATH.
rem Usage: build17 [gradle tasks...]   (defaults to "build")
rem Override the JDK location by setting JAVA17_HOME before running.
setlocal

if "%JAVA17_HOME%"=="" set "JAVA17_HOME=C:\Program Files\Java\jdk-17"

if not exist "%JAVA17_HOME%\bin\java.exe" (
    echo [build17] No JDK found at "%JAVA17_HOME%".
    echo [build17] Install JDK 17 or set JAVA17_HOME to its install directory.
    exit /b 1
)

set "JAVA_HOME=%JAVA17_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo [build17] Using JAVA_HOME=%JAVA_HOME%
"%JAVA_HOME%\bin\java.exe" -version

set "TASKS=%*"
if "%TASKS%"=="" set "TASKS=build"

call "%~dp0gradlew.bat" %TASKS%
set "RESULT=%ERRORLEVEL%"

if "%RESULT%"=="0" (
    echo [build17] Build OK. Jars in build\libs:
    dir /b "%~dp0build\libs"
) else (
    echo [build17] Build FAILED with exit code %RESULT%.
)
exit /b %RESULT%
