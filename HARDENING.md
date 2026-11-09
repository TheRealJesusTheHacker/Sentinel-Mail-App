# Hardening — Sentinel Mail App

The deliberate security decisions, and why.

## SPKI anchor pinning (mail host)

Ported unchanged from the Command App's audited mail module —
same server, same anchors.

The pins are the two CA anchors in the chain
`mail.sentinelsafeguard.net` actually presents, computed from
certificates fetched from the live server on 2026-10-25 and
**re-verified byte-for-byte against the live chain on 2026-11-09**
during this app's build:

- leaf CN=mail.sentinelsafeguard.net (renews ~90 days — NOT pinned)
  ← YE2 intermediate (rotates by design — NOT pinned)
  ← **ISRG Root YE** — pinned: `sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=`
  ← **ISRG Root X2** (cross-signed) — pinned: `diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=`

Anchor pinning means leaf renewals and intermediate rotations
change nothing: any future chain that still runs through either
root key matches, and each root backs up the other.

Validation order is **chain first, pin second**
(`SpkiPinningTrustManager`): the platform trust manager must
accept the chain before the pin is even consulted. A pin can
only make validation stricter. This class is the replacement for
the Jakarta `mail.*.ssl` trust property an early Command App
version set — that property replaced chain validation with blind
trust for the listed host, the opposite of pinning. It is gone
and a unit test grep-pins its absence (the token `ssl.trust`
itself) across the app sources.

**Rotation procedure** (if Let's Encrypt ever retires these
roots): fetch the live chain, compute the new roots' SPKI pins,
ship an app update carrying old + new pins together, then drop
the old pins in the following release. Never ship a build whose
pins don't match a chain the server can present.

## Server settings, verified live (2026-11-09)

Verified from the host itself, handshake/banner only — no auth
attempts were made:

- 993: Dovecot ready banner; TLS 1.3; cert CN=mail.sentinelsafeguard.net,
  Let's Encrypt YE2, valid to 2027-01-22.
- 587: Postfix banner; STARTTLS offered before auth; AUTH PLAIN
  LOGIN only after TLS; `SIZE 26214400` (the 25 MB ceiling the
  compose screen enforces).
- 465: implicit TLS submission, same certificate.
- 143: **not listening** — plaintext IMAP is disabled server-side,
  confirmed.

## The WebView cage

One WebView exists (the per-message formatted view). Its settings
are fixed at construction: JavaScript disabled, file access
disabled, content access disabled, DOM storage and database
disabled, no cache. Remote images are blocked
(`blockNetworkImage`) until the reader taps "Load remote images"
for that message; the toggle is per-message state, never a
preference. Content reaches it only through `HtmlSanitizer`.

## Backup exclusion

The encrypted credential store file
(`sentinel_mail_secure_store.xml`) is excluded from cloud backup
and device transfer (`res/xml/data_extraction_rules.xml`,
`res/xml/backup_rules.xml`). Its ciphertext is Keystore-bound
besides, but exclusion means it never leaves the device at all.

## R8 rules

`app/proguard-rules.pro` is deliberately enumerated: the
WorkManager worker (reflective instantiation, name persisted),
the Angus Mail service-loader entry points, and Log d/v
stripping. No blanket keeps. The Command App's serialization
rules are absent here because this app has no @Serializable wire
models.
