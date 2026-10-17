@echo off
setlocal
set "VERSION=8.9"
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
set "BASE=%GRADLE_USER_HOME%\hamava-bootstrap"
set "DIST=%BASE%\gradle-%VERSION%"
if not exist "%DIST%\bin\gradle.bat" (
  if not exist "%BASE%" mkdir "%BASE%"
  echo Downloading Gradle %VERSION% (first build only)...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip' -OutFile '%BASE%\gradle-%VERSION%-bin.zip'; Expand-Archive -Force '%BASE%\gradle-%VERSION%-bin.zip' '%BASE%'; Remove-Item '%BASE%\gradle-%VERSION%-bin.zip'"
  if errorlevel 1 exit /b %errorlevel%
)
call "%DIST%\bin\gradle.bat" %*
exit /b %errorlevel%
