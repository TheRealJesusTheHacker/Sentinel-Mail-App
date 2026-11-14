# ProGuard rules for the desktop RELEASE build (added 2026-11-14 for
# the Windows CI build). The Compose plugin's default release
# configuration aborts on unresolved references; the classes below
# are OPTIONAL integrations referenced by libraries in the graph
# (platform hooks that are absent on a desktop JVM by design — the
# same graph shipped in the Linux .deb). -dontwarn only silences
# the check for those absent optional classes; nothing is kept,
# removed, or obfuscated differently because of this file.
-dontwarn org.graalvm.nativeimage.hosted.**
