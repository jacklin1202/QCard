package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.telegram.TelegramConnectionState
import com.wangzan101.mika.telegram.TelegramRuntime
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.CapabilityTag
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.StateCard
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 设置首页的状态胶囊；未保存 Bot 不影响页面的「一切就绪」检查。 */
@Composable
internal fun TelegramEntryStatus(state: TelegramConnectionState, hasBot: Boolean) {
    val (text, tone) = when (state) {
        TelegramConnectionState.Connected -> R.string.telegram_status_connected to PillTone.Ok
        TelegramConnectionState.Connecting -> R.string.telegram_status_connecting to PillTone.Accent
        TelegramConnectionState.Off -> R.string.telegram_status_paused to PillTone.Neutral
        is TelegramConnectionState.Failed -> R.string.telegram_status_failed to PillTone.Warn
        TelegramConnectionState.NeedsBinding -> if (hasBot) {
            R.string.telegram_status_rebind to PillTone.Warn
        } else R.string.telegram_status_disconnected to PillTone.Neutral
    }
    StatusPill(stringResource(text), tone)
}

/** 设置 › Telegram：粘贴 Bot 令牌（getMe 验证后才保存），再用一次性绑定码认领主人。 */
@Composable
fun TelegramSettingsScreen(runtime: TelegramRuntime, onBack: () -> Unit) {
    val state by runtime.state.collectAsState()
    val scope = rememberCoroutineScope()
    var hasBot by remember { mutableStateOf(runtime.store.bot() != null) }
    var botName by remember { mutableStateOf(runtime.store.bot()?.username.orEmpty()) }
    var ownerId by remember { mutableStateOf(runtime.store.ownerId()) }
    var tokenInput by remember { mutableStateOf("") }
    var bindCode by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmUnbind by remember { mutableStateOf(false) }
    val actionFailedText = stringResource(R.string.telegram_action_failed)

    // 页面开着时每 2 秒回读一次 store：/bind 是在 Telegram 那头完成的，runtime 的
    // connection state 不一定跟着变，只靠 state 触发会让主人栏一直显示未绑定。
    LaunchedEffect(Unit) {
        while (true) {
            hasBot = runtime.store.bot() != null
            botName = runtime.store.bot()?.username.orEmpty()
            ownerId = runtime.store.ownerId()
            delay(2000)
        }
    }

    fun runAction(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message?.takeIf { it.isNotBlank() } ?: actionFailedText
            } finally {
                busy = false
            }
        }
    }

    PageScaffold(stringResource(R.string.telegram_title), onBack) {
        val status = telegramStateCopy(state, hasBot)
        StateCard(MikaIcons.Chats, status.first, status.second, status.third, Modifier.testTag("telegram-status"))

        GroupLabel(stringResource(R.string.telegram_how))
        GroupCard {
            Text(
                stringResource(R.string.telegram_how_body), Modifier.padding(16.dp),
                style = MikaType.Body, color = MikaTheme.colors.ink2,
            )
        }

        if (!hasBot || state == TelegramConnectionState.NeedsBinding) {
            // token 被 Telegram 拒收（NeedsBinding）时也要能直接换新 token：
            // 同一个 Bot 换 token 是轮替，绑定与 pending 都保留，不必先移除 Bot。
            GroupLabel(stringResource(R.string.telegram_token_label))
            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text(stringResource(R.string.telegram_token_hint)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("telegram-token"),
            )
            MikaButton(
                stringResource(R.string.telegram_save_test), {
                    val token = tokenInput
                    runAction {
                        val bot = runtime.validateToken(token)
                        withContext(Dispatchers.IO) { runtime.saveBot(bot) }
                        tokenInput = ""
                        bindCode = null
                    }
                }, Modifier.fillMaxWidth(), enabled = !busy && tokenInput.isNotBlank(),
            )
        }

        if (hasBot) {
            GroupLabel(stringResource(R.string.telegram_connection))
            GroupCard {
                val receiving = state !is TelegramConnectionState.Off && state !is TelegramConnectionState.NeedsBinding
                ListRow(
                    MikaIcons.Message, stringResource(R.string.telegram_receive),
                    stringResource(R.string.telegram_receive_sub), first = true,
                    onClick = if (busy) null else ({
                        runAction { withContext(Dispatchers.IO) { runtime.setEnabled(!receiving) } }
                    }),
                ) {
                    MikaSwitch(receiving, null, Modifier.testTag("telegram-enabled"), enabled = !busy)
                }
                ListRow(MikaIcons.Chats, stringResource(R.string.telegram_bound_account)) {
                    RowValue(if (botName.isNotBlank()) "@$botName" else "—")
                }
                ListRow(MikaIcons.Shield, stringResource(R.string.telegram_owner)) {
                    RowValue(if (ownerId != 0L) ownerId.toString() else stringResource(R.string.telegram_owner_unbound))
                }
            }

            if (ownerId == 0L) {
                GroupLabel(stringResource(R.string.telegram_bind_title))
                GroupCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.telegram_bind_body), Modifier.padding(bottom = 12.dp),
                            style = MikaType.Body, color = MikaTheme.colors.ink2,
                        )
                        bindCode?.let { code ->
                            Text(code, style = MikaType.BodyStrong, color = MikaTheme.colors.ink, modifier = Modifier.padding(bottom = 4.dp).testTag("telegram-bind-code"))
                            Text(
                                stringResource(R.string.telegram_bind_code_valid), Modifier.padding(bottom = 12.dp),
                                style = MikaType.Caption, color = MikaTheme.colors.ink2,
                            )
                        }
                        MikaButton(
                            stringResource(if (bindCode == null) R.string.telegram_bind_generate else R.string.telegram_bind_regenerate),
                            { bindCode = runtime.store.newBindCode(System.currentTimeMillis()) },
                            Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, enabled = !busy,
                        )
                    }
                }
            }

            TextButton({ confirmUnbind = true }, Modifier.fillMaxWidth(), enabled = !busy) {
                Text(stringResource(R.string.telegram_unbind), color = MikaTheme.colors.bad)
            }
        }
        error?.let { FooterNote(it, color = MikaTheme.colors.bad) }

        GroupLabel(stringResource(R.string.telegram_capabilities))
        GroupCard {
            ListRow(null, stringResource(R.string.telegram_capability_text), first = true) {
                CapabilityTag(stringResource(R.string.telegram_supported))
            }
            ListRow(null, stringResource(R.string.telegram_capability_replies)) {
                CapabilityTag(stringResource(R.string.telegram_supported))
            }
            ListRow(null, stringResource(R.string.telegram_capability_phone), stringResource(R.string.telegram_capability_phone_sub)) {
                CapabilityTag(stringResource(R.string.telegram_phone_only))
            }
            ListRow(null, stringResource(R.string.telegram_capability_later)) {
                CapabilityTag(stringResource(R.string.telegram_later))
            }
        }
        FooterNote(stringResource(R.string.telegram_footer))
    }

    if (confirmUnbind) {
        MikaDialog(
            onDismissRequest = { confirmUnbind = false },
            title = { Text(stringResource(R.string.telegram_unbind_title)) },
            text = { Text(stringResource(R.string.telegram_unbind_body)) },
            confirmButton = {
                TextButton({
                    confirmUnbind = false
                    runAction {
                        withContext(Dispatchers.IO) { runtime.unbind() }
                        bindCode = null
                    }
                }) { Text(stringResource(R.string.telegram_unbind), color = MikaTheme.colors.bad) }
            },
            dismissButton = { TextButton({ confirmUnbind = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun telegramStateCopy(state: TelegramConnectionState, hasBot: Boolean): Triple<String, String, TileTone> = when (state) {
    TelegramConnectionState.Connected -> Triple(
        stringResource(R.string.telegram_status_connected), stringResource(R.string.telegram_state_connected_detail), TileTone.Ok)
    TelegramConnectionState.Connecting -> Triple(
        stringResource(R.string.telegram_status_connecting), stringResource(R.string.telegram_state_connecting_detail), TileTone.Accent)
    TelegramConnectionState.Off -> Triple(
        stringResource(R.string.telegram_status_paused), stringResource(R.string.telegram_state_paused_detail), TileTone.Neutral)
    is TelegramConnectionState.Failed -> Triple(
        stringResource(R.string.telegram_status_failed), stringResource(R.string.telegram_state_failed_detail), TileTone.Bad)
    TelegramConnectionState.NeedsBinding -> if (hasBot) Triple(
        stringResource(R.string.telegram_status_rebind), stringResource(R.string.telegram_state_rebind_detail), TileTone.Warn)
    else Triple(
        stringResource(R.string.telegram_status_disconnected), stringResource(R.string.telegram_state_disconnected_detail), TileTone.Neutral)
}
