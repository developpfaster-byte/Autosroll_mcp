#!/usr/bin/env bash
# ==============================================================================
# Script de compilation APK - MCP Screen Reader (Android 8.0+ / API 26+)
# Compatible Linux, macOS, WSL & environnements CI/CD
# ==============================================================================

set -e

# Couleurs pour l'affichage console
GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${CYAN}====================================================${NC}"
echo -e "${CYAN}   Compilation de l'APK - MCP Screen Reader         ${NC}"
echo -e "${CYAN}   Cible : Android 8.0+ (API 26+) jusqu'à Android 16 ${NC}"
echo -e "${CYAN}====================================================${NC}"

# 1. Détection de l'exécutable Gradle (gradle ou ./gradlew)
GRADLE_CMD=""
if command -v gradle &> /dev/null; then
    GRADLE_CMD="gradle"
elif [ -f "./gradlew" ]; then
    chmod +x ./gradlew
    GRADLE_CMD="./gradlew"
else
    echo -e "${RED}Erreur : Ni 'gradle' ni './gradlew' n'ont été trouvés.${NC}"
    echo "Veuillez installer Gradle ou générer le wrapper avec 'gradle wrapper'."
    exit 1
fi

echo -e "${GREEN}✓ Exécutable Gradle détecté : ${GRADLE_CMD}${NC}"

# 2. Détermination du mode de build (debug par défaut, release si argument passé)
BUILD_TYPE="debug"
GRADLE_TASK="assembleDebug"

if [ "$1" == "release" ] || [ "$1" == "--release" ]; then
    BUILD_TYPE="release"
    GRADLE_TASK="assembleRelease"
fi

echo -e "${CYAN}➔ Lancement de la tâche : ${GRADLE_CMD} ${GRADLE_TASK}${NC}"
echo -e "${CYAN}----------------------------------------------------${NC}"

# 3. Exécution de la compilation
$GRADLE_CMD $GRADLE_TASK

# 4. Vérification et affichage du résultat
APK_DEBUG_PATH="app/build/outputs/apk/debug/app-debug.apk"
APK_RELEASE_PATH="app/build/outputs/apk/release/app-release-unsigned.apk"

echo -e "${CYAN}----------------------------------------------------${NC}"
if [ "$BUILD_TYPE" == "debug" ] && [ -f "$APK_DEBUG_PATH" ]; then
    APK_SIZE=$(du -h "$APK_DEBUG_PATH" | cut -f1)
    echo -e "${GREEN}✓ SUCCÈS : APK de développement généré avec succès !${NC}"
    echo -e "${YELLOW}Emplacement : ${APK_DEBUG_PATH}${NC}"
    echo -e "${YELLOW}Taille : ${APK_SIZE}${NC}"
    echo -e "${CYAN}Compatibilité : Android 8.0+ (Oreo, Pie, 10, 11, 12, 13, 14, 15, 16)${NC}"
    echo ""
    echo "Pour installer sur votre appareil Android connecté via USB (ADB) :"
    echo -e "${GREEN}adb install -r ${APK_DEBUG_PATH}${NC}"
elif [ "$BUILD_TYPE" == "release" ] && [ -f "$APK_RELEASE_PATH" ]; then
    APK_SIZE=$(du -h "$APK_RELEASE_PATH" | cut -f1)
    echo -e "${GREEN}✓ SUCCÈS : APK Release généré !${NC}"
    echo -e "${YELLOW}Emplacement : ${APK_RELEASE_PATH}${NC}"
    echo -e "${YELLOW}Taille : ${APK_SIZE}${NC}"
else
    echo -e "${GREEN}✓ Compilation terminée.${NC}"
    echo "Consultez le dossier 'app/build/outputs/apk/' pour récupérer vos fichiers APK."
fi
echo -e "${CYAN}====================================================${NC}"
