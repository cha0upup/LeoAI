import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class ComponentClassComparatorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='leo-component-test-')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        home = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else None
        cls.javac = str(home / 'javac') if home else shutil.which('javac')
        cls.java = str(home / 'java') if home else shutil.which('java')
        cls.asm = Path.home() / '.m2/repository/org/ow2/asm/asm/9.5/asm-9.5.jar'
        if not cls.javac or not cls.java or not cls.asm.is_file():
            raise unittest.SkipTest('Run the component check first to prepare the JDK and ASM dependency')
        subprocess.run([cls.javac, '-cp', str(cls.asm), '-d', str(cls.root),
                        str(Path(__file__).with_name('ComponentClassComparator.java'))], check=True,
                       capture_output=True)

    def compile(self, folder, body, parameters=False, debug=True):
        folder.mkdir(parents=True)
        source = folder / 'Fixture.java'
        source.write_text(body)
        command = [self.javac, '-g' if debug else '-g:none']
        if parameters:
            command.append('-parameters')
        subprocess.run(command + ['-d', str(folder), str(source)], check=True, capture_output=True)
        return folder / 'Fixture.class'

    def compare(self, left, right):
        with tempfile.TemporaryDirectory(dir=self.root) as temporary:
            root = Path(temporary)
            generated, payloads = root / 'generated', root / 'payloads'
            a = self.compile(generated, **left)
            b = self.compile(payloads, **right)
            b.rename(payloads / 'Fixture.payload')
            return subprocess.run([self.java, '-cp', str(self.root) + os.pathsep + str(self.asm),
                                   'ComponentClassComparator', str(a.parent), str(payloads)],
                                  capture_output=True).returncode

    def test_accepts_line_and_local_variable_debug_differences(self):
        source = 'public class Fixture { public static int value() { int x = 7; return x; } }'
        self.assertEqual(0, self.compare({'body': source}, {'body': '\n\n' + source, 'debug': False}))

    def test_rejects_changed_instructions(self):
        source = 'public class Fixture { public static int value() { return %s; } }'
        self.assertNotEqual(0, self.compare({'body': source % '7'}, {'body': source % '8'}))

    def test_rejects_changed_runtime_annotations(self):
        source = 'public class Fixture { public static int value() { return 7; } }'
        self.assertNotEqual(0, self.compare({'body': source}, {'body': '@Deprecated ' + source}))

    def test_keeps_reflective_method_parameter_names(self):
        source = 'public class Fixture { public static int value(int %s) { return %s; } }'
        self.assertNotEqual(0, self.compare({'body': source % ('first', 'first'), 'parameters': True},
                                           {'body': source % ('second', 'second'), 'parameters': True}))


if __name__ == '__main__':
    unittest.main()
