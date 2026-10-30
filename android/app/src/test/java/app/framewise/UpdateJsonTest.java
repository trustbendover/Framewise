package app.framewise;

import static org.junit.Assert.*;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** 0.5.3: update.json first, rate-limit back-off, debounced taps and honest error messages. */
public class UpdateJsonTest {
    private static final long NOW = 1_800_000_000_000L, MIN = 60_000L;

    private static JSONObject manifest() throws Exception {
        return new JSONObject()
            .put("versionCode", 12).put("versionName", "0.5.3").put("tag", "v0.5.3")
            .put("apkName", "Framewise-0.5.3-android.apk")
            .put("apkUrl", "https://github.com/o/r/releases/download/v0.5.3/Framewise-0.5.3-android.apk")
            .put("sha256Url", "https://github.com/o/r/releases/download/v0.5.3/Framewise-0.5.3-android.apk.sha256")
            .put("sha256", "AB".repeat(32)).put("notes", "- new")
            .put("releases", new JSONArray()
                .put(new JSONObject().put("version", "0.5.1").put("notes", "- one"))
                .put(new JSONObject().put("version", "0.5.3").put("versionCode", 12).put("notes", "- three"))
                .put(new JSONObject().put("version", "0.5.2").put("notes", "- two"))
                .put(new JSONObject().put("version", "0.5").put("notes", "- old")));
    }

    @Test public void readsUpdateJsonWithSkippedNotesNewestFirst() throws Exception {
        UpdateManager.Release r = UpdateManager.parseUpdateJson(manifest(), 10, "0.5.1");
        assertEquals("0.5.3", r.versionName);
        assertEquals(12, r.versionCode);
        assertEquals("ab".repeat(32), r.sha256);
        assertTrue(UpdateManager.isNewer(r, 10, "0.5.1"));
        assertEquals(2, r.notesByVersion.length());
        assertEquals("0.5.3", r.notesByVersion.getJSONObject(0).getString("version"));
        assertEquals("0.5.2", r.notesByVersion.getJSONObject(1).getString("version"));
    }

    @Test public void ignoresNonGithubUrls() throws Exception {
        JSONObject m = manifest().put("apkUrl", "https://evil.example/app.apk");
        assertNull(UpdateManager.parseUpdateJson(m, 10, "0.5.1").apkUrl);
    }

    @Test public void noAutoCheckDuringBackoffOrWithin20Minutes() {
        assertFalse(UpdateManager.shouldAutoCheck(NOW, NOW - 25 * MIN, false, 11, 11, NOW + 30 * MIN));
        assertTrue(UpdateManager.shouldAutoCheck(NOW, NOW - 25 * MIN, false, 11, 11, NOW - MIN));
        assertFalse(UpdateManager.shouldAutoCheck(NOW, NOW - 5 * MIN, false, 11, 11, 0));
    }

    @Test public void backoffFollowsGithubResetTime() {
        assertEquals(NOW + 30 * MIN, UpdateManager.backoffUntil(NOW, String.valueOf((NOW + 30 * MIN) / 1000), null));
        assertEquals(NOW + 2 * MIN, UpdateManager.backoffUntil(NOW, null, "120"));
        assertEquals(NOW + 60 * MIN, UpdateManager.backoffUntil(NOW, null, null));
        assertEquals(NOW + 120 * MIN, UpdateManager.backoffUntil(NOW, String.valueOf((NOW + 900 * MIN) / 1000), null));
    }

    @Test public void repeatTapsWithinTenSecondsAreIgnored() {
        assertTrue(UpdateManager.isRepeatTap(NOW, NOW - 3_000));
        assertFalse(UpdateManager.isRepeatTap(NOW, NOW - 11_000));
        assertFalse(UpdateManager.isRepeatTap(NOW, 0));
    }

    @Test public void onlyBlamesCameraWifiWhenOnIt() {
        IOException offline = new IOException("timeout");
        assertFalse(UpdateManager.networkErrorMessage(offline, false).contains("camera"));
        assertTrue(UpdateManager.networkErrorMessage(offline, true).contains("camera's Wi-Fi"));
        assertEquals("GitHub is busy, Framewise will try again later.",
            UpdateManager.networkErrorMessage(new UpdateManager.RateLimitException(NOW), true));
    }
}
