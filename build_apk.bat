@echo off
REM ==============================================================================
REM Script de compilation APK Windows - MCP Screen Reader (Android 8.0+)
REM ==============================================================================

echo ====================================================
echo    Compilation de l'APK - MCP Screen Reader
echo    Cible : Android 8.0+ (API 26+)
echo ====================================================

set GRADLE_CMD=gradle
where gradle >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    if exist "gradlew.bat" (
        set GRADLE_CMD=gradlew.bat
    ) else (
        echo [ERREUR] Ni 'gradle' ni 'gradlew.bat' n'ont ete trouves dans le PATH.
        echo Installez Gradle ou ajoutez-le aux variables d'environnement.
        pause
        exit /b 1
    )
)

echo [OK] Utilisation de : %GRADLE_CMD%

set BUILD_TYPE=debug
set GRADLE_TASK=assembleDebug

if "%1"=="release" (
    set BUILD_TYPE=release
    set GRADLE_TASK=assembleRelease
)
if "%1"=="--release" (
    set BUILD_TYPE=release
    set GRADLE_TASK=assembleRelease
)

echo Lancement de la compilation : %GRADLE_CMD% %GRADLE_TASK%
call %GRADLE_CMD% %GRADLE_TASK%

if %ERRORLEVEL% NEQ 0 (
    echo [ERREUR] Echec lors de la compilation de l'APK.
    pause
    exit /b %ERRORLEVEL%
)

echo ====================================================
echo [SUCCES] Compilation terminee !
echo L'APK se trouve dans : app\build\outputs\apk\debug\app-debug.apk
echo Compatible avec Android 8.0 et superieur.
echo Pour l'installer via ADB :
echo adb install -r app\build\outputs\apk\debug\app-debug.apk
echo ====================================================
pause
