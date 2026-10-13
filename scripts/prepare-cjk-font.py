#!/usr/bin/env python3
"""Optional font regeneration; builds use the already bundled static font.

Source: notofonts/noto-cjk/Sans/Variable/TTF/NotoSansCJKsc-VF.ttf.
Requires FontTools 4.61.1. Pass a downloaded, checksum-matching source file.
The SIL OFL license is bundled beside the font's notices.
"""
import hashlib
import pathlib
import sys
import copy
from collections import defaultdict
from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

source = pathlib.Path(sys.argv[1])
assert hashlib.sha256(source.read_bytes()).hexdigest() == '990c807e79c25662a5a9ecf7f971baeb2bf2eab9a559e5ecf15cdfdb8561d21f'
font = TTFont(source, recalcTimestamp=False)
instantiateVariableFont(font, {'wght': 400}, inplace=True)
# PDFBox encodes glyph IDs as CIDs. Shared glyphs would map multiple codepoints
# to the first Unicode alias (e.g. 文 -> Kangxi radical ⽂). Give every Unicode
# alias its own glyph ID, preserving the outline and metrics exactly.
options = subset.Options()
options.layout_features = []
subsetter = subset.Subsetter(options=options)
subsetter.populate(unicodes=font.getBestCmap().keys())
subsetter.subset(font)
by_glyph = defaultdict(list)
for code, glyph in font.getBestCmap().items(): by_glyph[glyph].append(code)
order = list(font.getGlyphOrder())
for glyph, codes in by_glyph.items():
    for code in codes[1:]:
        alias = f"readerka_uni{code:06X}"
        font['glyf'][alias] = copy.deepcopy(font['glyf'][glyph])
        font['hmtx'][alias] = font['hmtx'][glyph]
        if 'vmtx' in font: font['vmtx'][alias] = font['vmtx'][glyph]
        order.append(alias)
        for cmap in font['cmap'].tables:
            if cmap.isUnicode() and code in cmap.cmap: cmap.cmap[code] = alias
font.setGlyphOrder(order)
assert len(order) <= 65535
assert all(ord(c) in font.getBestCmap() for c in '中文日本語한국어Русский')
target = pathlib.Path(__file__).resolve().parents[1] / 'app/src/main/assets/fonts/Readerka-CJK-Regular.ttf'
font.save(target)
print('Generated static CJK font:', target.name)
