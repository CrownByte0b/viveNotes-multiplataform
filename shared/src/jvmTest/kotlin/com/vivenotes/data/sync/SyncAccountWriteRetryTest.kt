package com.vivenotes.data.sync

import java.io.IOException
import java.nio.file.AccessDeniedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SyncAccountWriteRetryTest {
    @Test
    fun aDirectAccessDenialIsRetried() = runTest {
        var attempts = 0
        val result = retrySyncAccountWrite {
            if (++attempts == 1) throw AccessDeniedException("sync.preferences_pb")
            "written"
        }

        assertEquals("written", result)
        assertEquals(2, attempts)
        assertEquals(50, currentTime)
    }

    @Test
    fun aWrappedAccessDenialIsRetriedUntilTheReaderReleasesTheFile() = runTest {
        var attempts = 0
        val result = retrySyncAccountWrite {
            if (++attempts < 4) throw IOException("Unable to rename", IOException(AccessDeniedException("sync.preferences_pb")))
            "written"
        }

        assertEquals("written", result)
        assertEquals(4, attempts)
        assertEquals(300, currentTime)
    }

    @Test
    fun otherIoFailuresAreReportedWithoutRetrying() = runTest {
        val failure = IOException("Unable to rename", IOException("Disk full"))
        var attempts = 0
        val thrown = assertFailsWith<IOException> {
            retrySyncAccountWrite {
                attempts++
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertEquals(1, attempts)
        assertEquals(0, currentTime)
    }

    @Test
    fun aPersistentAccessDenialReportsTheOriginalErrorAfterBoundedRetries() = runTest {
        val failure = IOException("Unable to rename", AccessDeniedException("sync.preferences_pb"))
        var attempts = 0
        val thrown = assertFailsWith<IOException> {
            retrySyncAccountWrite {
                attempts++
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertEquals(6, attempts)
        assertEquals(750, currentTime)
    }

    @Test
    fun cancellationIsReportedWithoutRetrying() = runTest {
        val failure = CancellationException("Closing")
        var attempts = 0
        val thrown = assertFailsWith<CancellationException> {
            retrySyncAccountWrite {
                attempts++
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertEquals(1, attempts)
        assertEquals(0, currentTime)
    }

    @Test
    fun cancellingDuringTheRetryDelayStopsFurtherWrites() = runTest {
        var attempts = 0
        val job = launch {
            retrySyncAccountWrite {
                attempts++
                throw IOException("Unable to rename", AccessDeniedException("sync.preferences_pb"))
            }
        }
        runCurrent()
        assertEquals(1, attempts)
        job.cancelAndJoin()

        assertEquals(1, attempts)
        assertEquals(0, currentTime)
    }
}
