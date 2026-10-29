#!/usr/bin/env python3
"""Run the release gate without rewriting committed runtime assets."""

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile


def asset_manifest(root):
    if root.is_symlink() or not root.is_dir():
        raise ValueError('Expected a regular asset directory: ' + str(root))
    result = {}
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise ValueError('Asset symlink: ' + str(path))
        if path.is_file():
            result[path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    if 'index.html' not in result:
        raise ValueError('Missing built index.html: ' + str(root))
    return result


def assert_static_matches(frontend, backend):
    built = asset_manifest(frontend / 'dist')
    bundled = asset_manifest(backend / 'web/src/main/resources/static')
    mismatches = [name for name in sorted(set(built) | set(bundled)) if built.get(name) != bundled.get(name)]
    if mismatches:
        raise ValueError('Frontend dist differs from bundled static assets; synchronize and review the changes first: '
                         + ', '.join(mismatches[:20]))


def assert_packaged_static_matches(frontend, backend):
    jars = sorted((backend / 'web/target').glob('*.jar'))
    if len(jars) != 1:
        raise ValueError('Expected exactly one packaged application JAR')
    expected = asset_manifest(frontend / 'dist')
    packaged = {}
    prefix = 'BOOT-INF/classes/static/'
    with zipfile.ZipFile(jars[0]) as jar:
        for entry in jar.infolist():
            if entry.filename.startswith(prefix) and not entry.is_dir():
                name = entry.filename[len(prefix):]
                if name in packaged:
                    raise ValueError('Duplicate static JAR entry: ' + name)
                packaged[name] = hashlib.sha256(jar.read(entry)).hexdigest()
    if expected != packaged:
        raise ValueError('Packaged JAR static assets differ from the verified frontend build')


def run_command(label, command, cwd):
    print('\n=== ' + label + ' ===', flush=True)
    subprocess.run([str(value) for value in command], cwd=str(cwd), check=True)


def verify_release(backend, frontend, install=True):
    for required in [backend / 'pom.xml', frontend / 'package.json', frontend / 'package-lock.json']:
        if not required.is_file():
            raise ValueError('Missing release input: ' + str(required))
    npm = shutil.which('npm.cmd' if os.name == 'nt' else 'npm')
    if not npm:
        raise ValueError('npm is required for release verification')
    if install:
        run_command('Install locked frontend dependencies', [npm, 'ci'], frontend)
    run_command('Frontend lint', [npm, 'run', 'lint', '--', '--max-warnings=0'], frontend)
    run_command('Frontend tests', [npm, 'test'], frontend)
    run_command('Frontend production build', [npm, 'run', 'build'], frontend)
    assert_static_matches(frontend, backend)
    run_command('Java component API and runtime bytecode check',
                ['bash', backend / 'javacore/compile-components.sh', '--check'], backend)
    run_command('Semantic names',
                [sys.executable, backend / 'tools/check_semantic_names.py', '--frontend', frontend], backend)
    run_command('Build-tool regression tests',
                [sys.executable, '-m', 'unittest', 'discover', '-s', 'tools', '-p', 'test_*.py'], backend)
    maven = [backend / 'mvnw', '--batch-mode', 'clean', 'verify']
    compatibility_java = Path(os.environ.get('JAVA8_HOME', '')) / 'bin/java'
    if os.environ.get('JAVA8_HOME') and compatibility_java.is_file():
        maven.append('-Djmg.compat.java=' + str(compatibility_java))
    run_command('Backend clean verification and packaging', maven, backend)
    # Recheck after all subprocesses so an intervening asset change fails the gate too.
    assert_static_matches(frontend, backend)
    assert_packaged_static_matches(frontend, backend)
    print('\nRelease checks passed.', flush=True)


def main(argv=None):
    backend = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--frontend', type=Path, default=backend.parent / 'LeoVueAi')
    parser.add_argument('--skip-install', action='store_true',
                        help='Use the existing node_modules for local checks; CI installs from the lockfile')
    args = parser.parse_args(argv)
    try:
        verify_release(backend, args.frontend.resolve(), install=not args.skip_install)
    except subprocess.CalledProcessError as error:
        return error.returncode
    except (OSError, ValueError) as error:
        print('Release check failed: ' + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
