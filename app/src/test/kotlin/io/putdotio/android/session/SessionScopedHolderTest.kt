package io.putdotio.android.session

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionScopedHolderTest {
    @Test
    fun theCurrentSessionReusesOneValue() {
        val fixture = Fixture(initial = "a")

        val first = fixture.holder.valueFor("a") { fixture.build() }
        val second = fixture.holder.valueFor("a") { fixture.build() }

        assertSame(first, second)
        assertEquals(1, fixture.built.size)
        assertTrue(fixture.closed.isEmpty())
    }

    @Test
    fun aKeyThatIsNotCurrentBuildsNothingAndKeepsTheCurrentValue() {
        val fixture = Fixture(initial = "a")
        val current = checkNotNull(fixture.holder.valueFor("a") { fixture.build() })

        assertNull(fixture.holder.valueFor("b") { fixture.build() })

        assertEquals(listOf(current), fixture.built)
        assertTrue(fixture.closed.isEmpty())
        assertSame(current, fixture.holder.valueFor("a") { fixture.build() })
        fixture.authState.value = null
        assertNull(fixture.holder.valueFor("a") { fixture.build() })
        assertEquals(listOf(current), fixture.built)
    }

    @Test
    fun anEndedSessionIsReleasedThroughOnSessionEnded() {
        val fixture = Fixture(initial = "a")
        val first = checkNotNull(fixture.holder.valueFor("a") { fixture.build() })

        fixture.authState.value = null

        assertEquals(listOf(first), fixture.ended)
        assertTrue(fixture.closed.isEmpty())
        fixture.authState.value = "a"
        val second = checkNotNull(fixture.holder.valueFor("a") { fixture.build() })
        assertNotSame(first, second)
    }

    @Test
    fun aSessionThatChangedHandsBeforeReconcileClosesTheOldValue() {
        // The collector has not run yet, so only valueFor sees the new session.
        val fixture = Fixture(initial = "a", eager = false)
        val first = checkNotNull(fixture.holder.valueFor("a") { fixture.build() })

        fixture.authState.value = "b"
        val second = checkNotNull(fixture.holder.valueFor("b") { fixture.build() })

        assertNotSame(first, second)
        assertEquals(listOf(first), fixture.closed)
        fixture.scope.runCurrent()
        assertTrue(fixture.ended.isEmpty())
        assertSame(second, fixture.holder.valueFor("b") { fixture.build() })
    }

    @Test
    fun aValueWhoseSessionEndsWhileBuildingIsClosedAndNotHeld() {
        val fixture = Fixture(initial = "a")

        val raced = fixture.holder.valueFor("a") {
            fixture.build().also { fixture.authState.value = "b" }
        }

        assertNull(raced)
        assertEquals(fixture.built, fixture.closed)
        assertTrue(fixture.ended.isEmpty())
        fixture.authState.value = "a"
        val fresh = checkNotNull(fixture.holder.valueFor("a") { fixture.build() })
        assertEquals(2, fixture.built.size)
        assertNotSame(fixture.built.first(), fresh)
    }

    @Test
    fun clearClosesTheHeldValue() {
        val fixture = Fixture(initial = "a")
        val first = checkNotNull(fixture.holder.valueFor("a") { fixture.build() })

        fixture.holder.clear()

        assertEquals(listOf(first), fixture.closed)
        fixture.authState.value = null
        assertTrue(fixture.ended.isEmpty())
    }

    @Test
    fun concurrentCallersForOneSessionShareOneValue() {
        val fixture = Fixture(initial = "a")
        val threads = 8
        val start = CountDownLatch(1)
        val builds = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val results = (1..threads).map {
                pool.submit<Value?> {
                    start.await()
                    fixture.holder.valueFor("a") {
                        builds.incrementAndGet()
                        Value()
                    }
                }
            }
            start.countDown()
            val values = results.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, builds.get())
            assertEquals(1, values.toSet().size)
        } finally {
            pool.shutdownNow()
            fixture.scope.cancel()
        }
    }

    private class Value

    private class Fixture(initial: String?, eager: Boolean = true) {
        val authState = MutableStateFlow(initial)
        val scope: TestScope = TestScope(if (eager) UnconfinedTestDispatcher() else StandardTestDispatcher())
        val built = mutableListOf<Value>()
        val closed = mutableListOf<Value>()
        val ended = mutableListOf<Value>()
        val holder: SessionScopedHolder<String?, String, Value> =
            SessionScopedHolder(
                authState = authState,
                keyOf = { it },
                scope = scope,
                close = { closed += it },
                onSessionEnded = { ended += it },
            )

        fun build(): Value = Value().also { built += it }
    }
}
