from pathlib import Path
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

import verify_release


class ReleaseGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='leo-release-test-')
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.backend, self.frontend = root / 'backend', root / 'frontend'
        self.built = self.frontend / 'dist'
        self.bundled = self.backend / 'web/src/main/resources/static'
        for folder in [self.built, self.bundled]:
            folder.mkdir(parents=True)
            (folder / 'index.html').write_text('fixture')
        (self.backend / 'pom.xml').write_text('<project><version>3.0.1</version>'
                                            '<modules><module>core</module></modules></project>')
        (self.backend / 'core').mkdir()
        (self.backend / 'core/pom.xml').write_text('<project><parent><version>3.0.1</version></parent></project>')
        (self.frontend / 'package.json').write_text(json.dumps({'version': '3.0.1'}))
        (self.frontend / 'package-lock.json').write_text(json.dumps({
            'version': '3.0.1', 'packages': {'': {'version': '3.0.1'}}
        }))

    def test_version_drift_stops_the_gate_before_installing_or_building(self):
        for path in [self.backend / 'core/pom.xml', self.frontend / 'package.json',
                     self.frontend / 'package-lock.json']:
            original = path.read_text()
            with self.subTest(source=path.name), patch('verify_release.run_command') as run:
                path.write_text(original.replace('3.0.1', '3.0.0'))
                try:
                    with self.assertRaises(ValueError):
                        verify_release.verify_release(self.backend, self.frontend)
                    run.assert_not_called()
                finally:
                    path.write_text(original)

    def test_matching_trees_pass_without_changing_any_file(self):
        before = verify_release.asset_manifest(self.bundled)
        verify_release.assert_static_matches(self.frontend, self.backend)
        self.assertEqual(before, verify_release.asset_manifest(self.bundled))

    def test_bundled_assets_must_match_by_path_and_content(self):
        new_asset = self.built / 'new.js'
        new_asset.write_text('content')
        with self.assertRaisesRegex(ValueError, r'new\.js'):
            verify_release.assert_static_matches(self.frontend, self.backend)
        new_asset.unlink()

        (self.built / 'index.html').write_text('changed')
        with self.assertRaisesRegex(ValueError, r'index\.html'):
            verify_release.assert_static_matches(self.frontend, self.backend)

    def test_empty_output_is_not_a_successful_build(self):
        (self.built / 'index.html').unlink()
        with self.assertRaises(ValueError):
            verify_release.assert_static_matches(self.frontend, self.backend)

    def test_symlinks_are_not_packaged_as_assets(self):
        try:
            (self.built / 'link.js').symlink_to(self.built / 'index.html')
        except OSError:
            self.skipTest('Symlink creation is unavailable on this filesystem')
        with self.assertRaises(ValueError):
            verify_release.assert_static_matches(self.frontend, self.backend)

    def test_checks_actual_packaged_assets_instead_of_only_source_directories(self):
        output = self.backend / 'web/target'
        output.mkdir()
        with self.assertRaisesRegex(ValueError, 'Expected exactly one packaged application JAR'):
            verify_release.assert_packaged_static_matches(self.frontend, self.backend)

        jar = output / 'fixture.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('BOOT-INF/classes/static/index.html', 'fixture')
        verify_release.assert_packaged_static_matches(self.frontend, self.backend)
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('BOOT-INF/classes/static/index.html', 'stale')
        with self.assertRaises(ValueError):
            verify_release.assert_packaged_static_matches(self.frontend, self.backend)

    @patch('verify_release.shutil.which', return_value='/fixture/npm')
    def test_failed_architecture_check_stops_backend_packaging(self, _which):
        command = [sys.executable, self.backend / 'tools/check_architecture.py', '--backend', self.backend]

        def fail_check(label, actual, cwd):
            if actual == command:
                raise subprocess.CalledProcessError(1, actual)

        with patch('verify_release.run_command', side_effect=fail_check) as run, \
                patch('verify_release.assert_packaged_static_matches') as packaged:
            with self.assertRaises(subprocess.CalledProcessError) as error:
                verify_release.verify_release(self.backend, self.frontend, install=False)
        self.assertEqual(1, error.exception.returncode)
        self.assertEqual(command, run.call_args.args[1])
        packaged.assert_not_called()

    @patch('verify_release.shutil.which', return_value='/fixture/npm')
    def test_asset_mismatch_prevents_backend_packaging(self, _which):
        (self.bundled / 'stale.js').write_text('stale')
        with patch('verify_release.run_command') as run:
            with self.assertRaises(ValueError):
                verify_release.verify_release(self.backend, self.frontend)
        self.assertFalse(any(call.args[2] == self.backend for call in run.call_args_list))


if __name__ == '__main__':
    unittest.main()
