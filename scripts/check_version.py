"""Validate product release metadata, optionally against a prior Git revision."""
import argparse
from pathlib import Path
import re
import subprocess

VERSION_PATTERN = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)")


def version_tuple(value):
    if not VERSION_PATTERN.fullmatch(value):
        raise ValueError("VERSION must be a stable semantic version, for example 1.1.0")
    return tuple(map(int, value.split('.')))


def validate(root, previous=None, tag=None):
    value = (root / 'VERSION').read_text('utf-8-sig').strip()
    current = version_tuple(value)
    if previous is not None and current <= version_tuple(previous.strip()):
        raise ValueError('VERSION must increase compared with the previous release')
    if tag is not None and tag != 'v' + value:
        raise ValueError('Git tag must match VERSION')
    changelog = (root / 'CHANGELOG.md').read_text('utf-8-sig')
    headings = re.findall(r'^## \[([^\]]+)\] - \d{4}-\d{2}-\d{2}\s*$', changelog, re.M)
    if not headings or headings[0] != value:
        raise ValueError('The first versioned CHANGELOG entry must match VERSION')
    if headings.count(value) != 1:
        raise ValueError('CHANGELOG must contain exactly one entry for VERSION')
    notes = root / 'docs' / 'releases' / ('v' + value + '.md')
    title = notes.read_text('utf-8-sig').splitlines()[0]
    if not re.match(r'^# v' + re.escape(value) + r'(?:\s|$)', title):
        raise ValueError('Release note title must match VERSION')
    return value


def previous_version(root, revision):
    if not re.fullmatch(r'[0-9a-fA-F]{40,64}', revision):
        raise ValueError('Base revision must be a full Git commit hash')
    if set(revision) == {'0'}:
        return None
    result = subprocess.run(['git', 'ls-tree', '--name-only', revision, '--', 'VERSION'],
                            cwd=root, capture_output=True, text=True, check=True)
    if not result.stdout.strip():
        return None  # First versioned commit; do not invent historical releases.
    return subprocess.check_output(['git', 'show', revision + ':VERSION'], cwd=root, text=True).strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-ref')
    parser.add_argument('--tag')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    previous = previous_version(root, args.base_ref) if args.base_ref else None
    print('Release metadata verified: v' + validate(root, previous, args.tag))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, IndexError, subprocess.CalledProcessError) as error:
        raise SystemExit('Version check failed: ' + str(error)) from None
