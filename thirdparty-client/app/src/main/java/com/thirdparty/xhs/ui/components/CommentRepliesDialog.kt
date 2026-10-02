@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thirdparty.xhs.App
import com.thirdparty.xhs.data.CommentReply
import com.thirdparty.xhs.ui.theme.AvatarSize
import com.thirdparty.xhs.ui.theme.Spacing

/**
 * Full reply thread for one comment.
 *
 * The comment list only embeds a short preview of the replies, so the rest is
 * fetched from `v2/note-comment/comment-reply-list`.
 *
 * **That endpoint is unreliable in practice**: it accepts the right parameters
 * (`{note_id, parent_comment_id, page}` — every other name returns result=-1)
 * and even echoes the correct `data_count`, yet its `list` comes back EMPTY even
 * for a comment whose reply exists in the inline preview. So the inline preview
 * is seeded into the dialog and the fetched batch is merged on top, which means
 * the dialog still shows real content instead of "暂无回复".
 */
@Composable
fun CommentRepliesDialog(
    noteId: Long,
    commentId: Int,
    commentUserName: String,
    totalCount: Int,
    /** replies the comment list already carried inline */
    preview: List<CommentReply>,
    onDismiss: () -> Unit
) {
    var replies by remember(commentId) { mutableStateOf(preview) }
    var page by remember(commentId) { mutableStateOf(0) }
    var loading by remember(commentId) { mutableStateOf(true) }
    var hasMore by remember(commentId) { mutableStateOf(true) }
    var exhausted by remember(commentId) { mutableStateOf(false) }

    suspend fun loadMore() {
        val next = page + 1
        val batch = runCatching { App.repo.commentReplies(noteId, commentId, next) }.getOrNull()
        if (batch != null && batch.isNotEmpty()) {
            page = next
            // the backend repeats entries across page boundaries
            replies = (replies + batch).distinctBy { it.replyId }
            hasMore = batch.isNotEmpty()
        } else {
            // empty list or error — stop offering "load more"
            hasMore = false
            exhausted = true
        }
        loading = false
    }

    LaunchedEffect(commentId) { loadMore() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (totalCount > 0) "共 $totalCount 条回复" else "回复",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            if (loading && replies.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(Spacing.l), Alignment.Center) {
                    LoadingIndicator(Modifier.size(24.dp))
                }
            } else if (replies.isEmpty()) {
                Text(
                    "暂无回复",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s)
                ) {
                    items(replies, key = { it.replyId }) { r ->
                        Row(Modifier.fillMaxWidth()) {
                            XhsAvatar(
                                url = r.headImg,
                                contentDescription = r.userName,
                                modifier = Modifier.size(AvatarSize.comment)
                            )
                            Spacer(Modifier.width(Spacing.s))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        r.userName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (r.replyToName.isNotBlank()) {
                                        Text(
                                            " 回复 @${r.replyToName}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(r.content, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (hasMore) {
                        item(key = "__more__") {
                            Box(
                                Modifier.fillMaxWidth().padding(Spacing.s),
                                contentAlignment = Alignment.Center
                            ) {
                                TextButton(onClick = { loading = true }) { Text("加载更多回复") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
