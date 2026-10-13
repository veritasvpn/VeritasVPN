package cloud.veritasvpn.auth

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryRefreshTest {

    @Test
    fun twoConcurrentCallersRefreshOnceWhenTokenIsExpired() = runBlocking {
        val access = AtomicReference("expired")
        val calls = AtomicInteger(0)
        val ready = AtomicInteger(0)
        val bothReady = CompletableDeferred<Unit>()
        val results = List(2) {
            async(Dispatchers.Default) {
                AuthRepository.singleFlightRefresh(
                    force = false,
                    accessToken = { access.get() },
                    expired = { access.get() == "expired" },
                    refresh = {
                        calls.incrementAndGet()
                        bothReady.await()
                        access.set("fresh")
                        true
                    },
                    onReady = {
                        if (ready.incrementAndGet() == 2) bothReady.complete(Unit)
                    },
                )
            }
        }.map { it.await() }

        assertTrue(results.all { it })
        assertEquals(1, calls.get())
        assertEquals("fresh", access.get())
    }

    @Test
    fun twoConcurrentForcedRefreshesStillCallOnce() = runBlocking {
        val access = AtomicReference("still-valid")
        val calls = AtomicInteger(0)
        val ready = AtomicInteger(0)
        val bothReady = CompletableDeferred<Unit>()
        val results = List(2) {
            async(Dispatchers.Default) {
                AuthRepository.singleFlightRefresh(
                    force = true,
                    accessToken = { access.get() },
                    expired = { false },
                    refresh = {
                        calls.incrementAndGet()
                        bothReady.await()
                        access.set("rotated")
                        true
                    },
                    onReady = {
                        if (ready.incrementAndGet() == 2) bothReady.complete(Unit)
                    },
                )
            }
        }.map { it.await() }

        assertTrue(results.all { it })
        assertEquals(1, calls.get())
        assertEquals("rotated", access.get())
    }

    @Test
    fun unexpiredTokenSkipsRefreshUntilForced() = runBlocking {
        val access = AtomicReference("still-valid")
        var calls = 0
        val skipped = AuthRepository.singleFlightRefresh(
            force = false,
            accessToken = { access.get() },
            expired = { false },
            refresh = {
                calls += 1
                true
            },
        )
        val forced = AuthRepository.singleFlightRefresh(
            force = true,
            accessToken = { access.get() },
            expired = { false },
            refresh = {
                calls += 1
                access.set("rotated")
                true
            },
        )

        assertTrue(skipped)
        assertTrue(forced)
        assertEquals(1, calls)
        assertEquals("rotated", access.get())
    }

    @Test
    fun failedRefreshLetsTheWaitingCallerTry() = runBlocking {
        val access = AtomicReference("expired")
        val calls = AtomicInteger(0)
        val ready = AtomicInteger(0)
        val bothReady = CompletableDeferred<Unit>()
        val results = List(2) {
            async(Dispatchers.Default) {
                AuthRepository.singleFlightRefresh(
                    force = false,
                    accessToken = { access.get() },
                    expired = { access.get() == "expired" },
                    refresh = {
                        val n = calls.incrementAndGet()
                        if (n == 1) {
                            bothReady.await()
                            false
                        } else {
                            access.set("fresh")
                            true
                        }
                    },
                    onReady = {
                        if (ready.incrementAndGet() == 2) bothReady.complete(Unit)
                    },
                )
            }
        }.map { it.await() }

        assertEquals(listOf(false, true), results.sorted())
        assertEquals(2, calls.get())
        assertEquals("fresh", access.get())
    }
}
