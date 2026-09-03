@echo off
REM Builds the mod with the JDK unpacked into toolchain\ by setup, so nothing has to be
REM installed system-wide. If you install a JDK 21 yourself, you can ignore this script
REM and just run "gradlew build".
setlocal
for /d %%D in ("%~dp0toolchain\jdk-*") do set "JAVA_HOME=%%D"
if not defined JAVA_HOME (
    echo No JDK found in toolchain\. Set JAVA_HOME to a JDK 21 and run gradlew build.
    exit /b 1
)
echo Using JDK: %JAVA_HOME%
call "%~dp0gradlew.bat" %*
