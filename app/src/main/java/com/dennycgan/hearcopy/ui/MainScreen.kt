package com.dennycgan.hearcopy.ui

import android.content.ClipData
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.dennycgan.hearcopy.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Header (name, slogan, gear), status card, the last transcript with Copy
 * all, and the footer line. The whole screen scrolls: a transcript of a
 * 5 min clip is far longer than the screen.
 */
@Composable
fun MainScreen(
    card: StatusCard,
    transcript: String?,
    onOpenSettings: () -> Unit,
    onCardTap: (CardTap) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()),
    ) {
        Header(onOpenSettings)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Column(modifier = Modifier.padding(20.dp)) {
            StatusCardView(card, onCardTap)
            Spacer(modifier = Modifier.size(24.dp))
            TranscriptSection(transcript)
            Footer()
        }
    }
}

@Composable
private fun Header(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 24.dp, end = 12.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column {
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
            )
            Spacer(modifier = Modifier.size(5.dp))
            Text(
                text = stringResource(R.string.main_slogan),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                painter = painterResource(R.drawable.ic_gear),
                contentDescription = stringResource(R.string.action_open_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusCardView(card: StatusCard, onCardTap: (CardTap) -> Unit) {
    val tappable = card.tap != CardTap.None
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp))
            .then(
                if (tappable) {
                    Modifier.clickable(role = Role.Button) { onCardTap(card.tap) }
                } else {
                    Modifier
                },
            )
            .padding(15.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardIconView(card.icon)
            Text(text = stringResource(card.title), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        val description = cardDescription(card)
        if (description != null) {
            Text(
                text = description,
                modifier = Modifier.padding(top = 8.dp),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun cardDescription(card: StatusCard): String? {
    val res = card.description ?: return null
    val text = card.recordingSinceMs?.let { stringResource(res, recordingTimer(it)) } ?: stringResource(res)
    return if (card.tryAgain) stringResource(R.string.setup_try_again, text) else text
}

/**
 * The recording time from [startedAtMs] (elapsedRealtime), updated on
 * whole seconds only while the Activity is at least started.
 */
@Composable
private fun recordingTimer(startedAtMs: Long): String {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val started = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAtMs, started) {
        while (started) {
            now = SystemClock.elapsedRealtime()
            delay(msToNextSecond(now - startedAtMs))
        }
    }
    val seconds = recordedSeconds(now - startedAtMs)
    return stringResource(R.string.touch_timer, seconds / 60, seconds % 60)
}

@Composable
private fun CardIconView(icon: CardIcon) {
    Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        val drawable = when (icon) {
            CardIcon.Spinner -> null
            CardIcon.Alert -> R.drawable.ic_alert_circle
            CardIcon.Check -> R.drawable.ic_check_circle
            CardIcon.Stop -> R.drawable.ic_stop_square
        }
        if (drawable == null) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                painter = painterResource(drawable),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The last transcript: natively selectable text and Copy all at the bottom
 * right, or an empty state. Never cleared by a failure or a new recording.
 */
@Composable
private fun TranscriptSection(transcript: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = stringResource(R.string.transcript_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = stringResource(R.string.transcript_caption),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // Copy all sits at the bottom of the box: SpaceBetween within the
    // minimum height, below the text once the text is taller.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 195.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .padding(15.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        if (transcript == null) {
            Text(
                text = stringResource(R.string.transcript_empty),
                fontSize = 14.sp,
                lineHeight = 24.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SelectionContainer {
                Text(text = transcript, fontSize = 14.sp, lineHeight = 24.sp)
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) {
            CopyAllButton(transcript)
        }
    }
}

@Composable
private fun CopyAllButton(transcript: String?) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.transcript_title)
    // Android 12 and lower show no confirmation of their own. Reset for each new transcript.
    var copied by remember(transcript) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_MS)
            copied = false
        }
    }
    Button(
        onClick = {
            val text = transcript ?: return@Button
            scope.launch {
                clipboard.setClipEntry(ClipData.newPlainText(label, text).toClipEntry())
                copied = true
            }
        },
        enabled = transcript != null,
        shape = RoundedCornerShape(7.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_copy),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = stringResource(if (copied) R.string.transcript_copied else R.string.action_copy_all),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Footer() {
    Row(
        modifier = Modifier.padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SecondaryDot()
        Text(
            text = stringResource(R.string.transcript_footer),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The design's small dot in the secondary colour (footer and settings note). */
@Composable
internal fun SecondaryDot(modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(7.dp).background(MaterialTheme.colorScheme.secondary, CircleShape))
}

private const val COPIED_MS = 1500L
