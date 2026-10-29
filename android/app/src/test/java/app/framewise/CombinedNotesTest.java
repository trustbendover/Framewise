package app.framewise;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class CombinedNotesTest {
    private static JSONObject release(String tag, int code, boolean draft, boolean pre) throws Exception {
        return new JSONObject().put("tag_name", tag).put("draft", draft).put("prerelease", pre)
            .put("body", "- notes for " + tag + "\n\n---\nversionCode: " + code)
            .put("html_url", "https://github.com/x/y/releases/tag/" + tag)
            .put("assets", new JSONArray().put(new JSONObject().put("name", "Framewise.apk").put("browser_download_url", "https://github.com/x/y/" + tag + ".apk"))
                .put(new JSONObject().put("name", "Framewise.apk.sha256").put("browser_download_url", "https://github.com/x/y/" + tag + ".sha256")));
    }

    @Test public void friendTwoUpdatesBehindSeesEverySkippedRelease() throws Exception {
        JSONArray list = new JSONArray()
            .put(release("v0.5", 8, false, false)).put(release("v0.6-beta", 9, false, true)).put(release("v0.7", 10, true, false))
            .put(release("v0.4.2", 7, false, false)).put(release("v0.4.1", 7 - 1, false, false)).put(release("v0.4", 5, false, false));
        UpdateManager.Release best = UpdateManager.pickNewest(list, 6, "0.4.1");
        assertEquals("0.5", best.versionName);
        assertEquals(8, best.versionCode);
        assertEquals(2, best.notesByVersion.length());
        assertEquals("0.5", best.notesByVersion.getJSONObject(0).getString("version"));
        assertEquals("0.4.2", best.notesByVersion.getJSONObject(1).getString("version"));
        assertTrue(best.notesByVersion.getJSONObject(1).getString("notes").contains("notes for v0.4.2"));
        assertTrue(best.apkUrl.endsWith("v0.5.apk"));
    }

    @Test public void upToDateHasNoSkippedNotes() throws Exception {
        JSONArray list = new JSONArray().put(release("v0.5", 8, false, false));
        UpdateManager.Release best = UpdateManager.pickNewest(list, 8, "0.5.0");
        assertFalse(UpdateManager.isNewer(best, 8, "0.5.0"));
        assertEquals(0, best.notesByVersion.length());
    }

    @Test(expected = org.json.JSONException.class)
    public void onlyDraftsMeansNoRelease() throws Exception {
        UpdateManager.pickNewest(new JSONArray().put(release("v1", 99, true, false)), 1, "0.1");
    }

    @Test public void olderQueuedLogSnapshotNeverOverwritesNewer() {
        assertTrue(DiagLog.writeIfNewest("t.json", "new", 20));
        assertFalse(DiagLog.writeIfNewest("t.json", "old", 10));
        assertTrue(DiagLog.writeIfNewest("t.json", "newer", 21));
    }
}
