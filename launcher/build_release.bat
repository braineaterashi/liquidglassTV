@echo off
set "JAVA_HOME=C:\Users\brain\.gemini\antigravity\scratch\jdk17\jdk-17.0.12+7"
set "ANDROID_HOME=C:\Users\brain\AppData\Local\Android\Sdk"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "C:\Users\brain\.gemini\antigravity\scratch\cleanyourtv\launcher"
call gradlew.bat assembleRelease
