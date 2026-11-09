# Building — Sentinel Mail App

## Requirements

- JDK 17
- Android SDK platform 35, build-tools 35.0.0
- Gradle wrapper (in-repo); dependencies are hash-verified
  against `gradle/verification-metadata.xml` — a build whose
  downloads don't match the locked hashes fails, by design.

## Everyday builds

```
./gradlew :app:testDebugUnitTest   # the unit suites
./gradlew assembleDebug            # debug build
./gradlew assembleRelease          # UNSIGNED compile proof only
```

An unsigned release is a compile proof, never a deliverable. The
push gate (`.github/workflows/android.yml`) runs the tests and
both builds on every push and mechanically proves the release
output stays unsigned.

## Signed releases

Signing material comes only from the environment:

```
SENTINEL_APP_KEYSTORE            path to the owner's PKCS12 keystore
SENTINEL_APP_KEYSTORE_PASSWORD
SENTINEL_APP_KEY_ALIAS
SENTINEL_APP_KEY_PASSWORD
```

All four set → `./gradlew signedRelease` builds the signed APK.
Some set → configuration fails (a half-set signing setup is a
mistake, not a mode). None set → release stays unsigned and the
`signedRelease` task refuses to run. There is no fallback key.

In CI, `.github/workflows/signed-release.yml` (manual dispatch)
runs the test gate, materialises the keystore from the `release`
environment's secrets, builds, then verifies with apksigner:
debug certificate rejected, signer SHA-256 asserted equal to the
family fingerprint in README.md.

The R8 mapping file from a release build is archived as a CI
artifact. It is the deobfuscation key for release crash reports —
never commit it.

## The multi-account seam

V1 ships one active account. Everything asks
`data/AccountRepository` for the active account and a ready
`MailService`; no screen or worker touches the stores directly.
Multi-account later means: a list behind that same interface, a
per-account `SecureStore` namespace, and a switcher that changes
what `activeAccount()` returns. Nothing in the engine or UI keys
on "there is only one", so the work is additive.

## Layout

```
app/src/main/java/net/sentinelsafeguard/mailapp/
  config/     MailConfig, CertificatePins (SPKI anchors)
  data/       SecureStore, SettingsStore, AccountRepository
  data/mail/  the engine: MailService, MailTls, pinning, rules,
              text, HTML sanitizer
  notify/     Notifier, NewMailWorker (periodic checking)
  ui/         Compose screens + theme (Emerald Depth)
```
