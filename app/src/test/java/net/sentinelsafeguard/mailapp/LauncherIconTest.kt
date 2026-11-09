package net.sentinelsafeguard.mailapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * LOGO RULE regression pin (owner order 2026-11-08: "You use my
 * logo not your invention"). Sentinel Mail ships from day one with
 * the icon package generated from the owner's real logo
 * (your_files/brand/sentinel-safeguard-logo.jpg, the full lockup)
 * — byte-identical to the package the Command App pins. These
 * tests pin the resource-level facts so an invented mark can
 * never silently appear: no vector foreground exists, the
 * adaptive icon resolves to the raster logo PNGs, every launcher
 * PNG stays byte-identical to the owner-logo package, the splash
 * logo IS the source file, and the notification silhouette stays
 * the mechanically extracted emblem (never a redraw).
 */
class LauncherIconTest {

    private fun resDir(): File {
        val candidates = listOf(
            File("src/main/res"),
            File("app/src/main/res"),
        )
        return candidates.firstOrNull { File(it, "values/colors.xml").isFile }
            ?: throw AssertionError("res dir not found from ${File("").absolutePath}")
    }

    private fun sha256(f: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun noDrawnVectorForegroundExists() {
        assertFalse(
            "an invented vector mark must never ship",
            File(resDir(), "drawable/ic_launcher_foreground.xml").exists(),
        )
    }

    @Test
    fun adaptiveIconResolvesToRasterLogoForeground() {
        val xml = File(resDir(), "mipmap-anydpi-v26/ic_launcher.xml").readText()
        assertTrue(xml.contains("@mipmap/ic_launcher_foreground"))
        assertFalse(xml.contains("@drawable/ic_launcher_foreground"))
        assertTrue(xml.contains("@color/ic_launcher_background"))
    }

    @Test
    fun launcherPngsMatchOwnerLogoPackageBytes() {
        val expected = mapOf(
            "mipmap-mdpi/ic_launcher.png" to
                "53049c39d64fa496aa63f95be279c91133f3278496b5d20026460ede2cae0745",
            "mipmap-mdpi/ic_launcher_foreground.png" to
                "b9e62593678640e448acb23fae180c4414776de442a5457a7ba6ba12bd7d3460",
            "mipmap-hdpi/ic_launcher.png" to
                "d594d82a1cd56a2a11eeeb5191ff12a27243142ae48debb3d96c4c81718442e2",
            "mipmap-hdpi/ic_launcher_foreground.png" to
                "9c83c6434fb66d5bda3da7613b99f3a42479132ec8384905f9bf8647c3ea685f",
            "mipmap-xhdpi/ic_launcher.png" to
                "54901be614f830301efe95801f8ecb7c5ab9d5c2bcdfc6f8a21eb4116ef98976",
            "mipmap-xhdpi/ic_launcher_foreground.png" to
                "0bc088ee4b25f15c72ced71f911629560806c14435758e822ae39cd89c288e5b",
            "mipmap-xxhdpi/ic_launcher.png" to
                "7d61b21afbc011093eb1d14e38f34760c334d5913758909cd8d70dbc8719fd5b",
            "mipmap-xxhdpi/ic_launcher_foreground.png" to
                "6a636f5b16f1ed3a140da60c33724a9588c330a8a22999328c966609fb3b4790",
            "mipmap-xxxhdpi/ic_launcher.png" to
                "fb46c90f30386859abfbea5aca1677175e2f8f62de7ccfcbd48cab53ec477061",
            "mipmap-xxxhdpi/ic_launcher_foreground.png" to
                "09b5de8adf9be11eb4dff6990b256c07e6c5c406bebe83a552410f0036474093",
        )
        for ((path, hash) in expected) {
            val f = File(resDir(), path)
            assertTrue("$path must exist", f.isFile)
            assertEquals("$path must stay byte-identical to the owner-logo package", hash, sha256(f))
        }
    }

    @Test
    fun inAppLogoIsTheSourceFileItself() {
        val logo = File(resDir(), "drawable-nodpi/sentinel_logo.jpg")
        assertTrue(logo.isFile)
        assertEquals(
            "8d373bf2755f1b9f80d6d9498d7b08924501d368e854635e7091639fa8821910",
            sha256(logo),
        )
    }

    @Test
    fun notificationSilhouetteStaysTheExtractedEmblem() {
        val icon = File(resDir(), "drawable-nodpi/ic_stat_sentinel.png")
        assertTrue(icon.isFile)
        assertEquals(
            "358def30fdbbb06fbdf64287ad867bb2a508b60f23d1586160481592b46db79a",
            sha256(icon),
        )
    }
}
