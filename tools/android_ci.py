"""Android CI metadata, signed artifacts and channel publishing (standard library only)."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SIGNER = '5926dcb22898c6d347e14c6912ce9d51e275c7bf3eea6099bebe3e70b9c52502'
EDITIONS = {'full': ('com.chuckiehelper.mobile', 'remotetool-update.json'),
            'phone': ('com.chuckiehelper.phonecontrol', 'phonecontrol-update.json')}


def run(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True, encoding='utf-8').strip()


def plan(channel):
    commit = run('git', 'rev-parse', 'HEAD')
    count = int(run('git', 'rev-list', '--first-parent', '--count', 'HEAD'))
    code = 1000 + count * 2 + (channel == 'stable')
    assert 148 < code < 2_100_000_000
    return {'commit': commit, 'channel': channel, 'versionCode': code}


def package(out, channel):
    info = plan(channel)
    out.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    signers = sorted(sdk.glob('build-tools/*/apksigner' + ('.bat' if os.name == 'nt' else '')))
    assert signers, 'Android signing verifier missing'
    built = datetime.datetime.now(datetime.timezone.utc).isoformat()
    for flavor, (package_id, manifest_name) in EDITIONS.items():
        apkroot = ROOT / 'app/mobile/build/outputs/apk' / flavor / 'release'
        metadata = json.loads((apkroot / 'output-metadata.json').read_text())
        row, = metadata['elements']
        assert metadata['applicationId'] == package_id and row['versionCode'] == info['versionCode']
        if channel == 'stable':
            if flavor == 'full':
                assert os.environ.get('GITHUB_REF_NAME', '') in {f"android-v{row['versionName']}", f"android-v{row['versionName']}-build{info['versionCode']}"}, 'Tag must match the main App version'
        else: assert '-dev.' in row['versionName']
        apk = apkroot / row['outputFile']
        verified = run(str(signers[-1]), 'verify', '--print-certs', str(apk))
        assert re.search(r'certificate SHA-256 digest:\s*' + SIGNER, verified), 'Installed certificate must be preserved'
        shutil.copy2(apk, out / apk.name)
        notes = f"{'开发版自动构建' if channel == 'development' else '正式版'} · {row['versionName']}\n提交：{info['commit']}\n构建：{built}"
        manifest = {'schemaVersion': 1, 'packageId': package_id, 'versionCode': info['versionCode'],
                    'versionName': row['versionName'], 'apkAsset': apk.name, 'size': apk.stat().st_size,
                    'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'signerSha256': SIGNER,
                    'channel': channel, 'sourceRevision': info['commit'], 'builtAt': built, 'notes': notes}
        (out / manifest_name).write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (out / 'build.json').write_text(json.dumps(info), encoding='utf-8')


def release_info(tag):
    result = subprocess.run(['gh', 'release', 'view', tag, '--json', 'assets,isDraft,targetCommitish,tagName'], cwd=ROOT, capture_output=True, encoding='utf-8')
    if result.returncode == 0: return json.loads(result.stdout)
    if 'release not found' in result.stderr.lower(): return None
    raise RuntimeError('Cannot inspect existing release: ' + result.stderr)


def verify_assets(tag, files):
    assets = {a['name']: a for a in release_info(tag)['assets']}
    for path in files:
        asset = assets[path.name]
        assert asset['size'] == path.stat().st_size and asset['state'] == 'uploaded', path.name
        assert asset['digest'] == 'sha256:' + hashlib.sha256(path.read_bytes()).hexdigest(), path.name


def prune_development_apks(tag, apk_names):
    assert tag == 'android-development', 'Only the moving development release may be pruned'
    keep = set(apk_names)
    assert len(keep) == 2 and all(re.fullmatch(r'RemoteTool-(?:PhoneControl-)?\d[^/\\]*\.apk', name) for name in keep)
    assert sum(name.startswith('RemoteTool-PhoneControl-') for name in keep) == 1
    assets = release_info(tag)['assets']
    current = {a['name']: a for a in assets if a['name'] in keep}
    assert set(current) == keep and all(a['state'] == 'uploaded' for a in current.values()), 'Both current APKs must be uploaded before cleanup'
    for asset in assets:
        name = asset['name']
        if name not in keep and re.fullmatch(r'RemoteTool-(?:PhoneControl-)?\d[^/\\]*\.apk', name):
            run('gh', 'release', 'delete-asset', tag, name, '--yes')


def publish(out, channel):
    info = json.loads((out / 'build.json').read_text())
    assert info == plan(channel), 'Release artifacts must match this exact checkout'
    manifests = [out / name for _, name in EDITIONS.values()]
    apks = [out / json.loads(m.read_text())['apkAsset'] for m in manifests]
    assert all(p.is_file() for p in manifests + apks)
    notes = f"{'主线自动构建，仅供开发渠道使用。' if channel == 'development' else '正式发布，包含主 App 与手机操控 App。'}\n\n提交：{info['commit']}\n构建号：{info['versionCode']}\n\n" + run('git', 'log', '-1', '--format=%s')
    with tempfile.TemporaryDirectory() as temp:
        notes_file = Path(temp) / 'notes.txt'; notes_file.write_text(notes, encoding='utf-8')
        tag = 'android-development' if channel == 'development' else os.environ['GITHUB_REF_NAME']
        previous = release_info(tag)
        if channel == 'development':
            if previous:
                old_manifest = next((a for a in previous['assets'] if a['name'] == 'remotetool-update.json'), None)
                if old_manifest:
                    run('gh', 'release', 'download', tag, '--pattern', 'remotetool-update.json', '--dir', temp)
                    old = json.loads((Path(temp) / 'remotetool-update.json').read_text())
                    if old['versionCode'] > info['versionCode']:
                        print('Newer development build already published; stale build skipped'); return
                assert not previous['isDraft'], 'Unexpected draft development release'
                # Only this dedicated moving development tag is ever force-updated.
                run('gh', 'api', '--method', 'PATCH', f"repos/{os.environ['GITHUB_REPOSITORY']}/git/refs/tags/android-development",
                    '-f', f"sha={info['commit']}", '-F', 'force=true')
                run('gh', 'release', 'edit', tag, '--prerelease', '--latest=false', '--notes-file', str(notes_file))
            else:
                run('gh', 'release', 'create', tag, '--target', info['commit'], '--prerelease', '--latest=false', '--title', 'Android 开发版', '--notes-file', str(notes_file))
            # Upload immutable named APKs before switching either channel manifest.
            run('gh', 'release', 'upload', tag, *map(str, apks), '--clobber')
            run('gh', 'release', 'upload', tag, *map(str, manifests), '--clobber')
            verify_assets(tag, manifests + apks)
            # Manifests and APKs are verified first; retain exactly the two APKs
            # referenced by this successful build, never guess by upload time.
            prune_development_apks(tag, [p.name for p in apks])
        else:
            assert run('git', 'rev-list', '-n', '1', tag) == info['commit']
            files = manifests + apks + [out / 'RemoteTool.zip']
            assert all(p.is_file() for p in files)
            if previous and not previous['isDraft']:
                verify_assets(tag, files); print('Existing published release verified'); return
            if not previous:
                run('gh', 'release', 'create', tag, '--verify-tag', '--draft', '--title', tag.removeprefix('android-v'), '--notes-file', str(notes_file))
            run('gh', 'release', 'upload', tag, *map(str, files), '--clobber')
            verify_assets(tag, files)
            run('gh', 'release', 'edit', tag, '--draft=false', '--latest')
        print('Published and verified:', tag, info['commit'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['plan', 'package', 'publish'])
    parser.add_argument('--channel', choices=['stable', 'development'], required=True)
    parser.add_argument('--out', type=Path)
    args = parser.parse_args()
    if args.action == 'plan': print(json.dumps(plan(args.channel)))
    elif args.action == 'package': package(args.out.resolve(), args.channel)
    else: publish(args.out.resolve(), args.channel)
