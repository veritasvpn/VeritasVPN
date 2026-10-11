package cloud.veritasvpn.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ApiClientRetryTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        client = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun twoTimeoutsThenSuccess_retriesTwiceAndSucceeds() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        server.start()

        val retryCount = AtomicInteger(0)
        val request = Request.Builder().url(server.url("/test")).get().build()

        val retryClient = client.newBuilder()
            .callTimeout(15, TimeUnit.SECONDS)
            .build()

        val response = ApiClient.executeWithRetryInternal(
            requestFactory = { request },
            httpClient = retryClient,
            retryOnTimeout = true,
            onRetry = { retryCount.incrementAndGet() }
        )

        assertEquals(200, response.code)
        assertEquals(2, retryCount.get())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun allTimeouts_throwsAfterThreeAttempts() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()

        val retryCount = AtomicInteger(0)
        val request = Request.Builder().url(server.url("/test")).get().build()

        val retryClient = client.newBuilder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

        try {
            ApiClient.executeWithRetryInternal(
                requestFactory = { request },
                httpClient = retryClient,
                retryOnTimeout = true,
                onRetry = { retryCount.incrementAndGet() }
            )
            fail("Should have thrown IOException")
        } catch (e: IOException) {
            assertEquals(2, retryCount.get())
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun timeoutWithoutRetry_throwsAfterOneAttempt() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()

        val retryCount = AtomicInteger(0)
        val request = Request.Builder().url(server.url("/test")).get().build()

        try {
            ApiClient.executeWithRetryInternal(
                requestFactory = { request },
                httpClient = client,
                retryOnTimeout = false,
                onRetry = { retryCount.incrementAndGet() }
            )
            fail("Should have thrown IOException")
        } catch (e: IOException) {
            assertEquals(0, retryCount.get())
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun nonRetryableError_noRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad request"))
        server.start()

        val retryCount = AtomicInteger(0)
        val request = Request.Builder().url(server.url("/test")).get().build()

        val response = ApiClient.executeWithRetryInternal(
            requestFactory = { request },
            httpClient = client,
            retryOnTimeout = false,
            onRetry = { retryCount.incrementAndGet() }
        )

        assertEquals(400, response.code)
        assertEquals(0, retryCount.get())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun serverError500_noRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("service unavailable"))
        server.start()

        val retryCount = AtomicInteger(0)
        val request = Request.Builder().url(server.url("/test")).get().build()

        val response = ApiClient.executeWithRetryInternal(
            requestFactory = { request },
            httpClient = client,
            retryOnTimeout = false,
            onRetry = { retryCount.incrementAndGet() }
        )

        assertEquals(503, response.code)
        assertEquals(0, retryCount.get())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun cancellation_stopsRetries() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()

        val request = Request.Builder().url(server.url("/test")).get().build()

        val job = async(Dispatchers.IO) {
            ApiClient.executeWithRetryInternal(
                requestFactory = { request },
                httpClient = client,
                retryOnTimeout = true,
                onRetry = null
            )
        }

        Thread.sleep(500)
        job.cancel(CancellationException("Test cancellation"))

        try {
            job.await()
            fail("Should have thrown CancellationException")
        } catch (e: CancellationException) {
            assertTrue("Should have made at least one request", server.requestCount >= 1)
        }
    }
}
