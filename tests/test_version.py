import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('version_check', Path(__file__).resolve().parents[1] / 'scripts/check_version.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class VersionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'docs/releases').mkdir(parents=True)
        (self.root / 'VERSION').write_text('1.1.0\n', encoding='utf-8')
        (self.root / 'CHANGELOG.md').write_text('# Changes\n\n## [1.1.0] - 2026-10-30\n', encoding='utf-8')
        (self.root / 'docs/releases/v1.1.0.md').write_text('# v1.1.0 Release\n', encoding='utf-8')

    def test_matching_release(self):
        self.assertEqual(module.validate(self.root, '1.0.9', 'v1.1.0'), '1.1.0')

    def test_initial_version_needs_no_invented_baseline(self):
        self.assertEqual(module.validate(self.root), '1.1.0')

    def test_reused_and_decreased_versions_fail(self):
        for old in ['1.1.0', '1.1.1', '2.0.0']:
            with self.assertRaisesRegex(ValueError, 'increase'):
                module.validate(self.root, old)

    def test_version_order_is_numeric(self):
        self.assertGreater(module.version_tuple('1.10.0'), module.version_tuple('1.9.99'))

    def test_invalid_versions_fail(self):
        for value in ['v1.1.0', '01.1.0', '1.1', '../1.1.0', '1.1.0-rc1', '1.1.0\ninvalid']:
            with self.assertRaises(ValueError):
                module.version_tuple(value)

    def test_tag_mismatch_fails(self):
        with self.assertRaisesRegex(ValueError, 'Git tag'):
            module.validate(self.root, tag='v1.1.1')

    def test_changelog_mismatch_or_duplicate_fails(self):
        for text in ['## [1.0.0] - 2026-10-30\n', '## [1.1.0] - 2026-10-30\n## [1.1.0] - 2026-10-29\n']:
            (self.root / 'CHANGELOG.md').write_text(text, encoding='utf-8')
            with self.assertRaises(ValueError):
                module.validate(self.root)

    def test_release_note_missing_or_wrong_title_fails(self):
        notes = self.root / 'docs/releases/v1.1.0.md'
        notes.write_text('# v1.1.01 Wrong release\n', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'title'):
            module.validate(self.root)
        notes.unlink()
        with self.assertRaises(FileNotFoundError):
            module.validate(self.root)


if __name__ == '__main__':
    unittest.main()
