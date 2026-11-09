@echo off
setlocal EnableExtensions
cd /d "%~dp0"

if not defined JAVA_HOME if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"

call "%~dp0gradlew.bat" --console=plain assembleDebug
set "BUILD_EXIT_CODE=%ERRORLEVEL%"

if "%BUILD_EXIT_CODE%"=="0" (
    echo.
    echo Debug APK created:
    echo %~dp0app\build\outputs\apk\debug\app-debug.apk
) else (
    echo.
    echo Debug build failed with exit code %BUILD_EXIT_CODE%.
)

exit /b %BUILD_EXIT_CODE%

