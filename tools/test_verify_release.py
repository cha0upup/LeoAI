from pathlib import Path
import subprocess
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
        for path in [self.backend / 'pom.xml', self.frontend / 'package.json', self.frontend / 'package-lock.json']:
            path.write_text('{}')

    def test_matching_trees_pass_without_changing_any_file(self):
        before = verify_release.asset_manifest(self.bundled)
        verify_release.assert_static_matches(self.frontend, self.backend)
        self.assertEqual(before, verify_release.asset_manifest(self.bundled))

    def test_changed_missing_and_stale_files_fail(self):
        for folder, name in [(self.built, 'new.js'), (self.bundled, 'stale.js')]:
            with self.subTest(name=name):
                target = folder / name
                target.write_text('content')
                with self.assertRaises(ValueError):
                    verify_release.assert_static_matches(self.frontend, self.backend)
                target.unlink()
        (self.built / 'index.html').write_text('changed')
        with self.assertRaises(ValueError):
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
        jar = output / 'fixture.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('BOOT-INF/classes/static/index.html', 'fixture')
        verify_release.assert_packaged_static_matches(self.frontend, self.backend)
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('BOOT-INF/classes/static/index.html', 'stale')
        with self.assertRaises(ValueError):
            verify_release.assert_packaged_static_matches(self.frontend, self.backend)

    def test_missing_packaged_application_is_a_failure(self):
        with self.assertRaises(ValueError):
            verify_release.assert_packaged_static_matches(self.frontend, self.backend)

    @patch('verify_release.shutil.which', return_value='/fixture/npm')
    def test_gate_connects_the_required_checks_before_packaging(self, _which):
        with patch('verify_release.run_command') as run, \
                patch('verify_release.assert_packaged_static_matches') as packaged, patch('builtins.print'):
            verify_release.verify_release(self.backend, self.frontend)
        packaged.assert_called_once_with(self.frontend, self.backend)
        labels = [call.args[0] for call in run.call_args_list]
        self.assertEqual(['Install locked frontend dependencies', 'Frontend lint', 'Frontend tests',
                          'Frontend production build', 'Java component API and runtime bytecode check',
                          'Semantic names', 'Build-tool regression tests',
                          'Backend clean verification and packaging'], labels)

    @patch('verify_release.shutil.which', return_value='/fixture/npm')
    def test_asset_mismatch_prevents_backend_packaging(self, _which):
        (self.bundled / 'stale.js').write_text('stale')
        with patch('verify_release.run_command') as run:
            with self.assertRaises(ValueError):
                verify_release.verify_release(self.backend, self.frontend)
        self.assertFalse(any('Backend' in call.args[0] for call in run.call_args_list))

    @patch('verify_release.shutil.which', return_value='/fixture/npm')
    def test_failed_frontend_tests_stop_the_gate(self, _which):
        def fail_tests(label, command, cwd):
            if label == 'Frontend tests':
                raise subprocess.CalledProcessError(1, command)
        with patch('verify_release.run_command', side_effect=fail_tests) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                verify_release.verify_release(self.backend, self.frontend, install=False)
        self.assertEqual(['Frontend lint', 'Frontend tests'], [call.args[0] for call in run.call_args_list])


if __name__ == '__main__':
    unittest.main()
