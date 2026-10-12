DjVuLibre 3.5.29, Windows command-line decoder and OCR text reader.
These files were extracted without installation from the upstream package:
https://sourceforge.net/projects/djvu/files/DjVuLibre_Windows/3.5.29%2B4.12/DjVuLibre-3.5.29_DjView-4.12_Setup.exe/download
Package SHA-256: 92233fbf891c63f3fb7a0b5e1ce108baa4c29a40c89a442d1313f883aae84670
Upstream sources and licensing: https://djvu.sourceforge.net/
License: GNU GPL v2 or later; see COPYING.txt, extracted from this package.
Only ddjvu, djvused and their DLL dependencies are included. The viewer,
network/Qt libraries, installer and shell integration are not included.
Each import extracts the decoder to an isolated temporary directory and
deletes it on completion. The decoder does not download book resources.

The app-local x86 msvcp140.dll and vcruntime140.dll (14.23.27820.0) were extracted from the Microsoft-signed vcredist_x86.exe in the same upstream package. Their license terms are in third-party/licenses/Microsoft-Visual-Cpp-2019. Windows 10/11 provides the Universal CRT system APIs.
