package com.dennycgan.hearcopy.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dennycgan.hearcopy.R

/** The outcome of the last key save or delete, shown under the key block. */
enum class KeyMessage { Invalid, SaveFailed, DeleteFailed }

/**
 * The key block's state and actions. [input] is held in memory only and
 * never written to saved instance state (ADR-013). [present] null: storage
 * not read yet, nothing shown.
 */
class ApiKeyUi(
    val present: Boolean?,
    val input: String,
    val hint: String?,
    val message: KeyMessage?,
    val busy: Boolean,
    val replacing: Boolean,
    val confirmingDelete: Boolean,
    val onInput: (String) -> Unit,
    val onSave: () -> Unit,
    val onReplace: () -> Unit,
    val onCancelReplace: () -> Unit,
    val onDeleteRequest: () -> Unit,
    val onDeleteConfirm: () -> Unit,
    val onDeleteDismiss: () -> Unit,
)

/**
 * Back arrow and title, the OpenAI API key block (field and Save; once
 * saved the last four characters, Replace and Delete), the Connection
 * block (Test transcription and its result; [connection] null: storage not
 * read yet, nothing shown) and the note on local encryption.
 */
@Composable
fun SettingsScreen(apiKey: ApiKeyUi, connection: ConnectionStatus?, onTest: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 16.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(text = stringResource(R.string.settings_title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.api_key_label),
                modifier = Modifier.padding(bottom = 8.dp),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            ApiKeyBlock(apiKey)
            connection?.let { ConnectionBlock(it, onTest) }
            Row(
                modifier = Modifier.padding(top = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SecondaryDot(modifier = Modifier.padding(top = 5.dp))
                Text(
                    text = stringResource(R.string.settings_key_note),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (apiKey.confirmingDelete) DeleteDialog(apiKey)
}

@Composable
private fun ApiKeyBlock(apiKey: ApiKeyUi) {
    val present = apiKey.present ?: return
    if (present && !apiKey.replacing) {
        val hint = apiKey.hint.orEmpty()
        val savedDescription = stringResource(R.string.api_key_saved, hint)
        Text(
            text = stringResource(R.string.api_key_masked, hint),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics { contentDescription = savedDescription },
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
        )
        ButtonRow {
            SecondaryButton(R.string.action_replace, enabled = !apiKey.busy, onClick = apiKey.onReplace)
            SecondaryButton(R.string.action_delete, enabled = !apiKey.busy, onClick = apiKey.onDeleteRequest)
        }
    } else {
        OutlinedTextField(
            value = apiKey.input,
            onValueChange = apiKey.onInput,
            modifier = Modifier.fillMaxWidth(),
            enabled = !apiKey.busy,
            placeholder = { Text(text = stringResource(R.string.api_key_placeholder)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
        )
        ButtonRow {
            Button(
                onClick = apiKey.onSave,
                enabled = !apiKey.busy,
                shape = RoundedCornerShape(7.dp),
                contentPadding = BUTTON_PADDING,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                ButtonText(R.string.action_save)
            }
            // Cancel only while replacing a saved key.
            if (present) {
                SecondaryButton(R.string.action_cancel, enabled = !apiKey.busy, onClick = apiKey.onCancelReplace)
            }
        }
    }
    apiKey.message?.let { message ->
        Text(
            text = stringResource(keyMessage(message)),
            modifier = Modifier.padding(top = 8.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ConnectionBlock(connection: ConnectionStatus, onTest: () -> Unit) {
    Text(
        text = stringResource(R.string.settings_connection_label),
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
    )
    SecondaryButton(R.string.action_test_transcription, enabled = connection.canTest, onClick = onTest)
    val line = connection.reason?.let { stringResource(connection.line, stringResource(it)) } ?: stringResource(connection.line)
    Text(
        text = line,
        modifier = Modifier.padding(top = 8.dp),
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = if (connection.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ButtonRow(content: @Composable () -> Unit) {
    Row(modifier = Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}

@Composable
private fun SecondaryButton(@StringRes label: Int, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(7.dp),
        contentPadding = BUTTON_PADDING,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        ButtonText(label)
    }
}

@Composable
private fun ButtonText(@StringRes label: Int) {
    Text(text = stringResource(label), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun DeleteDialog(apiKey: ApiKeyUi) {
    AlertDialog(
        onDismissRequest = apiKey.onDeleteDismiss,
        title = { Text(text = stringResource(R.string.api_key_delete_title)) },
        text = { Text(text = stringResource(R.string.api_key_delete_text)) },
        confirmButton = {
            TextButton(onClick = apiKey.onDeleteConfirm) {
                Text(text = stringResource(R.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = apiKey.onDeleteDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

@StringRes
private fun keyMessage(message: KeyMessage): Int = when (message) {
    KeyMessage.Invalid -> R.string.api_key_invalid
    KeyMessage.SaveFailed -> R.string.api_key_save_failed
    KeyMessage.DeleteFailed -> R.string.api_key_delete_failed
}

private val BUTTON_PADDING = PaddingValues(horizontal = 14.dp, vertical = 9.dp)
