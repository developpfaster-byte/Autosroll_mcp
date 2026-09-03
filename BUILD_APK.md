# Guide de Compilation de l'APK (Android 8.0+)

Ce projet est configuré avec un `minSdk = 24` (Android 7.0), ce qui garantit une **compatibilité complète avec Android 8.0 (API 26), 8.1 (API 27) et toutes les versions supérieures (Android 9, 10, 11, 12, 13, 14, 15 et 16)**.

---

### Méthode 1 : Avec le script automatique (Recommandé)

#### Sur Linux / macOS / WSL :
```bash
# Compilation de l'APK de développement (Debug)
./build_apk.sh

# Ou pour la version Release :
./build_apk.sh release
```

#### Sur Windows :
Double-cliquez simplement sur `build_apk.bat` ou lancez en ligne de commande :
```cmd
build_apk.bat
```

Le script s'occupe de :
1. Détecter automatiquement `gradle` ou le wrapper `gradlew`.
2. Lancer la tâche Gradle appropriée (`assembleDebug`).
3. Vérifier et afficher l'emplacement exact ainsi que la taille de l'APK généré.

---

### Méthode 2 : Directement avec Gradle

Si vous préférez exécuter la commande Gradle standard :

```bash
# Compilation de l'APK Debug pour le développement
gradle assembleDebug

# Ou avec le wrapper si vous l'utilisez :
./gradlew assembleDebug
```

---

### Emplacement de l'APK généré

Une fois la compilation terminée, votre fichier APK se trouve ici :
```
app/build/outputs/apk/debug/app-debug.apk
```

---

### Installation sur votre appareil Android 8+

#### Via le câble USB (ADB) :
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

#### Sans ordinateur :
Vous pouvez copier `app-debug.apk` sur votre téléphone (Google Drive, Telegram, e-mail, clé USB) et cliquer dessus pour l'installer (veillez à autoriser l'installation d'applications de sources inconnues dans les paramètres de sécurité de votre appareil).

---

### Spécifications Techniques
- **Application ID** : `com.aistudio.screenreader.mcp`
- **SDK Minimal (minSdk)** : `24` (Compatible Android 7.0, 8.0+, 9, 10, 11, 12, 13, 14, 15, 16)
- **SDK Cible (targetSdk / compileSdk)** : `36`
- **Permissions requises** : Service d'Accessibilité, Superposition d'écran (`SYSTEM_ALERT_WINDOW`), Accès Réseau (`INTERNET`).
