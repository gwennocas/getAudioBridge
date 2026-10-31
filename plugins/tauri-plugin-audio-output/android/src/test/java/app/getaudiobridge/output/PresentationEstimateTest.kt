package app.getaudiobridge.output

import org.junit.Assert.*
import org.junit.Test

class PresentationEstimateTest {
    @Test fun accountsForTimestampAgeInsteadOfCountingAlreadyPresentedFrames() {
        assertEquals(10.0, PresentationEstimate.milliseconds(4800, 4320, 3840, 1_000_000_000, 1_010_000_000)!!, 0.0001)
    }
    @Test fun includesFramesCommittedForFuturePresentation() {
        assertEquals(15.0, PresentationEstimate.milliseconds(4800, 4800, 4800, 1_015_000_000, 1_000_000_000)!!, 0.0001)
    }
    @Test fun unwrapsTimestampFromBeforeThePlaybackHeadWrap() {
        val wrap = 1L shl 32
        assertEquals(736.0 / 48, PresentationEstimate.milliseconds(wrap + 720, wrap + 240, wrap - 16, 1_000_000_000, 1_000_000_000)!!, 0.0001)
    }
    @Test fun rejectsStaleMissingAndInconsistentReports() {
        assertNull(PresentationEstimate.milliseconds(4800, 4320, 3840, 0, 1_000_000_000))
        assertNull(PresentationEstimate.milliseconds(4800, 4320, 3840, 1_000_000_000, 4_000_000_000))
        assertNull(PresentationEstimate.milliseconds(4800, 4320, 5000, 1_000_000_000, 1_000_000_000))
    }
    @Test fun isIndependentOfTheMonotonicClockOriginAndNeverNegative() {
        val first = PresentationEstimate.milliseconds(4800, 4320, 3840, 1_000_000_000, 1_010_000_000)
        assertEquals(first, PresentationEstimate.milliseconds(4800, 4320, 3840, 9_000_000_000, 9_010_000_000))
        assertEquals(0.0, PresentationEstimate.milliseconds(4800, 4800, 4800, 1_000_000_000, 1_010_000_000)!!, 0.0)
    }
}
