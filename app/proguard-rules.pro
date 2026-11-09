# Sentinel Mail App — R8 rules for release builds.
#
# Every rule here is DELIBERATE: it names the exact reflection or
# name-based lookup that would break without it. There is no blanket
# "-keep class **" and no rule kept "just in case". If a rule's reason
# stops being true, delete the rule with the feature that needed it.
# (Adapted from the Command App's audited ruleset — same libraries,
# same reasoning; the Command-App-only serialization rules do not
# apply here because this app has no @Serializable wire models.)
#
# Manifest-declared components (MainActivity, MailApplication, the
# FileProvider) are kept by the AAPT rules AGP feeds to R8
# automatically — they are not repeated here.

# ---------------------------------------------------------------------
# WorkManager — the periodic new-mail check worker.
#
# WorkManager instantiates the worker reflectively from its class
# NAME, and that name is persisted in WorkManager's own database, so
# it must survive both stripping and renaming across app updates —
# a renamed worker silently orphans already-scheduled checks.
# ---------------------------------------------------------------------
-keep class net.sentinelsafeguard.mailapp.notify.NewMailWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ---------------------------------------------------------------------
# Eclipse Angus Mail — the mail stack discovers almost everything by
# class name, from resource files inside its own jars:
#
#   META-INF/services/jakarta.mail.Provider        (ServiceLoader)
#     -> the six Provider classes below
#   META-INF/javamail.default.providers            (Session)
#     -> the Store/Transport classes below, built via their
#        (Session, URLName) constructors
#   META-INF/mailcap                               (DataHandler)
#     -> the content-handler classes below
#   META-INF/services/jakarta.mail.util.StreamProvider
#   META-INF/services/jakarta.activation.spi.*     (angus-activation)
#
# Only those entry points need keeps; everything they use internally
# is reached by ordinary bytecode references, which R8 traces itself.
# Class lists verified against the shipped angus-mail 2.0.3 /
# angus-activation 2.0.2 jars when this file was written.
# ---------------------------------------------------------------------
-keep class org.eclipse.angus.mail.imap.IMAPProvider { <init>(); }
-keep class org.eclipse.angus.mail.imap.IMAPSSLProvider { <init>(); }
-keep class org.eclipse.angus.mail.smtp.SMTPProvider { <init>(); }
-keep class org.eclipse.angus.mail.smtp.SMTPSSLProvider { <init>(); }
-keep class org.eclipse.angus.mail.pop3.POP3Provider { <init>(); }
-keep class org.eclipse.angus.mail.pop3.POP3SSLProvider { <init>(); }

-keep class org.eclipse.angus.mail.imap.IMAPStore {
    <init>(jakarta.mail.Session, jakarta.mail.URLName);
}
-keep class org.eclipse.angus.mail.imap.IMAPSSLStore {
    <init>(jakarta.mail.Session, jakarta.mail.URLName);
}
-keep class org.eclipse.angus.mail.smtp.SMTPTransport {
    <init>(jakarta.mail.Session, jakarta.mail.URLName);
}
-keep class org.eclipse.angus.mail.smtp.SMTPSSLTransport {
    <init>(jakarta.mail.Session, jakarta.mail.URLName);
}

-keep class org.eclipse.angus.mail.handlers.text_plain { <init>(); }
-keep class org.eclipse.angus.mail.handlers.text_html { <init>(); }
-keep class org.eclipse.angus.mail.handlers.text_xml { <init>(); }
-keep class org.eclipse.angus.mail.handlers.multipart_mixed { <init>(); }
-keep class org.eclipse.angus.mail.handlers.message_rfc822 { <init>(); }

-keep class org.eclipse.angus.mail.util.MailStreamProvider { <init>(); }
-keep class org.eclipse.angus.activation.MailcapRegistryProviderImpl { <init>(); }
-keep class org.eclipse.angus.activation.MimeTypeRegistryProviderImpl { <init>(); }

# ---------------------------------------------------------------------
# Log stripping — debug/verbose log calls never survive into a
# release build. There are no Log calls in the app today (verified
# by source search when this rule was added, and the mail layer is
# written to never log credentials); this rule pins that
# mechanically so a future Log.d/Log.v cannot ship. Info/warn/error
# are left alone deliberately — they are the levels a crash-adjacent
# diagnostic would use, and none exist today either.
# ---------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# ---------------------------------------------------------------------
# R8 missing-class warnings — compile-time-only annotations and
# desktop-Java APIs that Android does not ship. Neither is ever
# loaded at runtime on Android; both are referenced (only) from
# library bytecode, which is exactly what -dontwarn is for. Named
# exactly, nothing broader:
#
#   com.google.errorprone.annotations.** — Error Prone's
#     compile-time-only annotations, referenced by Tink (via
#     androidx.security:security-crypto). Tink's own consumer
#     rules prescribe this dontwarn.
#   javax.security.auth.callback.** / javax.security.sasl.** —
#     desktop Java SASL APIs referenced by Angus Mail's SASL
#     authenticators. Android has no javax.security.sasl; the app
#     authenticates with PLAIN over TLS, never those code paths.
# ---------------------------------------------------------------------
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.security.auth.callback.**
-dontwarn javax.security.sasl.**
