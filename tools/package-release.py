#!/usr/bin/env python3
"""Package only the three production mods and the original Solstice shader pack."""
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    properties = dict(line.split('=', 1) for line in
                      (ROOT / 'gradle.properties').read_text().splitlines()
                      if '=' in line and not line.startswith('#'))
    version = properties['version']
    mods = [
        ('', 'minecraft-metal-renderer', 'minecraft_metal', version),
        ('shader-loader', 'minecraft-shader-loader', 'minecraft_shader_loader',
         properties['shader_loader_version']),
        ('scene-optimizer', 'minecraft-scene-optimizer', 'minecraft_scene_optimizer',
         properties['scene_optimizer_version']),
    ]
    payload = {}
    for module, name, mod_id, mod_version in mods:
        jar = ROOT / module / 'build/libs' / f'{name}-{mod_version}.jar'
        with zipfile.ZipFile(jar) as archive:
            metadata = json.loads(archive.read('fabric.mod.json'))
            assert metadata['id'] == mod_id and metadata['version'] == mod_version, jar
            assert metadata['environment'] == 'client', jar
            assert 'LICENSE' in archive.namelist(), jar
            assert not metadata.get('jars'), 'Unexpected bundled dependency'
            assert not any('/gametest/' in n or '/benchmark/' in n
                           or n.endswith('.java') for n in archive.namelist()), jar
            if mod_id == 'minecraft_metal':
                assert 'native/macos-arm64/libminecraft_metal.dylib' in archive.namelist()
        payload[f'mods/{jar.name}'] = jar.read_bytes()
    for name in ('Solstice-0.1.0.zip',):
        pack = ROOT / 'shader-loader/build/shaderpacks' / name
        with zipfile.ZipFile(pack) as archive:
            assert {'LICENSE', 'shaders/shaders.properties'} <= set(archive.namelist())
            assert not any(n.startswith('/') or '..' in Path(n).parts
                           for n in archive.namelist())
        payload[f'shaderpacks/{name}'] = pack.read_bytes()
    payload['config/minecraft-shader-loader.properties'] = (
        '# Solstice is selected by default. Restart Minecraft after changing packs.\n'
        'pack=Solstice-0.1.0.zip\nprofile=BALANCED\n').encode()
    for name in ('LICENSE', 'THIRD_PARTY_NOTICES.md'):
        payload[name] = (ROOT / name).read_bytes()
    payload['INSTALL.txt'] = (ROOT / 'docs/releases/bundle-install.txt').read_bytes()
    payload['PERFORMANCE.md'] = (ROOT / 'docs/releases/performance-settings.md').read_bytes()
    payload['SHA256SUMS'] = ''.join(f'{digest(data)}  {name}\n'
                                  for name, data in sorted(payload.items())).encode()

    output = ROOT / 'build/distributions' / f'v{version}'
    output.mkdir(parents=True, exist_ok=True)
    bundle = output / f'minecraft-metal-suite-{version}.zip'
    with zipfile.ZipFile(bundle, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(payload.items()):
            entry = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, data)
    assets = [bundle]
    for name, data in payload.items():
        if name.startswith(('mods/', 'shaderpacks/')):
            path = output / Path(name).name
            path.write_bytes(data)
            assets.append(path)
    sums = output / 'SHA256SUMS'
    sums.write_text(''.join(f'{digest(path.read_bytes())}  {path.name}\n'
                            for path in sorted(assets)))
    expected = {path.name for path in assets} | {'SHA256SUMS'}
    assert {path.name for path in output.iterdir()} == expected, 'Unexpected release files'
    print(f'Validated {len(mods)} mods, 1 original pack; bundle contains {len(payload)} files.')
    print(output)


if __name__ == '__main__':
    main()
