# CarLauncher FYT

Launcher Android natif ultra-léger conçu spécifiquement pour les autoradios chinois sur plateforme **FYT (Unisoc UIS8141E, 1 Go RAM, 1024x600 paysage, Android 8.1 / API 27)**.

## Caractéristiques techniques
- **Une seule dépendance** : osmdroid (carte OpenStreetMap, rendue en mode sombre). Pas d’AndroidX, pas de WebView.
- **Compatibilité** : `minSdk 24`, `targetSdk 33`, orientation paysage verrouillée.
- **Vrai Launcher Android** : Déclaré avec `CATEGORY_HOME` + `CATEGORY_DEFAULT` (`launchMode="singleTask"`).
- **Horloge & date en français** mises à jour en temps réel (`ACTION_TIME_TICK`).
- **Lecteur intégré** : titre, pochette, progression, bouton lecture/pause qui reflète l’état réel. « Lecture » démarre l’app musique choisie (Apple Music, Spotify, musique Bluetooth…) sans l’ouvrir à la main.
- **Son des touches** doux, réglable (activer, volume, adaptation au volume de la musique) dans Réglages (engrenage du dock).
- **Démarrage automatique** au contact (application d’accueil + réception de `BOOT_COMPLETED`).
- **Association par appui long** : Un clic normal ouvre l'application associée ; un appui long sur Navigation, Musique ou Téléphone ouvre la liste des applications installées (sauvegarde dans `SharedPreferences`).

## Compiler l'APK automatiquement sur GitHub (sans Android Studio)
1. Créez un nouveau dépôt sur GitHub et poussez tous les fichiers de ce dossier à la racine du dépôt (y compris le dossier `.github/workflows/build.yml`).
2. Allez dans l'onglet **Actions** de votre dépôt GitHub.
3. Le workflow **Build CarLauncherFYT APK** se lance automatiquement (ou cliquez sur *Run workflow*).
4. Une fois terminé, téléchargez l'artefact **`CarLauncherFYT-apk`** contenant `CarLauncherFYT.apk`.
5. Copiez `CarLauncherFYT.apk` sur une clé USB, installez-le sur l'autoradio et sélectionnez **CarLauncher FYT** comme application d'accueil par défaut dans *Paramètres Android > Applications > Applications par défaut > Application d'accueil*.

## Aperçu
Maquettes 1024x600 : `docs/preview/` (`home.png`, `drawer.png`, `settings.png`, `picker.png`). Source : `docs/preview/home.html` (ouvrir avec `#drawer`, `#settings` ou `#picker`).
