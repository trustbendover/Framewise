package app.framewise;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdaterLogicTest {
    @Test public void comparesReleaseTagsWithInstalledVersion() {
        assertTrue(UpdateManager.compareVersions("0.3.1", "0.3.0") > 0);
        assertTrue(UpdateManager.compareVersions("v0.4", "0.3.9") > 0);
        assertEquals(0, UpdateManager.compareVersions("0.3", "0.3.0"));
        assertTrue(UpdateManager.compareVersions("0.2.0", "0.3.0") < 0);
        assertTrue(UpdateManager.compareVersions("1.0.0", "0.10.0") > 0);
        assertTrue(UpdateManager.compareVersions("0.10.0", "0.9.0") > 0);
        // Regression: two-part tags (v0.4) must sort above three-part patch tags (0.3.2).
        assertTrue(UpdateManager.compareVersions("v0.4", "0.3.2") > 0);
        assertTrue(UpdateManager.compareVersions("0.4.1", "0.4") > 0);
    }

    @Test public void installedBuildMatchesGradleVersion() {
        assertEquals("trustbendover/Framewise", BuildConfig.UPDATE_REPO);
        assertTrue(BuildConfig.VERSION_CODE >= 3);
    }
}
