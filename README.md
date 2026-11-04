# Diva Touch – APK builder

The game files are stored encrypted in `game.tar.gz.enc`. The build unlocks them with the `GAME_PASSWORD` repository secret, so they can't be read from this public repo.

1. Actions tab -> "Build APK" -> **Run workflow** (it also runs on every push to `main`).
2. Download `DivaTouch-apk` from the finished run and unzip it to get `DivaTouch.apk`.

Note: Actions artifacts of a public repo can be downloaded by anyone who is signed in to GitHub, for as long as they are kept (1 day here), so download it soon after the build.
