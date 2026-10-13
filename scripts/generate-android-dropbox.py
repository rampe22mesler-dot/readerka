#!/usr/bin/env python3
"""Use the unchanged desktop HTTP/PKCE protocol with an API-28 stream adapter."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = root / 'windows/desktop/src/main/kotlin/org/readerka/desktop/DropboxClient.kt'
destination = root / 'app/build/generated/dropbox-protocol/org/readerka/dropbox/DropboxClient.kt'
destination.parent.mkdir(parents=True, exist_ok=True)
text = source.read_text().replace('package org.readerka.desktop', 'package org.readerka.dropbox')
text = text.replace('.readNBytes(', '.readBounded(')
text += '''
// InputStream.readNBytes is unavailable on API 28. Semantics match the desktop helper.
private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
    require(limit >= 0)
    val output = java.io.ByteArrayOutputStream(minOf(limit, 65536))
    val buffer = ByteArray(minOf(limit.coerceAtLeast(1), 65536))
    while (output.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (n < 0) break
        if (n == 0) { val single = read(); if (single < 0) break; output.write(single) }
        else output.write(buffer, 0, n)
    }
    return output.toByteArray()
}
'''
if not destination.exists() or destination.read_text() != text:
    destination.write_text(text)
