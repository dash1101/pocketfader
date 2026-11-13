package app.pocketfader.client

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.Description

/** One comment under a song's or video's YouTube page. */
@Immutable
data class Comment(
    val id: String,
    val author: String,
    val avatar: String?,
    val text: String,
    val time: String,
    val likes: String,
    val replyCount: Int,
    val replies: Page?,
    val pinned: Boolean,
    val hearted: Boolean,
    val byCreator: Boolean,
    val verified: Boolean,
)

/**
 * Comments, read on the phone itself through NewPipe Extractor (the same way it finds songs), straight from YouTube:
 * the computer isn't needed. A YouTube Music song has the comments of its YouTube page.
 */
object Comments {
    class Batch(val items: List<Comment>, val next: Page?, val disabled: Boolean = false, val count: Int = -1)

    private fun url(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

    private fun text(d: Description?): String {
        if (d == null) return ""
        val raw = d.content()
        return if (d.type() == Description.Type.HTML) {
            HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
        } else {
            raw.trim()
        }
    }

    private fun toComment(c: CommentsInfoItem): Comment {
        val likes = c.textualLikeCount?.takeIf { it.isNotBlank() } ?: if (c.likeCount > 0) c.likeCount.toString() else ""
        return Comment(
            id = c.commentId ?: c.url ?: "",
            author = c.uploaderName.orEmpty(),
            avatar = c.uploaderAvatars?.minByOrNull { kotlin.math.abs(it.height - 88) }?.url,
            text = text(c.commentText),
            time = c.textualUploadDate.orEmpty(),
            likes = likes,
            replyCount = c.replyCount.coerceAtLeast(0),
            replies = c.replies,
            pinned = c.isPinned,
            hearted = c.isHeartedByUploader,
            byCreator = c.isChannelOwner,
            verified = c.isUploaderVerified,
        )
    }

    /** The first page of comments (run off the main thread). */
    fun first(videoId: String): Batch {
        Streams.init()
        val info = CommentsInfo.getInfo(ServiceList.YouTube, url(videoId)) ?: return Batch(emptyList(), null, disabled = true)
        return Batch(
            info.relatedItems.map(::toComment), if (info.hasNextPage()) info.nextPage else null,
            disabled = info.isCommentsDisabled, count = info.commentsCount,
        )
    }

    /** The next page of comments, or of a comment's replies. */
    fun more(videoId: String, page: Page): Batch {
        Streams.init()
        val p = CommentsInfo.getMoreItems(ServiceList.YouTube, url(videoId), page)
        return Batch(p.items.map(::toComment), if (p.hasNextPage()) p.nextPage else null)
    }

    fun friendly(e: Throwable): String = when (e) {
        is ReCaptchaException -> "YouTube wants a captcha from this phone right now. Try again later."
        is java.io.IOException -> "Couldn't reach YouTube. Check the phone's internet connection."
        else -> "Couldn't load the comments" + (e.message?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ".")
    }
}

/** Replies under one comment, once they are asked for. */
@Immutable
data class Replies(val items: List<Comment> = emptyList(), val loading: Boolean = true, val next: Page? = null, val error: String? = null)

/** The comments of what is playing, as the screen shows them; kept while you switch tabs, started over for each song. */
class CommentsUi(val videoId: String?) {
    var items by mutableStateOf<List<Comment>>(emptyList())
    var loading by mutableStateOf(false)
    var loaded by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var disabled by mutableStateOf(false)
    var count by mutableStateOf(-1)
    var next by mutableStateOf<Page?>(null)
    val replies = mutableStateMapOf<String, Replies>()

    val usable: Boolean get() = isYtId(videoId)

    suspend fun loadFirst() {
        val id = videoId?.takeIf { isYtId(it) } ?: return
        if (loading) return
        loading = true
        error = null
        try {
            val b = withContext(Dispatchers.IO) { Comments.first(id) }
            items = b.items.distinctBy { it.id }
            next = b.next
            disabled = b.disabled
            count = b.count
            loaded = true
        } catch (e: Exception) {
            error = Comments.friendly(e)
        }
        loading = false
    }

    suspend fun loadMore() {
        val id = videoId?.takeIf { isYtId(it) } ?: return
        val page = next ?: return
        if (loading) return
        loading = true
        try {
            val b = withContext(Dispatchers.IO) { Comments.more(id, page) }
            val seen = items.map { it.id }.toHashSet()
            items = items + b.items.filter { it.id !in seen }
            next = b.next
        } catch (e: Exception) {
            error = Comments.friendly(e)
        }
        loading = false
    }

    fun toggleReplies(c: Comment, scope: CoroutineScope) {
        if (replies.containsKey(c.id)) {
            replies.remove(c.id)
            return
        }
        val page = c.replies ?: return
        replies[c.id] = Replies()
        scope.launch { loadReplies(c.id, page) }
    }

    fun moreReplies(c: Comment, scope: CoroutineScope) {
        val r = replies[c.id] ?: return
        val page = r.next ?: return
        if (r.loading) return
        replies[c.id] = r.copy(loading = true)
        scope.launch { loadReplies(c.id, page) }
    }

    private suspend fun loadReplies(commentId: String, page: Page) {
        val id = videoId ?: return
        val before = replies[commentId]?.items.orEmpty()
        try {
            val b = withContext(Dispatchers.IO) { Comments.more(id, page) }
            if (replies.containsKey(commentId)) replies[commentId] = Replies(before + b.items, false, b.next)
        } catch (e: Exception) {
            if (replies.containsKey(commentId)) replies[commentId] = Replies(before, false, null, Comments.friendly(e))
        }
    }
}

/** The comments of [videoId], loaded the first time they are shown ([active]). */
@Composable
fun rememberComments(videoId: String?, active: Boolean): CommentsUi {
    val ui = remember(videoId) { CommentsUi(videoId) }
    LaunchedEffect(ui, active) {
        if (active && ui.usable && !ui.loaded && !ui.loading && ui.error == null) ui.loadFirst()
    }
    return ui
}

@Composable
private fun CommentsNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp),
    )
}

@Composable
private fun Spinner() {
    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

/** The comments as rows of a list (the phone's player page, below its tabs). */
fun LazyListScope.commentItems(ui: CommentsUi, scope: CoroutineScope) {
    when {
        !ui.usable -> item(key = "c-none") { CommentsNote("Comments show for songs and videos from YouTube Music, not for files on this phone.") }
        ui.disabled -> item(key = "c-off") { CommentsNote("Comments are turned off for this one.") }
        else -> {
            if (ui.count > 0) item(key = "c-count") {
                Text(
                    "%,d comments".format(ui.count),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(ui.items, key = { "c-" + it.id }) { c -> CommentRow(c, ui, scope) }
            val err = ui.error
            when {
                ui.loading -> item(key = "c-loading") { Spinner() }
                err != null -> item(key = "c-error") {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CommentsNote(err)
                        OutlinedButton(onClick = {
                            ui.error = null
                            scope.launch { if (ui.loaded) ui.loadMore() else ui.loadFirst() }
                        }) { Text("Try again") }
                    }
                }
                ui.next != null -> item(key = "c-more") {
                    // reaching the end of the list asks for the next page
                    LaunchedEffect(ui.next) { ui.loadMore() }
                    Spinner()
                }
                ui.loaded && ui.items.isEmpty() -> item(key = "c-empty") { CommentsNote("No comments yet.") }
            }
        }
    }
}

@Composable
private fun Avatar(url: String?, sizeDp: Int) {
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun CommentBody(c: Comment, reply: Boolean, footer: @Composable () -> Unit = {}) {
    var expanded by remember(c.id) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = if (reply) 60.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
    ) {
        Avatar(c.avatar, if (reply) 28 else 36)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            if (c.pinned) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PushPin, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" Pinned", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.author,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (c.byCreator) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (c.verified) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.CheckCircle, "Verified", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (c.time.isNotBlank()) {
                    Text(
                        " · ${c.time}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                }
            }
            Text(
                c.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp).clickable { expanded = !expanded },
            )
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Outlined.ThumbUp, "Likes", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (c.likes.isNotBlank()) Text(c.likes, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (c.hearted) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.Favorite, "Hearted by the creator", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
            footer()
        }
    }
}

@Composable
private fun CommentRow(c: Comment, ui: CommentsUi, scope: CoroutineScope) {
    val r = ui.replies[c.id]
    Column {
        CommentBody(c, reply = false) {
            if (c.replyCount > 0 && c.replies != null) {
                TextButton(onClick = { ui.toggleReplies(c, scope) }, modifier = Modifier.height(36.dp)) {
                    Text(if (r != null) "Hide replies" else if (c.replyCount == 1) "1 reply" else "${c.replyCount} replies")
                }
            }
        }
        if (r != null) {
            for (reply in r.items) CommentBody(reply, reply = true)
            when {
                r.loading -> Spinner()
                r.error != null -> Text(
                    r.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 60.dp, end = 16.dp, top = 6.dp),
                )
                r.next != null -> TextButton(onClick = { ui.moreReplies(c, scope) }, modifier = Modifier.padding(start = 48.dp)) {
                    Text("More replies")
                }
            }
        }
    }
}

/** Comments as a screen of their own (the wide layout's right pane). */
@Composable
fun CommentsPane(s: RemoteState, modifier: Modifier = Modifier) {
    val ui = rememberComments(s.now?.videoId, active = true)
    val scope = rememberCoroutineScope()
    val state = rememberLazyListState()
    LazyColumn(modifier.fillMaxSize().edgeFade(state), state = state) {
        commentItems(ui, scope)
        item(key = "c-pad") { Spacer(Modifier.height(24.dp)) }
    }
}
