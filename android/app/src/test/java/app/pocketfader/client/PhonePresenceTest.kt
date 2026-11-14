package app.pocketfader.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the phone tells the computer what it plays (for the Discord status), and what it sends. */
class PhonePresenceTest {
    private val song = PhoneSong(
        key = "aaaaaaaaaaa", title = "One", artist = "Artist", album = "Album",
        art = "https://lh3.googleusercontent.com/abc=w544-h544-l90-rj", videoId = "aaaaaaaaaaa",
        playing = true, position = 10.0, duration = 200.0,
    )

    @Test
    fun aSpeedChangeGoesAtOnce() {
        assertTrue(shouldSendPresence(song, 1_000L, song.copy(speed = 1.25, position = 10.5), 1_500L))
        assertFalse(shouldSendPresence(song, 1_000L, song.copy(position = 10.5), 1_500L))
    }

    @Test
    fun onlyMusicPlayingStartsBeingShown() {
        assertTrue(shouldSendPresence(null, 0L, song, 1_000L))
        // a paused queue left from before isn't music playing
        assertFalse(shouldSendPresence(null, 0L, song.copy(playing = false), 1_000L))
        assertFalse(shouldSendPresence(null, 0L, null, 1_000L))
    }

    @Test
    fun changesGoAtOnce() {
        // shown at 1 s, 10 s into the song
        assertTrue(shouldSendPresence(song, 1_000L, null, 2_000L))                                       // stopped
        assertTrue(shouldSendPresence(song, 1_000L, song.copy(key = "bbbbbbbbbbb", videoId = "bbbbbbbbbbb", position = 0.0), 2_000L))
        assertTrue(shouldSendPresence(song, 1_000L, song.copy(title = "Two"), 2_000L))
        assertTrue(shouldSendPresence(song, 1_000L, song.copy(playing = false, position = 11.0), 2_000L))  // paused
        val paused = song.copy(playing = false)
        assertTrue(shouldSendPresence(paused, 1_000L, paused.copy(playing = true), 60_000L))               // resumed
        // another song while paused is still shown (it was already showing)
        assertTrue(shouldSendPresence(paused, 1_000L, paused.copy(key = "ccccccccccc", title = "Three"), 2_000L))
    }

    @Test
    fun aSongPlayingOnIsNotAChangeButASeekIs() {
        // 20 s later the song is 20 s further on
        assertFalse(shouldSendPresence(song, 1_000L, song.copy(position = 30.0), 21_000L))
        assertFalse(shouldSendPresence(song, 1_000L, song.copy(position = 32.0), 21_000L))   // within the slack
        assertFalse(shouldSendPresence(song, 1_000L, song.copy(position = 28.0), 21_000L))
        assertTrue(shouldSendPresence(song, 1_000L, song.copy(position = 33.0), 21_000L))    // forward
        assertTrue(shouldSendPresence(song, 1_000L, song.copy(position = 5.0), 21_000L))     // back
        // the same song again from the start (repeat one)
        assertTrue(shouldSendPresence(song, 1_000L, song.copy(position = 0.0), 191_000L))
        // paused, the place stays where it was
        val paused = song.copy(playing = false)
        assertFalse(shouldSendPresence(paused, 1_000L, paused, 500_000L))
        assertTrue(shouldSendPresence(paused, 1_000L, paused.copy(position = 60.0), 2_000L))
        // played faster, the song moves on faster
        val fast = song.copy(speed = 1.5)
        assertFalse(shouldSendPresence(fast, 1_000L, fast.copy(position = 40.0), 21_000L))
    }

    @Test
    fun theHeartbeatCountsTheSongForward() {
        assertEquals(15.0, song.movedOn(1_000L, 6_000L).position, 1e-9)
        assertEquals(17.5, song.copy(speed = 1.5).movedOn(1_000L, 6_000L).position, 1e-9)
        assertEquals(200.0, song.movedOn(1_000L, 1_000_000L).position, 1e-9)                  // never past the end
        assertEquals(10.0, song.copy(playing = false).movedOn(1_000L, 6_000L).position, 1e-9)
        assertEquals(10.0, song.movedOn(6_000L, 1_000L).position, 1e-9)                       // a clock that went back
        assertEquals(1_010.0, song.copy(duration = 0.0).movedOn(0L, 1_000_000L).position, 1e-9)  // no length known
    }

    @Test
    fun aPausedSongComesOffAfterTenMinutes() {
        assertTrue(keepShowing(true, 0L, 100_000_000L))
        assertTrue(keepShowing(false, 0L, 100_000_000L))
        assertTrue(keepShowing(false, 1_000L, 1_000L + 9 * 60_000L))
        assertFalse(keepShowing(false, 1_000L, 1_000L + 10 * 60_000L))
    }

    @Test
    fun theSongAsSent() {
        val body = JSONObject(presenceBody("Abc123def456", song, 1_700_000_000_000L).toString())
        assertEquals("Abc123def456", body.getString("device"))
        assertEquals(1_700_000_000_000L, body.getLong("ts"))
        val now = body.getJSONObject("now")
        assertTrue(now.getBoolean("playing"))
        assertEquals("One", now.getString("title"))
        assertEquals("Artist", now.getString("artist"))
        assertEquals("Album", now.getString("album"))
        assertEquals("https://lh3.googleusercontent.com/abc=w544-h544-l90-rj", now.getString("art"))
        assertEquals("aaaaaaaaaaa", now.getString("videoId"))
        assertEquals(10.0, now.getDouble("position"), 1e-9)
        assertEquals(200.0, now.getDouble("duration"), 1e-9)
        assertEquals(1.0, now.getDouble("speed"), 1e-9)
    }

    @Test
    fun stoppedIsNull() {
        val body = JSONObject(presenceBody("Abc123def456", null, 5L).toString())
        assertTrue(body.has("now"))
        assertTrue(body.isNull("now"))
        assertEquals("Abc123def456", body.getString("device"))
    }

    @Test
    fun noAddressesOfThePhonesOwnFiles() {
        val file = PhoneSong(
            key = "content://media/external/audio/media/5", title = "Recording",
            art = "content://media/external/audio/albumart/1", videoId = null, playing = true,
        )
        val now = presenceBody("Abc123def456", file, 1L).getJSONObject("now")
        assertFalse(now.has("art"))
        assertFalse(now.has("videoId"))
        // only https covers, only real YouTube ids
        val odd = presenceBody("Abc123def456", file.copy(art = "http://example.com/x.jpg", videoId = "file:///sdcard/x.mp3"), 1L)
            .getJSONObject("now")
        assertFalse(odd.has("art"))
        assertFalse(odd.has("videoId"))
    }

    @Test
    fun oddNumbersAreSentAsSafeOnes() {
        val now = presenceBody(
            "Abc123def456", song.copy(position = Double.NaN, duration = Double.POSITIVE_INFINITY, speed = 0.0), 1L,
        ).getJSONObject("now")
        assertEquals(0.0, now.getDouble("position"), 0.0)
        assertEquals(0.0, now.getDouble("duration"), 0.0)
        assertEquals(1.0, now.getDouble("speed"), 0.0)
        // a place reported a moment past the end is sent as the end
        val past = presenceBody("Abc123def456", song.copy(position = 200.4), 1L).getJSONObject("now")
        assertEquals(200.0, past.getDouble("position"), 1e-9)
        val before = presenceBody("Abc123def456", song.copy(position = -3.0), 1L).getJSONObject("now")
        assertEquals(0.0, before.getDouble("position"), 0.0)
    }

    @Test
    fun howOftenTheComputerAsks() {
        assertEquals(30, everyFromReply(JSONObject("""{"ok":true,"shown":true,"why":"","every":30}""")))
        assertEquals(45, everyFromReply(JSONObject("""{"ok":true,"shown":false,"why":"off","every":45}""")))
        assertEquals(DEFAULT_EVERY_SEC, everyFromReply(JSONObject("{}")))
        assertEquals(10, everyFromReply(JSONObject("""{"every":0}""")))
        assertEquals(300, everyFromReply(JSONObject("""{"every":86400}""")))
    }

    @Test
    fun thePhonesName() {
        val names = HashSet<String>()
        repeat(200) {
            val id = newDeviceId()
            assertTrue(id, Regex("^[A-Za-z0-9]{12}$").matches(id))
            assertTrue(isDeviceId(id))
            names.add(id)
        }
        assertEquals(200, names.size)
        assertEquals(newDeviceId(java.util.Random(7)), newDeviceId(java.util.Random(7)))
        assertTrue(isDeviceId("phone_1-ab"))
        assertFalse(isDeviceId(null))
        assertFalse(isDeviceId("abc"))
        assertFalse(isDeviceId("has a space"))
        assertFalse(isDeviceId("x".repeat(33)))
    }

    @Test
    fun whereTheDiscordSongComesFrom() {
        val phone = parseBeacon(
            JSONObject("""{"nowPlaying":{"title":"One","artist":"Artist","state":"playing","from":"phone"}}"""), 0L,
        )
        assertEquals(BeaconSong("One", "Artist", true, "phone"), phone.song)
        // a computer that doesn't say
        val older = parseBeacon(JSONObject("""{"nowPlaying":{"title":"One","artist":"Artist","state":"paused"}}"""), 0L)
        assertEquals("", older.song!!.from)
        assertFalse(older.song!!.playing)
    }
}
