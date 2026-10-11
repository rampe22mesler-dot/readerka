package org.readerka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

val LocalCompact = staticCompositionLocalOf { false }

/** Original 24px outline glyphs; no icon font, services or external resources. */
object Outline {
    private fun glyph(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
        PathParser().parsePathString(path).toNodes(), stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round).build()
    val File = glyph("PDF", "M6,3 H14 L19,8 V21 H6 Z M14,3 V8 H19 M9,12 H16 M9,16 H15")
    val Note = glyph("Заметка", "M5,3 H19 V21 H5 Z M8,8 H16 M8,12 H16 M8,16 H13")
    val Book = glyph("Чтение", "M3,5 Q7,3 12,6 Q17,3 21,5 V20 Q17,18 12,21 Q7,18 3,20 Z M12,6 V21")
    val Folder = glyph("Папка", "M3,7 V20 H21 V7 H12 L10,4 H3 Z")
    val Search = glyph("Поиск", "M16,10 A6,6 0,1 1,4 10 A6,6 0,1 1,16 10 M15,15 L21,21")
    val Settings = glyph("Настройки", "M4,6 H20 M4,12 H20 M4,18 H20 M8,3 V9 M16,9 V15 M10,15 V21")
    val Menu = glyph("Панель", "M3,4 H21 V20 H3 Z M9,4 V20 M5,8 H7 M5,12 H7")
    val More = glyph("Действия", "M12,4 V4.1 M12,12 V12.1 M12,20 V20.1")
    val Close = glyph("Закрыть", "M6,6 L18,18 M18,6 L6,18")
    val Plus = glyph("Добавить", "M12,4 V20 M4,12 H20")
    val Left = glyph("Назад", "M15,5 L8,12 L15,19")
    val Right = glyph("Вперёд", "M9,5 L16,12 L9,19")
    val Down = glyph("Развернуть", "M5,9 L12,16 L19,9")
    val Fullscreen = glyph("Полный экран", "M3,9 V3 H9 M15,3 H21 V9 M21,15 V21 H15 M9,21 H3 V15")
    val Help = glyph("Справка", "M21,12 A9,9 0,1 1,3 12 A9,9 0,1 1,21 12 M9,8 Q9,5 12,6 Q16,8 12,11 V14 M12,18 V18.1")
    val Split = glyph("Две области", "M3,4 H21 V20 H3 Z M12,4 V20")
    val Pin = glyph("Закрепить", "M8,3 H16 L15,9 L18,13 H6 L9,9 Z M12,13 V21")
    val Edit = glyph("Редактировать", "M4,16 L16,4 L20,8 L8,20 H4 Z M13,7 L17,11")
    val Preview = glyph("Просмотр", "M2,12 Q12,0 22,12 Q12,24 2,12 Z M15,12 A3,3 0,1 1,9 12 A3,3 0,1 1,15 12")
    val Save = glyph("Сохранить", "M4,3 H17 L21,7 V21 H4 Z M8,3 V9 H16 V3 M8,21 V14 H17 V21")
    val Bookmark = glyph("Закладка", "M6,3 H18 V21 L12,16 L6,21 Z")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun WorkspaceAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, selected: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(if (selected) MaterialTheme.colorScheme.secondaryContainer else if (hovered) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent, tween(120), label = "action-background")
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState(), focusable = false) {
        IconButton(onClick, modifier.onPreviewKeyEvent { event ->
            if (enabled && event.key in listOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Spacebar)) {
                if (event.type == KeyEventType.KeyDown) onClick()
                true
            } else false
        }.size(48.dp).background(background, RoundedCornerShape(6.dp)).then(if (focused) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)) else Modifier), enabled = enabled, interactionSource = interaction) {
            Icon(icon, label, tint = if (selected || focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** Finger-sized handle with a thin visual rule; presets are also available in Layout. */
@Composable fun ResizeHandle(label: String, onDrag: (Float) -> Unit, modifier: Modifier = Modifier, vertical: Boolean = true, onStart: () -> Unit = {}, onEnd: () -> Unit = {}, onCancel: () -> Unit = {}) {
    val density = LocalDensity.current
    val callback by rememberUpdatedState(onDrag)
    val startCallback by rememberUpdatedState(onStart)
    val endCallback by rememberUpdatedState(onEnd)
    val cancelCallback by rememberUpdatedState(onCancel)
    val origin = remember { mutableStateOf(Offset.Zero) }
    Box(modifier.then(if (vertical) Modifier.width(48.dp).fillMaxHeight() else Modifier.height(48.dp).fillMaxWidth())
        .testTag(label).onGloballyPositioned { origin.value = it.boundsInRoot().topLeft }.pointerInput(vertical) {
            var previous: Offset? = null
            detectDragGestures(onDragStart = { previous = null; startCallback() }, onDragEnd = { previous = null; endCallback() }, onDragCancel = { previous = null; cancelCallback() }) { change, amount ->
                val position = origin.value + change.position
                val movement = previous?.let { position - it } ?: amount
                previous = position
                change.consume()
                callback(with(density) { (if (vertical) movement.x else movement.y).toDp().value })
            }
        }, contentAlignment = Alignment.Center) {
        Box((if (vertical) Modifier.width(1.dp).fillMaxHeight() else Modifier.height(1.dp).fillMaxWidth()).background(MaterialTheme.colorScheme.outlineVariant))
        Box((if (vertical) Modifier.size(4.dp, 28.dp) else Modifier.size(28.dp, 4.dp)).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
    }
}
