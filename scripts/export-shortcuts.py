#!/usr/bin/env python3
"""Export the application's shortcut registry for the user guide."""
from pathlib import Path
import re

root = Path(__file__).resolve().parent.parent
source = (root / 'app/src/main/java/org/readerka/ui/AppShortcuts.kt').read_text()
lines = ['# Горячие клавиши Readerka', '', 'Список создаётся из реестра приложения командой `python3 scripts/export-shortcuts.py`. Все эти сочетания также доступны в справке (`F1`), во вкладке «Горячие клавиши» с поиском.', '', 'Одно сочетание имеет одно назначение. Поиск и свойства относятся к активному PDF или документу. Команды редактора работают при открытом редакторе; команды выделения PDF — после выделения текста. `Ctrl+B` форматирует текст; проводник переключается через `Ctrl+Shift+B`.', '']
count = 0
for section, title in [('global', 'Общие команды и области'), ('editor', 'Редактор'), ('pdf', 'PDF и выделение')]:
    match = re.search(r'val ' + section + r' = listOf\((.*?)\n    \)', source, re.S)
    if not match:
        raise ValueError('Missing shortcut group: ' + section)
    bindings = re.findall(r'AppShortcut\("([^"]+)", "([^"]+)", "([^"]+)"', match.group(1))
    if not bindings:
        raise ValueError('Empty shortcut group: ' + section)
    lines.extend(['## ' + title, '', '| Клавиши | Действие |', '|---|---|'])
    lines.extend('| ' + keys + ' | ' + label + ' |' for _, label, keys in bindings)
    lines.append('')
    count += len(bindings)
lines.extend(['## Стандартное управление', '', '- `Tab` / `Shift+Tab` — следующий / предыдущий элемент; `Enter` / пробел — выполнить действие.', '- В текстовых полях `Ctrl+C`, `Ctrl+X`, `Ctrl+V`, `Ctrl+A` — копировать, вырезать, вставить, выделить всё. `Ctrl+Shift+V` — системная вставка без оформления, если поддерживается клавиатурой.', '- В PDF стрелки и `Page Up` / `Page Down` листают страницы; `Home` / `End` переходят к первой / последней.', '- `Esc` сначала закрывает меню, панель или выделение; затем выходит из полного экрана. `F11` непосредственно переключает полный экран.', '- Цвет, настройки, фильтры, выбор книги/темы/версии и другие поля доступны клавишами Tab, стрелками и Enter; отдельные сочетания для каждого значения не назначены.', ''])
(root / 'docs/SHORTCUTS.md').write_text('\n'.join(lines))
print(f'Exported {count} shortcuts to docs/SHORTCUTS.md')
