package app.pocketfader.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the app reads version names, GitHub's tags and releases, and a release's checksum list. */
class UpdateParsingTest {
    @Test
    fun versionNames() {
        assertTrue(isNewer("v0.2.1-alpha", "0.2.0-alpha.10"))
        assertTrue(isNewer("v0.2.0-alpha.10", "0.2.0-alpha.4"))
        assertTrue(isNewer("v0.2.2-alpha", "0.2.1-alpha"))
        assertTrue(isNewer("v0.2.1", "0.2.1-alpha"))
        assertFalse(isNewer("v0.2.1-alpha", "0.2.1-alpha"))
        assertFalse(isNewer("v0.2.0-alpha.10", "0.2.1-alpha"))
        assertEquals(versionKey("0.2.1a0"), versionKey("v0.2.1-alpha"))
        assertEquals(versionKey("v0.2.1-alpha.0"), versionKey("0.2.1-alpha"))   // what older apps are told
        assertNull(versionKey("nightly"))
        assertFalse(isNewer("nightly", "0.2.1-alpha"))
    }

    @Test
    fun theHighestTagWhateverOrderGithubListsThem() {
        val tags = JSONArray(
            """[{"name":"v0.2.0-alpha.9"},{"name":"v0.2.1-alpha"},{"name":"latest-build"},{"name":"v0.2.0-alpha.10"},
               {"name":"v0.1.0-alpha.15"},{"name":null}]"""
        )
        assertEquals(listOf("v0.2.1-alpha", "v0.2.0-alpha.10", "v0.2.0-alpha.9"), newestTags(tags))
        assertEquals(emptyList<String>(), newestTags(JSONArray("[]")))
    }

    private val dl = "https://github.com/dash1101/ytm-starcast/releases/download/v0.2.1-alpha/"

    private fun release(assets: String, draft: Boolean = false, body: String = "\"### What's new in 0.2.1-alpha\\n\\n- one\"") =
        JSONObject(
            """{"tag_name":"v0.2.1-alpha","draft":$draft,"prerelease":true,"body":$body,
               "html_url":"https://github.com/dash1101/ytm-starcast/releases/tag/v0.2.1-alpha","assets":[$assets]}"""
        )

    @Test
    fun aReleaseWithItsApp() {
        val r = releaseInfo(
            release(
                """{"name":"ytm-starcast-android-unshrunk.apk","browser_download_url":"${dl}ytm-starcast-android-unshrunk.apk"},
                   {"name":"ytm-starcast-android.apk","browser_download_url":"${dl}ytm-starcast-android.apk"},
                   {"name":"SHA256SUMS.txt","browser_download_url":"${dl}SHA256SUMS.txt"}"""
            )
        )!!
        assertEquals("v0.2.1-alpha", r.latest)
        assertTrue(r.apk)
        assertEquals(GithubRelease("ytm-starcast-android.apk", "${dl}ytm-starcast-android.apk", "${dl}SHA256SUMS.txt"), r.github)
        assertEquals(listOf("one"), parseReleaseNotes(r.notes)!!.items)
    }

    @Test
    fun downloadsOnlyFromThisRepositorysReleases() {
        val r = releaseInfo(release("""{"name":"ytm-starcast-android.apk","browser_download_url":"https://example.com/x.apk"}"""))!!
        assertFalse(r.apk)
        assertNull(r.github)
    }

    @Test
    fun draftsNoAppAndANullBody() {
        assertNull(releaseInfo(release("", draft = true)))
        val r = releaseInfo(release("", body = "null"))!!
        assertFalse(r.apk)
        assertEquals("", r.notes)
        // a release without a checksum list still has its app (Android checks the signature anyway)
        val noSums = releaseInfo(release("""{"name":"ytm-starcast-android.apk","browser_download_url":"${dl}ytm-starcast-android.apk"}"""))!!
        assertNotNull(noSums.github)
        assertNull(noSums.github!!.sumsUrl)
    }

    @Test
    fun checksums() {
        val a = "a".repeat(64)
        val b = "B".repeat(64)
        val sums = "$a  ytm-starcast-android-unshrunk.apk\n$b  ytm-starcast-android.apk\nnot a line\n"
        assertEquals("b".repeat(64), checksumFor(sums, "ytm-starcast-android.apk"))
        assertEquals(a, checksumFor("$a *ytm-starcast-android.apk", "ytm-starcast-android.apk"))
        assertNull(checksumFor(sums, "other.apk"))
        assertNull(checksumFor("xyz  ytm-starcast-android.apk", "ytm-starcast-android.apk"))
    }
}
