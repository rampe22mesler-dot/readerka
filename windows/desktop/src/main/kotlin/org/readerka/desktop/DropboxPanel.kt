package org.readerka.desktop

import java.awt.BorderLayout
import java.awt.Desktop
import java.net.URI
import javax.swing.*

class DropboxPanel(private val app: DesktopWindow) : JPanel(BorderLayout(0, 12)) {
    private val store = DropboxAccountStore(app.session.repository.directory)
    private val key = JTextField(app.session.repository.preference("dropbox-app-key").orEmpty(), 30).apply { name = "readerka-dropbox-app-key" }
    private val status = JLabel()
    private val login = button("Войти в Dropbox…") { connect() }.apply { name = "readerka-dropbox-login" }
    private val logout = button("Отключить на этом устройстве") { disconnect() }
    private val upload = button("Выгрузить в Dropbox…") { close(); app.uploadDropbox() }.apply { name = "readerka-dropbox-upload" }
    private val download = button("Загрузить из Dropbox…") { close(); app.downloadDropbox() }.apply { name = "readerka-dropbox-download" }

    init {
        name = "readerka-dropbox"
        border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
        add(fieldPanel("Аккаунт" to status, "App key приложения Dropbox" to key,
            "Подключение" to flow(login, logout)), BorderLayout.NORTH)
        add(JScrollPane(JTextArea(
            "Обмен выполняется только по кнопкам и после подтверждения. Сохранение записей в Readerka ничего не отправляет в Dropbox.\n\n" +
            "В Dropbox хранится полный ZIP библиотеки: книги в PDF, записи, папки, закладки, история и исследовательские поля. " +
            "Выгрузка заменяет Readerka-library.zip в папке приложения Dropbox. Если другая Readerka успела изменить копию, выгрузка остановится.\n\n" +
            "Загрузка сначала скачивает ZIP, затем показывает сравнение и выбор режима импорта. До применения создаётся местная резервная копия.\n\n" +
            "Первое подключение: в Dropbox App Console создайте приложение Scoped access → App folder. " +
            "В Permissions включите account_info.read, files.metadata.read, files.content.read и files.content.write, нажмите Submit. " +
            "Скопируйте App key со вкладки Settings в поле выше. App secret не требуется. " +
            "На другом устройстве укажите тот же App key, чтобы открыть ту же облачную копию.\n\n" +
            "«Войти в Dropbox» откроет системный браузер. Разрешите доступ и вставьте выданный Dropbox код в Readerka. " +
            (if (DesktopPlatform.isMac) "Вход хранится в Связке ключей macOS и не входит в ZIP. "
                else "Вход хранится с защитой Windows для текущего пользователя и не входит в ZIP. ") +
            "Архив библиотеки не зашифрован."
        ).apply {
            isEditable = false; lineWrap = true; wrapStyleWord = true
            background = this@DropboxPanel.background; caretPosition = 0
        }).apply { border = BorderFactory.createEmptyBorder() }, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(flow(button("Dropbox App Console…") { app.safe { Desktop.getDesktop().browse(URI("https://www.dropbox.com/developers/apps")) } }), BorderLayout.NORTH)
            add(flow(upload, download), BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        refresh()
    }

    private fun close() { SwingUtilities.getWindowAncestor(this)?.dispose() }
    private fun refresh() {
        val connected = store.exists()
        status.text = if (connected) "Подключён: ${app.session.repository.preference("dropbox-account").orEmpty()}" else "Dropbox не подключён"
        key.isEnabled = !connected; login.isEnabled = !connected; logout.isEnabled = connected
        upload.isEnabled = connected; download.isEnabled = connected
    }

    private fun connect() = app.safe {
        val attempt = DropboxClient.Login(key.text.trim())
        Desktop.getDesktop().browse(attempt.authorization)
        val code = JPasswordField(32).apply { name = "readerka-dropbox-code" }
        val choice = JOptionPane.showConfirmDialog(this, fieldPanel(
            "В браузере разрешите доступ к Dropbox." to JLabel("Скопируйте выданный код и вставьте его ниже."),
            "Код входа (одноразовый)" to code,
        ), "Вход в Dropbox", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (choice != JOptionPane.OK_OPTION) return@safe
        val value = String(code.password)
        code.text = ""
        if (value.isBlank()) return@safe
        app.background("Вход в Dropbox…", {
            val credentials = DropboxClient().login(attempt, value)
            store.save(credentials)
            credentials
        }) { credentials ->
            app.session.repository.persistPreferences(mapOf("dropbox-app-key" to credentials.appKey, "dropbox-account" to credentials.account))
            refresh()
        }
    }

    private fun disconnect() {
        if (!confirmDropbox(this, "Отключить Dropbox на этом устройстве?\nСохранённый вход будет удалён. Облачная копия останется в Dropbox.")) return
        app.safe {
            store.forget()
            app.session.repository.persistPreference("dropbox-account", "")
            refresh()
        }
    }
}

/** Destructive/remote operations never use an affirmative default. */
internal fun confirmDropbox(owner: java.awt.Component, message: String): Boolean = JOptionPane.showOptionDialog(
    owner, message, "Подтверждение Dropbox", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
    null, arrayOf("Продолжить", "Отмена"), "Отмена",
) == 0
