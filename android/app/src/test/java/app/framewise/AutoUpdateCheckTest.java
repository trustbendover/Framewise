package app.framewise;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Regression: the automatic launch/resume check must find new releases without a manual check. */
public class AutoUpdateCheckTest {
    private static final long MIN = 60_000L, NOW = 1_800_000_000_000L;

    @Test public void checksOnFirstLaunch() { assertTrue(UpdateManager.shouldAutoCheck(NOW, 0, false, -1, 9)); }

    @Test public void checksAgainAfterTheIntervalNotSixHours() {
        assertTrue(UpdateManager.shouldAutoCheck(NOW, NOW - 25 * MIN, false, 9, 9));
        assertFalse(UpdateManager.shouldAutoCheck(NOW, NOW - 5 * MIN, false, 9, 9));
    }

    @Test public void checksRightAfterInstallingANewVersion() {
        // Last check was 1 minute ago by the previous version: still check now.
        assertTrue(UpdateManager.shouldAutoCheck(NOW, NOW - MIN, false, 8, 9));
    }

    @Test public void checksWhenAnUpdateIsPendingOrTheClockJumpedBack() {
        assertTrue(UpdateManager.shouldAutoCheck(NOW, NOW - MIN, true, 9, 9));
        assertTrue(UpdateManager.shouldAutoCheck(NOW, NOW + 60 * MIN, false, 9, 9));
    }
}
