package cloud.veritasvpn.api

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ApiClientRetryTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun connectionRefusedThenSuccess_retriesAndSucceeds() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        server.start()

        val retryCount = AtomicInteger(0)
        val request = Request.Builder().url(server.url("/test")).get().build()

        val response = ApiClient.executeWithRetryInternal(
            requestFactory = { request },
            httpClient = client,
            retryOnTimeout = false,
            onRetry = { retryCount.incrementAndGet() }
        )

        assertEquals(200, response.code)
        assertEquals(0, retryCount.get())
    }

    @Test
    fun timeoutRetries_eventuallySucceeds() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        server.start()

        val retryCount = AtomicInteger(0)
        val request = Request.Builder().url(server.url("/test")).get().build()

        val fastClient = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()

        val response = ApiClient.executeWithRetryInternal(
            requestFactory = { request },
            httpClient = fastClient,
            retryOnTimeout = true,
            onRetry = { retryCount.incrementAndGet() }
        )

        assertEquals(200, response.code)
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
    fun postWithoutRetry_makesSingleRequest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("created"))
        server.start()

        val retryCount = AtomicInteger(0)
        val request = Request.Builder().url(server.url("/test")).post("body".toRequestBody(null)).build()

        val response = ApiClient.executeWithRetryInternal(
            requestFactory = { request },
            httpClient = client,
            retryOnTimeout = false,
            onRetry = { retryCount.incrementAndGet() }
        )

        assertEquals(201, response.code)
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
}
