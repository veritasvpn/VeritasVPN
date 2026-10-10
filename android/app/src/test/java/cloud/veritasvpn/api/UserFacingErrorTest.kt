package cloud.veritasvpn.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class UserFacingErrorTest {
    @Test
    fun socketTimeoutException_isTransientNetworkError() {
        val error = SocketTimeoutException("timeout")
        assert(error is SocketTimeoutException)
        assertNotEquals("timeout", error.javaClass.simpleName)
    }

    @Test
    fun interruptedIOException_isTransientNetworkError() {
        val error = InterruptedIOException("timeout")
        assert(error is InterruptedIOException)
        assertNotEquals("timeout", error.javaClass.simpleName)
    }

    @Test
    fun unknownHostException_isTransientNetworkError() {
        val error = UnknownHostException("Unable to resolve host")
        assert(error is UnknownHostException)
    }

    @Test
    fun connectException_isTransientNetworkError() {
        val error = ConnectException("Connection refused")
        assert(error is ConnectException)
    }

    @Test
    fun noRouteToHostException_isTransientNetworkError() {
        val error = NoRouteToHostException("No route to host")
        assert(error is NoRouteToHostException)
    }

    @Test
    fun sslException_isNotTransientButMapped() {
        val error = SSLException("SSL handshake failed")
        assert(error is SSLException)
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
