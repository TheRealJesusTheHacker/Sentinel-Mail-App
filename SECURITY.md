# Security — Sentinel Mail App

Sentinel Mail App handles a mailbox password and the contents of
a mailbox. Its posture, in plain terms:

## Transport

- IMAP 993 (implicit TLS) and SMTP 587 (STARTTLS required) only.
  Port 143 is disabled on the server and unused here.
- TLS is strict everywhere: platform chain validation **plus**
  SPKI anchor pinning to the two Let's Encrypt root anchors the
  server actually chains to (see HARDENING.md). A pin can only
  make validation stricter, never weaker.
- No cleartext anywhere: `usesCleartextTraffic="false"`, no
  trust-all trust manager, no hostname-verifier bypass, no
  Jakarta `ssl.trust` property. A unit test
  (`MailConfigTest.noCleartextEscapeHatchesAnywhereInTheAppSources`)
  grep-pins the absence of those escape hatches in the sources
  and the manifest.
- The one plaintext code path (`MailConfig.rehearsalLoopback`)
  exists only so a separate, never-shipped rehearsal harness can
  drive this engine against a local fake server on loopback. No
  app code references it, and a unit test pins the production
  defaults (host, ports, `tlsRequired = true`).

## Credentials and local data

- The mailbox password lives only in Android Keystore-backed
  encrypted storage (`SecureStore`), excluded from cloud backup
  and device transfer by the extraction rules.
- The app-lock PIN is never stored — only a salted SHA-256
  verifier of it.
- Nothing credential-shaped is ever logged; the R8 rules strip
  debug/verbose log calls from release builds regardless.
- FLAG_SECURE keeps mail and credentials off screenshots, the
  recents thumbnail, and screen recording. The app re-locks after
  about five minutes in the background when the lock is enabled.
- Mail bodies are held in memory only and dropped on sign-out;
  downloaded attachments go to the app-private cache and are
  shared out only through FileProvider grants when the user opens
  one.
- Sign-out wipes every secret on the device and cancels the
  periodic check. Mail itself stays on the server.

## HTML mail

Default rendering is plain text. The optional formatted view
renders the HTML part only after a whitelist sanitizer rebuild
(`HtmlSanitizer`, unit-tested): scripts, event handlers, styles,
frames, forms, and dangerous URL schemes cannot survive it. The
WebView itself runs with JavaScript disabled, no file/content
access, and remote images blocked until the reader loads them
for that one message.

## Signing

Release APKs are signed only with the owner's custodied key
(family signer SHA-256
`e30cf3915a9a8774025d04bf3625748219ca4b9ea67bf5779cbca0a8b1f1e28d`),
sealed into this repo's `release` environment. There is no
fallback key; a build without those secrets refuses to sign.

## Reporting

Security issues: `sentinel@sentinelsafeguard.net`.
