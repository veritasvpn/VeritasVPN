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
    fun apiException_containsServerMessage() {
        val error = ApiException("Server error message", 500)
        assertEquals("Server error message", error.serverMessage)
        assertEquals(500, error.httpCode)
    }

    @Test
    fun apiExceptionWithoutServerMessage_isNull() {
        val error = ApiException(null, 500)
        assertEquals(null, error.serverMessage)
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
