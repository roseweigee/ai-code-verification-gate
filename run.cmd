@echo off
rem Builds with the plain JDK (no dependency download) and runs the CLI. Requires JDK 17+.
cd /d "%~dp0"
if not exist build\classes mkdir build\classes
dir /s /b src\main\java\*.java > build\sources.txt
javac -d build\classes --release 17 @build\sources.txt || exit /b 1
java -cp build\classes -Daiverify.root=. dev.aiverify.Main %*
