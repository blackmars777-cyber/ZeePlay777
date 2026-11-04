"""Exercise publication checks against isolated repositories, including not-yet-staged files."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

CHECKER = Path(__file__).resolve().parents[1] / 'check_public_tree.py'


class PublicTreeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        (self.root / 'scripts').mkdir()
        shutil.copyfile(CHECKER, self.root / 'scripts/check_public_tree.py')
        (self.root / '.gitignore').write_text('.private/\n*.apk\n')

    def check_tree(self):
        return subprocess.run([sys.executable, str(self.root / 'scripts/check_public_tree.py')],
                              cwd=self.root, capture_output=True, text=True)

    def test_new_unstaged_document_cannot_expose_a_local_reference(self):
        (self.root / 'notes.md').write_text('Analysis: /Users/example/Downloads/input.apk\n')
        result = self.check_tree()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('notes.md', result.stderr)
        self.assertNotIn('/Users/example', result.stderr)

    def test_private_archive_is_ignored_but_cannot_be_force_added(self):
        folder = self.root / '.private'
        folder.mkdir()
        (folder / 'notes.md').write_text('local research\n')
        self.assertEqual(0, self.check_tree().returncode)
        subprocess.run(['git', 'add', '-f', '.private/notes.md'], cwd=self.root, check=True)
        self.assertNotEqual(0, self.check_tree().returncode)

    def test_binary_artifacts_are_rejected_even_when_force_added(self):
        (self.root / 'vendor.apk').write_bytes(b'test fixture')
        subprocess.run(['git', 'add', '-f', 'vendor.apk'], cwd=self.root, check=True)
        self.assertNotEqual(0, self.check_tree().returncode)

    def test_document_backup_identifiers_and_old_labels_are_rejected(self):
        for text in ['zeekrbackup-000000000', 'Kr' + 'Play']:
            with self.subTest(text=text):
                (self.root / 'notes.md').write_text(text)
                self.assertNotEqual(0, self.check_tree().returncode)

    def test_public_docs_and_wrapper_are_allowed(self):
        (self.root / 'README.md').write_text('Build with C:/Users/YOUR_NAME/Android/Sdk.\n')
        (self.root / 'gradle-wrapper.jar').write_bytes(b'test fixture')
        self.assertEqual(0, self.check_tree().returncode)

    def test_link_to_external_file_is_rejected(self):
        with tempfile.TemporaryDirectory() as outside:
            target = Path(outside) / 'input.txt'
            target.write_text('local input')
            (self.root / 'input.txt').symlink_to(target)
            self.assertNotEqual(0, self.check_tree().returncode)


if __name__ == '__main__':
    unittest.main()
