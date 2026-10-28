# Releasing this fork

This fork publishes its own signed APKs on GitHub, separately from the Play Store and F-Droid builds by KDE. Pushing a tag that starts with `v` runs `.github/workflows/release.yml`, which does three things in order:

1. Runs the unit tests and builds the release APK, minified and signed with the fork's release key.
2. Verifies the APK signature with `apksigner`.
3. Creates the GitHub release with generated notes and attaches the APK and its `.sha256` file.

The desktop app lives in the separate `kdeconnect-kde` repository and is released from there with its own tag. See `RELEASING.md` in that repository.

## Version and tag

The version comes from `versionName` and `versionCode` in `build.gradle.kts`. Tags use `versionName` with a `-fork.N` suffix, so fork releases never collide with upstream tags. N starts at 1 and increases for each release of the same upstream version.

Android only installs an update over an existing install when `versionCode` is higher. Releases that keep the same upstream `versionCode` cannot be installed over each other. Uninstall the old build first, or raise `versionCode` in a commit before tagging.

## Signing key

The release key is a PKCS12 keystore with the alias `kdeconnect`. It is stored in three repository secrets:

| Secret | Content |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | the keystore file, base64 encoded |
| `ANDROID_KEYSTORE_PASSWORD` | the store password, which is also the key password |
| `ANDROID_KEY_ALIAS` | `kdeconnect` |

The certificate SHA-256 fingerprint is `79:39:B1:F3:24:C8:3A:E0:11:88:71:7E:4B:85:06:F3:B4:0A:D5:6E:F5:CD:42:5D:F9:ED:72:FD:FA:E8:32:D5`.

Keep an offline backup of the keystore and its password. Android refuses to install an update signed with a different key, so losing the key means every user has to uninstall and reinstall.

The build reads the key from environment variables. `build.gradle.kts` only creates the `release` signing config when `KDECONNECT_RELEASE_KEYSTORE` is set, so debug builds and unsigned local release builds keep working without it.

| Variable | Meaning |
|---|---|
| `KDECONNECT_RELEASE_KEYSTORE` | path to the keystore file |
| `KDECONNECT_RELEASE_KEYSTORE_PASSWORD` | store password |
| `KDECONNECT_RELEASE_KEY_ALIAS` | key alias |
| `KDECONNECT_RELEASE_KEY_PASSWORD` | key password, defaults to the store password |

To rotate the key, generate a new keystore and replace the secrets:

```sh
keytool -genkeypair -keystore release.jks -storetype PKCS12 -alias kdeconnect \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=meatybot KDE Connect fork"
base64 -w0 release.jks | gh secret set ANDROID_KEYSTORE_BASE64 -R meatybot/kdeconnect-android
gh secret set ANDROID_KEYSTORE_PASSWORD -R meatybot/kdeconnect-android
printf kdeconnect | gh secret set ANDROID_KEY_ALIAS -R meatybot/kdeconnect-android
```

Rotating the key has the same effect as losing it: existing installs cannot update.

## Making a release

1. Make sure `master` is green in the CI workflow on GitHub.
2. Tag the commit and push the tag:

   ```sh
   git checkout master && git pull
   git tag -a v1.37.0-fork.2 -m "Short summary of the release"
   git push origin v1.37.0-fork.2
   ```

3. Watch the run until it succeeds:

   ```sh
   gh run list -R meatybot/kdeconnect-android -w Release -L 1
   gh run watch <run-id> -R meatybot/kdeconnect-android --exit-status
   ```

4. Check the published APK:

   ```sh
   gh release download v1.37.0-fork.2 -R meatybot/kdeconnect-android -D /tmp/rel
   cd /tmp/rel && sha256sum -c *.sha256
   apksigner verify --print-certs *.apk
   ```

   The signer SHA-256 digest must match the fingerprint above.

The run takes about 5 minutes.

## Re-running a failed release

If the release job fails after the tag is pushed, fix the problem on `master` and choose one of two options:

- Rebuild the existing tag without moving it. Run the Release workflow from the Actions tab with **Run workflow** and enter the tag, or use `gh workflow run Release -R meatybot/kdeconnect-android -f tag=<tag>`. The publish step reuses an existing release and replaces its assets.
- Move the tag to the fixed commit with `git tag -f -a <tag> -m "..."` and `git push -f origin <tag>`. Only do this when nobody has downloaded the release yet. Otherwise, tag the next `-fork.N` instead.

## Building a signed APK locally

```sh
KDECONNECT_RELEASE_KEYSTORE=/path/to/release.jks \
KDECONNECT_RELEASE_KEYSTORE_PASSWORD=... \
KDECONNECT_RELEASE_KEY_ALIAS=kdeconnect \
./gradlew assembleRelease
```

The APK is written to `build/outputs/apk/release/kdeconnect-android-release.apk`. Without the variables, the same command writes an unsigned `kdeconnect-android-release-unsigned.apk`.

## Installing a release

The APK uses the official package id `org.kde.kdeconnect_tp` but a different signature. Uninstall KDE Connect from the Play Store or F-Droid first, otherwise Android rejects the install. Uninstalling removes existing pairings, so every desktop has to pair again.
