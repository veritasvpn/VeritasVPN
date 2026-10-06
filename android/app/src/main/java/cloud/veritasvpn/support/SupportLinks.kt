package cloud.veritasvpn.support

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import java.net.URLEncoder

object SupportLinks {
    const val CONTACT_EMAIL = "contact@veritasvpn.cloud"
    const val CONTACT_SUBJECT = "VeritasVPN support"
    const val SUPPORT = "https://veritasvpn.cloud/support"
    const val SUPPORT_CONNECT = "https://veritasvpn.cloud/support#stealth"
    const val PRIVACY = "https://veritasvpn.cloud/privacy"
    const val TERMS = "https://veritasvpn.cloud/terms"
    const val CONTACT_PAGE = "https://veritasvpn.cloud/contact"

    private val ALLOWED_HTTPS = listOf(SUPPORT, PRIVACY, TERMS, CONTACT_PAGE)

    fun isAllowedHttps(url: String): Boolean {
        return ALLOWED_HTTPS.any { allowed ->
            url == allowed || url.startsWith("$allowed#") || url.startsWith("$allowed?")
        }
    }

    fun mailtoUrl(includeDiagnostics: Boolean, reportText: String): String {
        val body = contactBody(includeDiagnostics, reportText)
        val subject = encodeQuery(CONTACT_SUBJECT)
        val encodedBody = encodeQuery(body)
        return "mailto:$CONTACT_EMAIL?subject=$subject&body=$encodedBody"
    }

    fun openHttps(context: Context, url: String): Boolean {
        if (!isAllowedHttps(url)) return false
        val uri = Uri.parse(url)
        val customTabs = runCatching {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(context, uri)
            true
        }.getOrDefault(false)
        if (customTabs) return true
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        }.getOrDefault(false)
    }

    fun launchContact(context: Context, includeDiagnostics: Boolean, reportText: String): Boolean {
        val body = contactBody(includeDiagnostics, reportText)
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(mailtoUrl(includeDiagnostics, reportText))).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(CONTACT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, CONTACT_SUBJECT)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        if (start(context, intent)) return true
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(CONTACT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, CONTACT_SUBJECT)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        return start(context, Intent.createChooser(share, "Contact VeritasVPN support"))
    }

    fun launchEmailReport(context: Context, reportText: String): Boolean =
        launchContact(context, includeDiagnostics = true, reportText = reportText)

    fun launchShare(context: Context, reportText: String): Boolean {
        val safe = formatSafeReportText(reportText)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "VeritasVPN diagnostic report")
            putExtra(Intent.EXTRA_TEXT, safe)
        }
        return start(context, Intent.createChooser(send, "Share diagnostic report"))
    }

    fun copyReport(context: Context, reportText: String): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val safe = formatSafeReportText(reportText)
        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("VeritasVPN diagnostic report", safe))
            true
        }.getOrDefault(false)
    }

    private fun start(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
