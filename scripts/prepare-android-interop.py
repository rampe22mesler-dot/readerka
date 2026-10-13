#!/usr/bin/env python3
"""Augment the unchanged desktop fixture with all ZIP 5 field categories."""
import hashlib
import json
import pathlib
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
destination = root / 'app/build/generated/interop-assets/interop'
destination.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(root / 'windows/desktop/build/interop/to-android.zip') as archive:
    files = {name: archive.read(name) for name in archive.namelist()}
data = json.loads(files['library.json'])
data['researchFields'] = [{
    'id': 'interop-field', 'title': 'Синтетический проект',
    'placements': [
        dict(id='interop-card-a', entryId='interop-quote', x=-30.0, y=50.0, width=320.0, height=280.0, kind='Quote', projectComment='Аргумент в этом проекте', groupId='interop-group'),
        dict(id='interop-card-b', entryId='interop-quote', x=440.0, y=75.0, width=300.0, height=260.0, kind='Quote', projectComment='Другое применение той же цитаты', groupId=None),
        dict(id='interop-card-c', entryId='interop-note', x=40.0, y=400.0, width=300.0, height=260.0, kind='Note', projectComment='', groupId='interop-group'),
    ],
    'edges': [{'id': 'interop-edge', 'from': 'interop-card-a', 'to': 'interop-card-c', 'type': 'Supports', 'label': 'Основание'}],
    'groups': [dict(id='interop-group', title='Группа аргументов')],
    'zoom': .75, 'viewX': -20.0, 'viewY': 30.0, 'sequence': ['interop-card-c', 'interop-card-a'],
}]
files['library.json'] = json.dumps(data, ensure_ascii=False, separators=(',', ':')).encode()
files['manifest.json'] = json.dumps(dict(version=5, sha256={name: hashlib.sha256(value).hexdigest() for name, value in files.items() if name != 'manifest.json'})).encode()
with zipfile.ZipFile(destination / 'to-android.zip', 'w', compression=zipfile.ZIP_DEFLATED) as archive:
    for name, value in files.items():
        archive.writestr(name, value)
(destination / 'expected.json').write_bytes(files['library.json'])
print('Generated synthetic ZIP 5: complete model, repeated placements, groups, relations, project comments, viewport, sequence.')

# The existing desktop DjVu resources are synthetic images with synthetic OCR.
formats = destination.parent / 'formats'
formats.mkdir(parents=True, exist_ok=True)
for name in ['mixed.djvu', 'rotated.djvu']:
    (formats / name).write_bytes((root / 'windows/desktop/src/test/resources/formats' / name).read_bytes())

import base64
(formats / 'two-pages.tiff').write_bytes(base64.b64decode('SUkqAAgAAAAKAAABBAABAAAACAAAAAEBBAABAAAADAAAAAIBAwADAAAAhgAAAAMBAwABAAAAAQAAAAYBAwABAAAAAgAAABEBBAABAAAAjAAAABUBAwABAAAAAwAAABYBBAABAAAADAAAABcBBAABAAAAIAEAABwBAwABAAAAAQAAALgBAAAIAAgACAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAAAAAAASUkqAAgAAAAKAAABBAABAAAACAAAAAEBBAABAAAADAAAAAIBAwADAAAANgIAAAMBAwABAAAAAQAAAAYBAwABAAAAAgAAABEBBAABAAAAPAIAABUBAwABAAAAAwAAABYBBAABAAAADAAAABcBBAABAAAAIAEAABwBAwABAAAAAQAAAAAAAAAIAAgACAAAAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAP8AAAAA'))

(formats / "two-frames.gif").write_bytes(base64.b64decode('R0lGODlhCAAMAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQICgAAACwAAAAACAAMAAAIEgABCBxIsKDBgwgTKlzIsGHBgAAh+QQICgAAACwAAAAACAAMAIEAAP8AAAAAAAAAAAAIEgABCBxIsKDBgwgTKlzIsGHBgAA7'))
(formats / "scan.bmp").write_bytes(base64.b64decode('Qk1WAQAAAAAAADYAAAAoAAAACAAAAAwAAAABABgAAAAAACABAADEDgAAxA4AAAAAAAAAAAAAAAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/AAD/'))
