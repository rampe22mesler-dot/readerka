"""Package verified Windows Java + platform-neutral Kotlin/Swing libraries. No user data."""
import hashlib
import json
import shutil
import struct
import sys
import zipfile
from pathlib import Path

JRE_SHA256 = "d35b05f4832215d8877d0dbf15c6370c854d7d5b812f890a9c0db8ad412a6bf2"
root = Path(__file__).resolve().parent.parent
runtime_zip = Path(sys.argv[1])
assert hashlib.sha256(runtime_zip.read_bytes()).hexdigest() == JRE_SHA256, "Windows Java checksum mismatch"
package = root / "desktop/build/distribution/Readerka"
if package.exists():
    shutil.rmtree(package)
package.mkdir(parents=True)
shutil.copytree(root / "desktop/build/windows/lib", package / "lib")
with zipfile.ZipFile(runtime_zip) as archive:
    prefix = archive.namelist()[0].split("/")[0] + "/"
    for member in archive.infolist():
        relative = member.filename.removeprefix(prefix)
        assert ".." not in Path(relative).parts and not relative.startswith("/")
        if not relative or member.is_dir():
            continue
        destination = package / "runtime" / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(archive.read(member))

# Both launchers use only the included runtime and preserve paths containing spaces or Cyrillic.
launch = '@echo off\r\nsetlocal\r\nstart "" "%~dp0runtime\\bin\\javaw.exe" -Dfile.encoding=UTF-8 -cp "%~dp0lib\\*" org.readerka.desktop.MainKt %*\r\n'
diagnostics = '@echo off\r\nsetlocal\r\n"%~dp0runtime\\bin\\java.exe" -Dfile.encoding=UTF-8 -cp "%~dp0lib\\*" org.readerka.desktop.MainKt %*\r\npause\r\n'
(package / "Readerka.cmd").write_bytes(launch.encode("ascii"))
(package / "Diagnostics.cmd").write_bytes(diagnostics.encode("ascii"))
readme = r"""Readerka 0.9.1 Desktop Preview · Windows 10/11 x64

1. Полностью распакуйте ZIP. Не запускайте приложение внутри архива.
2. Откройте Readerka.cmd. Java устанавливать не нужно.
3. Файл → Импорт ZIP: откройте архив библиотеки с планшета.
4. Настройте книги, записи, конспекты, теги и папки.
5. Файл → Экспорт ZIP: все открытые черновики будут сохранены.
6. На планшете установите Readerka Preview 0.9.1 поверх Preview 0.9.
   Настройки → Восстановить ZIP → Обновить из архива.

Для первого импорта в пустую библиотеку подходит Android 0.9.
Для обновления совпадающих записей используйте 0.9.1 с выбором конфликтов.
Импорт создаёт резервную копию. На ПК: %LOCALAPPDATA%\Readerka\backups.
Данные приложения: %LOCALAPPDATA%\Readerka. ZIP вручную переносится
между устройствами; автоматической синхронизации нет. Не переносите SQLite.

F1: справка, функции и все горячие клавиши. F11: полный экран.
Ctrl+S: сохранить. Ctrl+B/I/U: форматирование. Ctrl+Shift+K: каталог вставки.
Все команды также доступны в меню; контекстные действия — по видимой ⋮.

Diagnostics.cmd позволяет увидеть ошибку запуска в отдельном окне.
Можно перетащить PDF или ZIP на Readerka.cmd для открытия/импорта.
Подробности: WINDOWS.md. Личные данные и тестовые библиотеки в ZIP не включены.
"""
(package / "README.txt").write_text(readme, encoding="utf-8-sig")
for document in ["WINDOWS.md", "DESKTOP_VALIDATION.md"]:
    if (root / "docs" / document).exists():
        shutil.copy2(root / "docs" / document, package / document)

# Confirm x64 PE binaries and Windows SQLite native library are actually included.
for binary in [package / "runtime/bin/java.exe", package / "runtime/bin/javaw.exe", package / "runtime/bin/server/jvm.dll"]:
    data = binary.read_bytes()
    assert data[:2] == b"MZ"
    offset = struct.unpack_from("<I", data, 0x3C)[0]
    assert data[offset:offset+4] == b"PE\0\0" and struct.unpack_from("<H", data, offset+4)[0] == 0x8664
sqlite = next((package / "lib").glob("sqlite-jdbc-*.jar"))
with zipfile.ZipFile(sqlite) as archive:
    assert "org/sqlite/native/Windows/x86_64/sqlitejdbc.dll" in archive.namelist()
with zipfile.ZipFile(package / "lib/desktop.jar") as archive:
    assert "org/readerka/desktop/MainKt.class" in archive.namelist()
assert not list(package.rglob("*.sqlite")) and not list(package.rglob("*.db")) and not list(package.rglob("*.keystore"))
manifest = {str(p.relative_to(package)).replace("\\", "/"): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(package.rglob("*")) if p.is_file()}
(package / "checksums.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
output = root / "downloads/readerka-0.9.1-desktop-windows-x64.zip"
output.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for item in sorted(package.rglob("*")):
        if item.is_file():
            archive.write(item, "Readerka/" + item.relative_to(package).as_posix())
assert output.stat().st_size < 100 * 1024 * 1024, "GitHub single file limit"
checksum = hashlib.sha256(output.read_bytes()).hexdigest()
output.with_suffix(".zip.sha256").write_text(f"{checksum}  {output.name}\n", encoding="ascii")
print(f"Windows portable package verified: {output.name}, {output.stat().st_size:,} bytes, SHA-256 {checksum}")
