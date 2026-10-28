package cloud.veritasvpn.billing

import cloud.veritasvpn.api.BillingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayAccountHashTest {
    @Test
    fun hashIsStableHexAndNotTheAccountId() {
        val account = "acc-1"
        val hash = PlayAccountHash.obfuscatedAccountId(account)
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(hash, PlayAccountHash.obfuscatedAccountId("  $account  "))
        assertNotEquals(account, hash)
        assertNotEquals(hash, PlayAccountHash.obfuscatedAccountId("acc-2"))
        // Pinned with billing-svc obfuscatedPlayAccountID("acc-1").
        assertEquals(
            "7032575faa823e04f6009640a1f6def620fbdbbd33201375babbc25d7537fc7d",
            hash,
        )
        assertThrows(IllegalArgumentException::class.java) {
            PlayAccountHash.obfuscatedAccountId("  ")
        }
    }

    @Test
    fun activePlaySubscriptionIsThePlayBuildOnly() {
        val play = BillingStatus(
            tier = "premium",
            status = "active",
            paymentMethod = "google_play",
            isPremium = true,
        )
        assertTrue(PlanEntitlement.activeGooglePlaySubscription(playBilling = true, play))
        assertFalse(PlanEntitlement.activeGooglePlaySubscription(playBilling = false, play))
        assertFalse(
            PlanEntitlement.activeGooglePlaySubscription(
                playBilling = true,
                play.copy(paymentMethod = "btcpay"),
            ),
        )
        assertFalse(
            PlanEntitlement.activeGooglePlaySubscription(
                playBilling = true,
                play.copy(isPremium = false, tier = "free", status = "active"),
            ),
        )
    }

    @Test
    fun playManifestDropsBtcPayReturnLinksAndReleaseDropsTheLoggingInterceptor() {
        val main = read("app/src/main/AndroidManifest.xml")
        val play = read("app/src/play/AndroidManifest.xml")
        val direct = read("app/src/direct/AndroidManifest.xml")
        val gradle = read("app/build.gradle.kts")
        assertFalse(main.contains("/billing/app-return"))
        assertFalse(main.contains("veritasvpn"))
        assertFalse(play.contains("/billing/app-return"))
        assertFalse(play.contains("veritasvpn"))
        assertTrue(direct.contains("/billing/app-return"))
        assertTrue(direct.contains("veritasvpn://") || direct.contains("android:scheme=\"veritasvpn\""))
        assertFalse(gradle.contains("logging-interceptor"))
        assertTrue(gradle.contains("versionCode = 84"))
        assertTrue(gradle.contains("versionName = \"0.2.100\""))
    }

    private fun read(relative: String): String {
        val suffixes = listOf(relative, relative.removePrefix("android/"))
        val roots = listOf(File("."), File(".."), File("../.."))
        val match = roots.asSequence()
            .filter { it.isDirectory }
            .flatMap { it.walk().maxDepth(6) }
            .firstOrNull { file ->
                if (!file.isFile) return@firstOrNull false
                val path = file.path.replace('\\', '/')
                suffixes.any { suffix -> path.endsWith(suffix) }
            } ?: error("$relative not found from ${File(".").absolutePath}")
        return match.readText()
    }
}
