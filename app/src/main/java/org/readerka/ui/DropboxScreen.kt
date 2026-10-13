package org.readerka.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable fun DropboxSection(vm: LibraryViewModel) {
    val account by vm.dropboxAccount.collectAsState()
    val login by vm.dropboxLogin.collectAsState()
    val upload by vm.dropboxUpload.collectAsState()
    val busy by vm.busy.collectAsState()
    val context = LocalContext.current
    var appKey by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sendConfirmation by remember { mutableStateOf(false) }
    var forgetConfirmation by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Dropbox · ручной обмен", style = MaterialTheme.typography.titleMedium)
        Text("Книги и записи работают без аккаунта и сети. Dropbox передаёт полный ZIP библиотеки только по вашим командам. Автоматической синхронизации нет.")
        if (account == null) {
            Text("Используйте App key своего приложения Dropbox с доступом App folder и разрешениями account_info.read, files.metadata.read, files.content.read, files.content.write. App secret не требуется.")
            OutlinedTextField(appKey, { appKey = it }, label = { Text("Dropbox App key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            TextButton(enabled = busy == null && appKey.isNotBlank(), onClick = {
                vm.beginDropboxLogin(appKey)?.let { url ->
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
            }) { Text("Открыть вход в Dropbox") }
            if (login != null) {
                Text("После входа Dropbox покажет одноразовый код. Вставьте его здесь; он связан с PKCE-попыткой и не сохраняется в библиотеке.")
                OutlinedTextField(code, { code = it }, label = { Text("Одноразовый код") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(enabled = busy == null && code.isNotBlank(), onClick = { vm.finishDropboxLogin(code); code = "" }) { Text("Завершить вход") }
            }
        } else {
            Text("Аккаунт: $account")
            TextButton(enabled = busy == null, onClick = { sendConfirmation = true }) { Text("Отправить библиотеку") }
            TextButton(enabled = busy == null, onClick = vm::downloadDropbox) { Text("Скачать и сравнить библиотеку") }
            TextButton(enabled = busy == null, onClick = { forgetConfirmation = true }) { Text("Отключить аккаунт на этом устройстве") }
        }
    }
    if (sendConfirmation) AlertDialog(onDismissRequest = { sendConfirmation = false }, title = { Text("Отправить всю библиотеку в Dropbox?") },
        text = { Text("Будут отправлены PDF, цитаты, заметки, конспекты и поля. Черновики, локальные вкладки и токены в ZIP не входят. Доступ к аккаунту используется только для этого действия.") },
        confirmButton = { TextButton(onClick = { sendConfirmation = false; vm.prepareDropboxUpload() }) { Text("Продолжить") } },
        dismissButton = { TextButton(onClick = { sendConfirmation = false }) { Text("Отмена") } })
    upload?.let { pending -> AlertDialog(onDismissRequest = { if (busy == null) vm.cancelDropboxUpload() },
        title = { Text(if (pending.previous == null) "Создать копию в Dropbox?" else "Заменить облачную копию?") },
        text = { Text(if (pending.previous == null) "В папке приложения будет создан Readerka-library.zip." else "Облачная копия ${pending.previous!!.modified} будет заменена. Если её изменит другое устройство после этой проверки, Dropbox отклонит выгрузку по ревизии.") },
        confirmButton = { TextButton(enabled = busy == null, onClick = vm::uploadDropbox) { Text(if (pending.previous == null) "Создать копию" else "Подтверждаю замену") } },
        dismissButton = { TextButton(enabled = busy == null, onClick = vm::cancelDropboxUpload) { Text("Отмена") } }) }
    if (forgetConfirmation) AlertDialog(onDismissRequest = { forgetConfirmation = false }, title = { Text("Отключить Dropbox?") },
        text = { Text("Локальный токен будет удалён. Библиотека и облачный ZIP сохранятся. Отозвать разрешение приложения можно в настройках Dropbox.") },
        confirmButton = { TextButton(onClick = { forgetConfirmation = false; vm.disconnectDropbox() }) { Text("Отключить") } },
        dismissButton = { TextButton(onClick = { forgetConfirmation = false }) { Text("Отмена") } })
}
