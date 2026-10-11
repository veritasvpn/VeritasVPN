package cloud.veritasvpn.api

import cloud.veritasvpn.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class UserFacingErrorTest {
    @Test
    fun socketTimeoutException_mapsToTimeoutStringRes() {
        val error = SocketTimeoutException("timeout")
        assertEquals(R.string.error_server_timeout, UserFacingError.messageRes(error))
    }

    @Test
    fun interruptedIOException_mapsToTimeoutStringRes() {
        val error = InterruptedIOException("timeout")
        assertEquals(R.string.error_server_timeout, UserFacingError.messageRes(error))
    }

    @Test
    fun unknownHostException_mapsToCannotReachStringRes() {
        val error = UnknownHostException("Unable to resolve host")
        assertEquals(R.string.error_cannot_reach_server, UserFacingError.messageRes(error))
    }

    @Test
    fun connectException_mapsToCannotReachStringRes() {
        val error = ConnectException("Connection refused")
        assertEquals(R.string.error_cannot_reach_server, UserFacingError.messageRes(error))
    }

    @Test
    fun noRouteToHostException_mapsToCannotReachStringRes() {
        val error = NoRouteToHostException("No route to host")
        assertEquals(R.string.error_cannot_reach_server, UserFacingError.messageRes(error))
    }

    @Test
    fun sslException_mapsToSecureConnectionFailedStringRes() {
        val error = SSLException("SSL handshake failed")
        assertEquals(R.string.error_secure_connection_failed, UserFacingError.messageRes(error))
    }

    @Test
    fun userVisibleError_returnsNullRes() {
        val error = cloud.veritasvpn.auth.AuthRepository.Error("Incorrect email or password.")
        assertNull(UserFacingError.messageRes(error))
    }

    @Test
    fun authRepositoryError_implementsUserVisibleError() {
        val error = cloud.veritasvpn.auth.AuthRepository.Error("Incorrect email or password.")
        assert(error is UserVisibleError)
        assertEquals("Incorrect email or password.", error.userMessage)
    }

    @Test
    fun billingRepositoryError_implementsUserVisibleError() {
        val error = cloud.veritasvpn.billing.BillingRepository.Error("Device limit reached.")
        assert(error is UserVisibleError)
        assertEquals("Device limit reached.", error.userMessage)
    }

    @Test
    fun unknownException_mapsToGenericStringRes() {
        val error = RuntimeException("Some random error")
        assertEquals(R.string.error_generic, UserFacingError.messageRes(error))
    }

    @Test
    fun exceptionMessages_areNotUserFriendly() {
        val errors = listOf(
            SocketTimeoutException("timeout"),
            InterruptedIOException("timeout"),
            UnknownHostException("Unable to resolve host"),
            ConnectException("Connection refused"),
            NoRouteToHostException("No route to host"),
            SSLException("SSL handshake failed"),
            RuntimeException("Some random error")
        )
        for (error in errors) {
            val rawMessage = error.message
            assert(rawMessage != null)
            assert(rawMessage!!.isNotBlank())
        }
    }
}
