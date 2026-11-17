import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile


def run(arguments):
    subprocess.run([str(value) for value in arguments], check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--android-jar', type=Path)
    parser.add_argument('--build-tools', type=Path)
    parser.add_argument('--xposed-api', type=Path)
    arguments = parser.parse_args()
    project = Path(__file__).resolve().parent
    tools = Path(os.environ['LOCALAPPDATA']) / 'Temp/codex-rayneo-apk-tools'
    paths_file = project / '.local/android_build_paths.json'
    if not paths_file.exists():
        paths_file = tools / 'android_build_paths.json'
    cached = json.loads(paths_file.read_text(encoding='utf-8'))
    android_jar = arguments.android_jar or Path(cached['android.jar'])
    build_tools = arguments.build_tools or Path(cached['aapt2.exe']).parent
    xposed_api = arguments.xposed_api or Path(cached['xposed-api'])
    java_home = Path(r'C:\Program Files\Java\jdk-17')
    output = project / 'build' / datetime.now().strftime('%Y%m%d_%H%M%S_%f')
    output.mkdir(parents=True, exist_ok=False)
    generated = output / 'generated'
    classes = output / 'classes'
    dex = output / 'dex'
    for folder in (generated, classes, dex):
        folder.mkdir()
    aapt = build_tools / 'aapt2.exe'
    resources = output / 'resources.zip'
    unsigned = output / 'unsigned.apk'
    run([aapt, 'compile', '--dir', project / 'res', '-o', resources])
    run([aapt, 'link', '-o', unsigned, '-I', android_jar,
         '--manifest', project / 'AndroidManifest.xml', '--java', generated, resources])
    sources = list((project / 'src').rglob('*.java')) + list(generated.rglob('*.java'))
    run([java_home / 'bin/javac.exe', '--release', '8', '-encoding', 'UTF-8',
         '-cp', str(android_jar) + os.pathsep + str(xposed_api), '-d', classes, *sources])
    jar = output / 'classes.jar'
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive:
        for file in classes.rglob('*.class'):
            archive.write(file, file.relative_to(classes).as_posix())
    run([java_home / 'bin/java.exe', '-cp', build_tools / 'lib/d8.jar', 'com.android.tools.r8.D8',
         '--lib', android_jar, '--classpath', xposed_api, '--min-api', '29', '--output', dex, jar])
    with zipfile.ZipFile(unsigned, 'a') as archive:
        for file in dex.glob('*.dex'):
            archive.write(file, file.name, compress_type=zipfile.ZIP_STORED)
        archive.write(project / 'assets/xposed_init', 'assets/xposed_init')
        for name in ('LICENSE', 'THIRD_PARTY_NOTICES.md',
                     'LICENSES/Apache-2.0.txt', 'LICENSES/XposedBridge-NOTICE.txt'):
            archive.write(project / name, 'assets/legal/' + name, compress_type=zipfile.ZIP_DEFLATED)
    aligned = output / 'aligned.apk'
    run([build_tools / 'zipalign.exe', '4', unsigned, aligned])
    keystore = project / '.local/rayneo-switch-development.jks'
    keystore.parent.mkdir(parents=True, exist_ok=True)
    if not keystore.exists():
        run([java_home / 'bin/keytool.exe', '-genkeypair', '-noprompt', '-keystore', keystore,
             '-storepass', 'android', '-keypass', 'android', '-alias', 'rayneo-switch',
             '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650',
             '-dname', 'CN=RayNeo Switch Development'])
    manifest = ET.parse(project / 'AndroidManifest.xml').getroot()
    version = manifest.attrib['{http://schemas.android.com/apk/res/android}versionName']
    apk = output / ('RayNeoSwitch-' + version + '.apk')
    signer = [java_home / 'bin/java.exe', '-jar', build_tools / 'lib/apksigner.jar']
    run([*signer, 'sign', '--ks', keystore, '--ks-key-alias', 'rayneo-switch',
         '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', apk, aligned])
    run([*signer, 'verify', '--verbose', apk])
    summary = {'apk': str(apk), 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'bytes': apk.stat().st_size}
    (project / 'latest_build.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
