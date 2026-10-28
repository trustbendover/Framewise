package app.framewise;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import org.junit.Test;

public class UpdaterLogicTest {
    @Test public void comparesReleaseTagsWithInstalledVersion() {
        assertTrue(UpdateManager.compareVersions("0.3.1", "0.3.0") > 0);
        assertTrue(UpdateManager.compareVersions("v0.4", "0.3.9") > 0);
        assertEquals(0, UpdateManager.compareVersions("0.3", "0.3.0"));
        assertTrue(UpdateManager.compareVersions("0.2.0", "0.3.0") < 0);
        assertTrue(UpdateManager.compareVersions("1.0.0", "0.10.0") > 0);
        assertTrue(UpdateManager.compareVersions("0.10.0", "0.9.0") > 0);
    }

    @Test public void installedBuildMatchesGradleVersion() {
        assertEquals("trustbendover/Framewise", BuildConfig.UPDATE_REPO);
        assertTrue(BuildConfig.VERSION_CODE >= 3);
    }

    @Test public void detectsAllBlackFrames() {
        int width = 48, height = 80, stride = width * 4 + 16;
        ByteBuffer black = ByteBuffer.allocate(stride * height);
        assertTrue(CaptureService.isBlack(black, stride, width, height));
        ByteBuffer image = ByteBuffer.allocate(stride * height);
        image.put(stride * 40 + 4 * 24, (byte) 200);
        assertFalse(CaptureService.isBlack(image, stride, width, height));
    }
}
