# Test certificates
Captured 2026-11-13 from the production server's own presented
chain (openssl s_client against 127.0.0.1:993 on the mail host):
leaf CN=mail.sentinelsafeguard.net <- YE2 <- Root YE <- ISRG Root X2.
Used by TlsPinningTest to prove the desktop build enforces the
same SPKI pins as Android (CertificatePins.MAIL_PINS) against the
real chain. The leaf is NOT pinned by design (it renews); the
intermediate/root pins are what the assertions exercise.
