# Sentinel Mail App

A dedicated native Android mail app for **Sentinel Mail** — Sentinel
Safeguard's self-hosted mail server (Postfix + Dovecot on
`mail.sentinelsafeguard.net`). The second app in the Sentinel
phone-app family, after the Command App.

The app talks **directly** to the mail server over IMAP (993,
implicit TLS) and SMTP submission (587, STARTTLS required). There
is no intermediary service, no account system of ours, and no copy
of mail anywhere but the server and the phone's in-memory view.

- Package: `net.sentinelsafeguard.mailapp`
- Launcher label: **Sentinel Mail**
- Release signer (SHA-256, the family custody key):
  `e30cf3915a9a8774025d04bf3625748219ca4b9ea67bf5779cbca0a8b1f1e28d`

## What it does (v1)

- Sign-in with a full Sentinel Mail address + password; server
  settings preconfigured to the verified Sentinel Mail values,
  editable under Advanced for other mailboxes on the same server.
- Folder tree (Inbox, Sent, Drafts, Archive, Trash, Junk + server
  folders, and the `Shared.sentinel.*` business folders), message
  lists with snippets, pull-to-refresh, server-side search.
- Read view: plain text by default (the family's audited
  rendering); an optional per-message formatted view through a
  whitelist HTML sanitizer in a locked-down WebView — no
  JavaScript, remote images blocked until you ask per message.
- Compose / reply / reply-all / forward with attachments (25 MB
  total, the server's own ceiling), IMAP drafts that sync with
  webmail, sends filed in Sent.
- Swipe archive/delete with undo, move-to-folder, read/unread and
  star toggles, attachment download/open.
- Periodic new-mail checks (15 minutes by default, adjustable,
  or manual-only) with notifications that open the message. This
  is periodic checking — it is never called "push", because it
  isn't.
- App lock (biometrics / device credential / app PIN), with the
  password held only in Keystore-backed encrypted storage.
- One account in v1, done excellently. The account seam
  (`AccountRepository`) is shaped so multi-account later is
  additive — see BUILDING.md.

## Provenance

The mail engine is ported from the Command App's shipped,
audited mail module (direct IMAP/SMTP, SPKI anchor pinning,
shared-folder identity rule) and extended for a mail-first app:
full compose, IMAP drafts, snippets, stars, arbitrary moves.
The icon package is generated from the owner's real Sentinel
Safeguard logo — byte-identical to the Command App's pinned
package, and pinned again here by `LauncherIconTest`.

## Building / releasing

See BUILDING.md. Security posture: SECURITY.md and HARDENING.md.
