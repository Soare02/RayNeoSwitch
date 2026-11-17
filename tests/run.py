"""Build and run provider regressions on an authorized Android device."""

import argparse
from datetime import datetime
import json
from pathlib import Path
import subprocess
import zipfile


def run(arguments, capture=False):
    result = subprocess.run([str(value) for value in arguments], check=True,
                            capture_output=capture, text=True, encoding='utf-8')
    return result.stdout if capture else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', type=Path, required=True)
    parser.add_argument('--device', required=True)
    parser.add_argument('--ui', action='store_true', help='Exercise the UI with a temporary copy of the active provider')
    parser.add_argument('--diagnose-output', action='store_true',
                        help='Compare two live model requests with a synthetic long prompt using the saved key')
    parser.add_argument('--diagnose-models', action='store_true', help='Read the live model catalog using the saved key')
    arguments = parser.parse_args()
    project = Path(__file__).resolve().parent.parent
    paths = json.loads((project / '.local/android_build_paths.json').read_text(encoding='utf-8'))
    latest = json.loads((project / 'latest_build.json').read_text(encoding='utf-8'))
    target_build = Path(latest['apk']).parent
    android_jar = Path(paths['android.jar'])
    build_tools = Path(paths['aapt2.exe']).parent
    java = Path(r'C:\Program Files\Java\jdk-17\bin')
    output = project / 'build' / ('provider_tests_' + datetime.now().strftime('%Y%m%d_%H%M%S_%f'))
    classes = output / 'classes'
    dex = output / 'dex'
    classes.mkdir(parents=True)
    dex.mkdir()
    run([java / 'javac.exe', '--release', '8', '-encoding', 'UTF-8', '-cp',
         str(android_jar) + ';' + str(target_build / 'classes.jar'), '-d', classes,
         *sorted((project / 'tests').glob('*.java'))])
    jar = output / 'tests.jar'
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive:
        for file in classes.rglob('*.class'):
            archive.write(file, file.relative_to(classes).as_posix())
    run([java / 'java.exe', '-cp', build_tools / 'lib/d8.jar', 'com.android.tools.r8.D8',
         '--lib', android_jar, '--classpath', target_build / 'classes.jar',
         '--min-api', '29', '--output', dex, jar])
    unsigned = output / 'unsigned.apk'
    run([build_tools / 'aapt2.exe', 'link', '-o', unsigned, '-I', android_jar,
         '--manifest', project / 'tests/AndroidManifest.xml'])
    with zipfile.ZipFile(unsigned, 'a') as archive:
        archive.write(dex / 'classes.dex', 'classes.dex', compress_type=zipfile.ZIP_STORED)
    aligned = output / 'aligned.apk'
    run([build_tools / 'zipalign.exe', '4', unsigned, aligned])
    apk = output / 'provider-tests.apk'
    run([java / 'java.exe', '-jar', build_tools / 'lib/apksigner.jar', 'sign',
         '--ks', project / '.local/rayneo-switch-development.jks',
         '--ks-key-alias', 'rayneo-switch', '--ks-pass', 'pass:android',
         '--key-pass', 'pass:android', '--out', apk, aligned])
    adb = [arguments.adb, '-s', arguments.device]
    remote_apk = '/data/local/tmp/codex-rayneo/provider-tests.apk'
    run([*adb, 'push', apk, remote_apk])
    run([*adb, 'shell', 'su', '-c', 'pm install -r -t --user 0 ' + remote_apk])
    options = ['-e', 'ui', 'true'] if arguments.ui else []
    if arguments.diagnose_output:
        options = ['-e', 'diagnose_output', 'true']
    if arguments.diagnose_models:
        options = ['-e', 'diagnose_models', 'true']
    result = run([*adb, 'shell', 'am', 'instrument', '-w', '-r', *options,
                  'dev.rayneo.switcher.tests/dev.rayneo.switcher.ProviderStoreInstrumentation'], capture=True)
    (output / 'result.txt').write_text(result, encoding='utf-8')
    print(result)
    if 'INSTRUMENTATION_CODE: -1' not in result:
        raise RuntimeError('Provider regression tests failed')


if __name__ == '__main__':
    main()
