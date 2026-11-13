# Sentinel Mail — Desktop edition

The desktop port of the Sentinel Mail Android app (desktop port
 #2 of the Sentinel desktop-apps program, 2026-11-13). It is the
SAME product, not a rewrite: this standalone Gradle build compiles
the shared sources directly from `../app/src/main/java` and adds
only the desktop platform layer (`src/main/kotlin`) behind the
seams the Android app was refactored to expose
(`ui/Platform.kt`, `data/AppStores.kt`).

- Kotlin 2.0.20 + Compose Multiplatform 1.7.3 (the program
  standard, per the Academy pipeline proof).
- Mail engine: the same Eclipse Angus Mail stack; TLS is the
  app's own SPKI-pinned layer (`MailTls` +
  `SpkiPinningTrustManager` + `CertificatePins`), enforced
  identically on desktop — proven by `TlsPinningTest` against the
  production server's real presented chain.
- Credentials: `DesktopStore` chain — DPAPI on Windows (JNA),
  libsecret Secret Service on Linux (JNA, runtime-probed),
  AES-256-GCM encrypted-file fallback (0600 key file, per-user
  config dir). The active posture is labeled in Settings/About
  in both locales; nothing claims OS storage when the fallback
  is what is running.
- Strings: generated at build time from the SAME
  `values/strings.xml` + `values-es/strings.xml` as Android
  (262 keys × 2); the system locale picks the table.

Honest desktop dispositions (no dead controls):
- Unlock is PIN only — no biometric row exists on desktop.
- No system notifications: new mail surfaces as in-app unread
  counts, and the Settings notifications section says exactly
  that. Checks run on the same interval, only while the app is
  running; snoozed messages are re-evaluated at start and on
  each tick.
- Formatted mail renders natively: the phone's WebView surface
  is replaced by a Compose rendering of the sanitized block
  model (`ui/HtmlBlocks.kt`). Remote images stay blocked by
  default — an http(s) image is fetched only after the reader
  loads images for that message; the message's own inline
  images render from its bytes.
- Compose has no camera attach on desktop (row absent);
  attachments use the native file dialogs.

Build (Linux .deb):

```
./gradlew :packageDeb --offline   # after deps resolve once
```

Dependencies verify against `gradle/verification-metadata.xml`
(exported from this build's own resolved graph, the Academy
pattern); the Android tree's metadata and test suite are the
hard gate — the port ships only when both are byte-identical
to the pre-port baseline. Windows packaging
(`packageMsi`/`packageExe`) is configured but was NOT built or
verified in this port (no Windows host); it is not claimed as
shipped.
