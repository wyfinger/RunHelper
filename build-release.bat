@echo off
setlocal EnableExtensions
cd /d "%~dp0"

if not defined JAVA_HOME if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"

call "%~dp0gradlew.bat" --console=plain assembleRelease
set "BUILD_EXIT_CODE=%ERRORLEVEL%"

if "%BUILD_EXIT_CODE%"=="0" (
    echo.
    echo Unsigned release APK created:
    echo %~dp0app\build\outputs\apk\release\app-release-unsigned.apk
    echo Sign it before distributing or installing it.
) else (
    echo.
    echo Release build failed with exit code %BUILD_EXIT_CODE%.
)

exit /b %BUILD_EXIT_CODE%

