# CarLauncher FYT

Launcher Android natif ultra-léger conçu spécifiquement pour les autoradios chinois sur plateforme **FYT (Unisoc UIS8141E, 1 Go RAM, 1024x600 paysage, Android 8.1 / API 27)**.

## Caractéristiques techniques
- **Zéro dépendance externe** : Pas d'AndroidX, pas de Jetpack Compose, pas de WebView. Empreinte RAM minimale (~10-15 Mo).
- **Compatibilité** : `minSdk 24`, `targetSdk 33`, orientation paysage verrouillée.
- **Vrai Launcher Android** : Déclaré avec `CATEGORY_HOME` + `CATEGORY_DEFAULT` (`launchMode="singleTask"`).
- **Horloge & date en français** mises à jour en temps réel (`ACTION_TIME_TICK`).
- **Contrôles audio Bluetooth directs** (`KEYCODE_MEDIA_PREVIOUS`, `KEYCODE_MEDIA_PLAY_PAUSE`, `KEYCODE_MEDIA_NEXT`) via `AudioManager.dispatchMediaKeyEvent`.
- **Association par appui long** : Un clic normal ouvre l'application associée ; un appui long sur Navigation, Musique ou Téléphone ouvre la liste des applications installées (sauvegarde dans `SharedPreferences`).

## Compiler l'APK automatiquement sur GitHub (sans Android Studio)
1. Créez un nouveau dépôt sur GitHub et poussez tous les fichiers de ce dossier à la racine du dépôt (y compris le dossier `.github/workflows/build.yml`).
2. Allez dans l'onglet **Actions** de votre dépôt GitHub.
3. Le workflow **Build CarLauncherFYT APK** se lance automatiquement (ou cliquez sur *Run workflow*).
4. Une fois terminé, téléchargez l'artefact **`CarLauncherFYT-debug-apk`** contenant `app-debug.apk`.
5. Copiez `app-debug.apk` sur une clé USB, installez-le sur l'autoradio et sélectionnez **CarLauncher FYT** comme application d'accueil par défaut dans *Paramètres Android > Applications > Applications par défaut > Application d'accueil*.
