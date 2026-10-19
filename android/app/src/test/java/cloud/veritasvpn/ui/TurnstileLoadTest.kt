package cloud.veritasvpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TurnstileLoadTest {
    @Test
    fun mainFrameFailureRetriesThenStops() {
        assertTrue(TurnstileLoad.retry(1))
        assertTrue(TurnstileLoad.retry(TurnstileLoad.ATTEMPTS - 1))
        assertFalse(TurnstileLoad.retry(TurnstileLoad.ATTEMPTS))
        assertFalse(TurnstileLoad.retry(0))
    }

    @Test
    fun pageIsRemoteAndTheMessageHidesTheWebViewError() {
        assertTrue(TurnstileLoad.PAGE.startsWith("https://veritasvpn.cloud/"))
        assertFalse(TurnstileLoad.UNAVAILABLE.contains("http", ignoreCase = true))
        assertFalse(TurnstileLoad.UNAVAILABLE.contains("took too long", ignoreCase = true))
        assertTrue(TurnstileLoad.BLANK_HTML.contains("#06101c"))
    }

    @Test
    fun webViewReplacesTheErrorPageAndDoesNotSurfaceItsText() {
        val source = kotlinSource("TurnstileWebView.kt")
        assertTrue(source.contains("onReceivedError"))
        assertTrue(source.contains("TurnstileLoad.BLANK_HTML"))
        assertTrue(source.contains("onUnavailable()"))
        assertFalse(source.contains("error.description"))
        assertFalse(source.contains("error.errorCode"))
        val auth = kotlinSource("AuthScreen.kt")
        assertFalse(auth.contains("Security check took too long"))
        assertTrue(auth.contains("TurnstileLoad.UNAVAILABLE"))
        assertTrue(auth.contains("showInteractive = turnstileInteractive"))
    }

    private fun kotlinSource(name: String): String {
        val roots = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("android/app/src/main/java"),
        )
        val file = roots.firstNotNullOfOrNull { root ->
            root.walk().firstOrNull { it.isFile && it.name == name }
        } ?: error("$name not found from ${File(".").absolutePath}")
        return file.readText()
    }
}
