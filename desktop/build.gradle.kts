import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
    id("org.jetbrains.compose") version "1.7.3"
}

// ---------------------------------------------------------------------------
// Sentinel Mail Desktop 1.0.0 — Compose Multiplatform port of the
// Android app. This module compiles the SHARED sources directly from
// ../app/src/main/java (the whole mail engine — Eclipse Angus Mail is
// pure JVM — plus every UI screen) and adds only the desktop platform
// seams (Platform.kt, DesktopStores.kt, Main.kt, the native HTML
// block renderer). The Android module is a separate build and is
// untouched by this file. See desktop/README.md.
// ---------------------------------------------------------------------------

val sharedUiAndData = listOf(
    "config/CertificatePins.kt",
    "config/MailConfig.kt",
    "data/AppStores.kt",
    "data/AccountRepository.kt",
    "data/cache/CacheJson.kt",
    "data/cache/CachePolicy.kt",
    "data/mail/AddressBook.kt",
    "data/mail/HtmlSanitizer.kt",
    "data/mail/ImagePolicy.kt",
    "data/mail/InlineImages.kt",
    "data/mail/MailModels.kt",
    "data/mail/MailRules.kt",
    "data/mail/MailService.kt",
    "data/mail/MailText.kt",
    "data/mail/MailTls.kt",
    "data/mail/RecentSearches.kt",
    "data/mail/SearchMerge.kt",
    "data/mail/SignatureText.kt",
    "data/mail/SnoozeLogic.kt",
    "data/mail/SpkiPinningTrustManager.kt",
    "data/mail/Threading.kt",
    "notify/SnoozeProcessor.kt",
    "ui/AppRoot.kt",
    "ui/ComposeScreen.kt",
    "ui/FolderListScreen.kt",
    "ui/LockScreen.kt",
    "ui/MailViewModel.kt",
    "ui/MessageListScreen.kt",
    "ui/MessageReadScreen.kt",
    "ui/Motion.kt",
    "ui/MotionLogic.kt",
    "ui/PickedAttachment.kt",
    "ui/SavedTimeText.kt",
    "ui/SettingsScreen.kt",
    "ui/SignInScreen.kt",
    "ui/UndoSendLogic.kt",
    "ui/theme/Theme.kt",
)

sourceSets {
    main {
        kotlin {
            srcDir("src/main/kotlin")
            srcDir("../app/src/main/java/net/sentinelsafeguard/mailapp")
            // Include patterns are relative to EACH srcDir root:
            // the shared list selects files under the mailapp
            // package dir; the net/** pattern selects this module's
            // own sources (and the generated R/DesktopStrings).
            include(sharedUiAndData + "net/sentinelsafeguard/mailapp/**/*.kt")
        }
    }
}

// --- Generated R + string tables, from the SAME strings.xml files ---
// The desktop R class carries the same key->id shape the shared UI
// sources compile against on Android, and DesktopStrings holds the
// EN/ES copy parsed from app/src/main/res/values{,-es}/strings.xml
// at build time — the desktop copy cannot drift from the phone's.
val generateDesktopStrings = tasks.register("generateDesktopStrings") {
    val enFile = file("../app/src/main/res/values/strings.xml")
    val esFile = file("../app/src/main/res/values-es/strings.xml")
    val outDir = layout.buildDirectory.dir("generated/desktopStrings")
    inputs.files(enFile, esFile)
    outputs.dir(outDir)
    doLast {
        // Android string escapes (\', \", \\, \n, \t) are
        // resolved by aapt on the phone; the DOM keeps them raw, so
        // unescape here first — the desktop copy must read exactly
        // what the phone renders (Mail's strings use \' heavily).
        fun androidUnescape(s: String): String {
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        '\'' -> { out.append('\''); i += 2 }
                        '"' -> { out.append('"'); i += 2 }
                        '\\' -> { out.append('\\'); i += 2 }
                        'n' -> { out.append('\n'); i += 2 }
                        't' -> { out.append('\t'); i += 2 }
                        else -> { out.append(c); i += 1 }
                    }
                } else {
                    out.append(c); i += 1
                }
            }
            return out.toString()
        }
        fun parse(f: File): List<Pair<String, String>> {
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(f)
            val nodes = doc.getElementsByTagName("string")
            val out = mutableListOf<Pair<String, String>>()
            for (i in 0 until nodes.length) {
                val n = nodes.item(i)
                out += n.attributes.getNamedItem("name").nodeValue to
                    androidUnescape(n.textContent)
            }
            return out
        }
        fun ktEscape(s: String): String = s
            .replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
            .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t")
        val en = parse(enFile)
        val es = parse(esFile)
        require(en.map { it.first } == es.map { it.first }) {
            "EN/ES string key order/sets differ — parity broken"
        }
        val ids = en.mapIndexed { idx, (k, _) -> k to (idx + 1) }.toMap()
        val rOut = outDir.get().dir("net/sentinelsafeguard/mailapp").file("R.kt").asFile
        rOut.parentFile.mkdirs()
        rOut.writeText(buildString {
            appendLine("package net.sentinelsafeguard.mailapp")
            appendLine()
            appendLine("// GENERATED at build time from strings.xml — do not edit.")
            appendLine("object R {")
            appendLine("    object string {")
            for ((k, v) in ids) appendLine("        const val $k: Int = $v")
            appendLine("    }")
            appendLine("    object drawable {")
            appendLine("        const val sentinel_logo: Int = 10001")
            appendLine("        const val mail_mark: Int = 10002")
            appendLine("    }")
            appendLine("}")
        })
        fun table(name: String, pairs: List<Pair<String, String>>): String = buildString {
            appendLine("    val $name: Map<Int, String> = mapOf(")
            for ((k, v) in pairs) appendLine("        ${ids[k]} to \"${ktEscape(v)}\",")
            appendLine("    )")
        }
        val sOut = outDir.get().dir("net/sentinelsafeguard/mailapp/desktop").file("DesktopStrings.kt").asFile
        sOut.parentFile.mkdirs()
        sOut.writeText(buildString {
            appendLine("package net.sentinelsafeguard.mailapp.desktop")
            appendLine()
            appendLine("// GENERATED at build time from the Android app's strings.xml")
            appendLine("// (values + values-es) — the desktop copy IS the phone copy.")
            appendLine("object DesktopStrings {")
            append(table("EN", en))
            append(table("ES", es))
            appendLine("}")
        })
    }
}
sourceSets.main.get().kotlin.srcDir(generateDesktopStrings.map { it.outputs.files })

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // The mail engine: Eclipse Angus Mail (Jakarta Mail 2.0.3) —
    // the same pinned artifacts the Android app ships; pure JVM.
    implementation("org.eclipse.angus:angus-mail:2.0.3")
    implementation("org.eclipse.angus:angus-activation:2.0.2")
    // JNA: DPAPI (Windows) and libsecret / Secret Service (Linux)
    // for the credential store chain.
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(17)
}

compose.desktop {
    application {
        mainClass = "net.sentinelsafeguard.mailapp.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi)
            packageName = "sentinel-mail"
            packageVersion = "1.0.0"
            description = "Sentinel Mail — direct IMAP/SMTP mail client for Sentinel Mail, from Sentinel Safeguard"
            vendor = "Sentinel Safeguard LLC"
            linux {
                packageName = "sentinel-mail"
                appCategory = "Network;Email"
                iconFile.set(project.file("../brand/sentinel-mail-icon-master.png"))
            }
            windows {
                packageName = "Sentinel Mail"
                iconFile.set(project.file("../brand/sentinel-mail-icon-master.png"))
                dirChooser = true
                menuGroup = "Sentinel Safeguard"
                upgradeUuid = "c3d4e5f6-7a8b-4c5d-9e6f-2a3b4c5d6e7f"
            }
        }
    }
}
