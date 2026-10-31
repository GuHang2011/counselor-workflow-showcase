"""Run real HTTP/browser acceptance on isolated data; never use the office database."""
from __future__ import annotations
import json
import hashlib
import argparse
from datetime import datetime, timezone
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
from erp_smoke import login, require

ROOT = Path(__file__).resolve().parents[1]
FRONT = ROOT / 'CropDiseasesDetection_vue'
CACHE = ROOT / '.cache'


def tree_sha256(directory):
    if not (directory / 'index.html').is_file():
        raise RuntimeError('Build the production frontend before acceptance.')
    lines = []
    for file in sorted(directory.rglob('*'), key=lambda p: p.relative_to(directory).as_posix()):
        if file.is_symlink():
            raise RuntimeError('Frontend build must not contain symbolic links.')
        if file.is_file():
            lines.append(hashlib.sha256(file.read_bytes()).hexdigest() + '  '
                         + file.relative_to(directory).as_posix() + '\n')
    return hashlib.sha256(''.join(lines).encode('utf-8')).hexdigest()


def wait_http(url, process):
    for _ in range(120):
        if process.poll() is not None:
            raise RuntimeError('Acceptance server exited; inspect .cache/acceptance-* logs')
        try:
            request = urllib.request.Request(url, headers={'Accept': 'text/html,application/json'})
            with urllib.request.urlopen(request, timeout=2) as response:
                if response.status == 200:
                    return
        except Exception:
            time.sleep(.5)
    raise RuntimeError('Acceptance server startup timed out')


def stop(process):
    if process and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--web-dir', type=Path, default=FRONT / 'dist', help='Production frontend directory to verify and serve')
    parser.add_argument('--backend-port', type=int, default=9999, help='Isolated backend port')
    parser.add_argument('--web-port', type=int, default=8888, help='Isolated desktop preview port')
    parser.add_argument('--mobile-port', type=int, default=8890, help='Isolated mobile preview port')
    args = parser.parse_args()
    web_directory = args.web_dir.resolve()
    backend_port, web_port, mobile_port = args.backend_port, args.web_port, args.mobile_port
    backend_url = f'http://127.0.0.1:{backend_port}'
    web_url = f'http://127.0.0.1:{web_port}'
    mobile_url = f'http://127.0.0.1:{mobile_port}'
    frontend_sha = tree_sha256(web_directory)
    for port in (backend_port, web_port, mobile_port):
        with socket.socket() as check:
            try:
                check.bind(('127.0.0.1', port))
            except OSError:
                raise RuntimeError(f'Port {port} is occupied. Existing services will not be stopped.')
    run = CACHE / ('acceptance-' + secrets.token_hex(5))
    run.mkdir(parents=True)
    fixtures = CACHE / 'ui-fixtures.json'
    env = {k: v for k, v in os.environ.items() if not k.startswith(('ERP_', 'SPRING_'))}
    initial = 'Init9!' + secrets.token_urlsafe(24)
    password = 'Fresh9!' + secrets.token_urlsafe(24)
    env.update(ERP_PORT=str(backend_port), SERVER_ADDRESS='127.0.0.1', ERP_AI_ENABLED='false', ERP_ADMIN_PASSWORD=initial,
        ERP_DB_URL='jdbc:h2:file:' + (run/'database').as_posix() + ';DB_CLOSE_ON_EXIT=FALSE',
        ERP_UPLOAD_DIR=str(run/'uploads'), ERP_BACKUP_DIR=str(run/'backups'),
        ERP_MEDIA_REQUIRE_SCAN='false', ERP_ALLOWED_ORIGINS=f'{web_url},{mobile_url}',
        ERP_DATA_KEY=__import__('base64').b64encode(secrets.token_bytes(16)).decode(),
        ERP_BACKUP_KEY=__import__('base64').b64encode(secrets.token_bytes(16)).decode(),
        ERP_BASE_URL=backend_url, ERP_TEST_ADMIN_PASSWORD=initial,
        ERP_TEST_ADMIN_NEW_PASSWORD=password, ERP_UI_FIXTURES=str(fixtures),
        VITE_API_PROXY_TARGET=backend_url, ERP_UI_URL=web_url, ERP_MOBILE_URL=mobile_url)
    jar = ROOT/'CropDiseasesDetection_springboot/target/counselor-erp-1.0.0.jar'
    report = {'status':'running','startedAt':datetime.now(timezone.utc).isoformat(),
        'runDirectory':str(run),'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),
        'frontendSha256':frontend_sha, 'frontendMode':'production-preview',
        'httpSmoke':'not-run','chatSmoke':'not-run','browser':'not-run','restart':'not-run'}
    report_path = CACHE/'acceptance-result.json'
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    java_home = os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if java_home else shutil.which('java')
    node = shutil.which('node')
    if not java or not node:
        raise RuntimeError('Java and Node.js are required for acceptance.')
    backend = frontend = mobile = None
    with (run/'backend.log').open('wb') as backend_log, (run/'frontend.log').open('wb') as frontend_log, (run/'mobile.log').open('wb') as mobile_log, (run/'checks.log').open('w', encoding='utf-8') as checks:
        try:
            backend = subprocess.Popen([str(java), '-jar', str(jar)], cwd=run, env=env, stdout=backend_log, stderr=subprocess.STDOUT)
            wait_http(backend_url + '/health', backend)
            frontend = subprocess.Popen([node, str(FRONT/'node_modules/vite/bin/vite.js'), 'preview', '--outDir', str(web_directory), '--host', '127.0.0.1', '--port', str(web_port), '--strictPort'], cwd=FRONT, env=env, stdout=frontend_log, stderr=subprocess.STDOUT)
            wait_http(web_url, frontend)
            mobile = subprocess.Popen([node, str(FRONT/'mobile/node_modules/@dcloudio/vite-plugin-uni/bin/uni.js'), '-p', 'h5', '--host', '127.0.0.1', '--port', str(mobile_port), '--strictPort'], cwd=FRONT/'mobile', env=env, stdout=mobile_log, stderr=subprocess.STDOUT)
            wait_http(mobile_url, mobile)
            print('Isolated services ready; running real HTTP workflows.', flush=True)
            result = subprocess.run([sys.executable, str(ROOT/'tests/erp_smoke.py')], env=env, stdout=checks, stderr=subprocess.STDOUT)
            checks.flush()
            if result.returncode:
                report['httpSmoke'] = 'failed'
                print((run/'checks.log').read_text(encoding='utf-8'))
                return result.returncode
            report['httpSmoke'] = 'passed'
            print((run/'checks.log').read_text(encoding='utf-8'), flush=True)
            result = subprocess.run([sys.executable, str(ROOT/'tests/erp_chat_smoke.py')], env=env, stdout=checks, stderr=subprocess.STDOUT)
            checks.flush()
            if result.returncode:
                report['chatSmoke'] = 'failed'
                print((run/'checks.log').read_text(encoding='utf-8'))
                return result.returncode
            report['chatSmoke'] = 'passed'
            print('PASS isolated chat HTTP acceptance.', flush=True)
            print('Running browser workflows and responsive layouts.', flush=True)
            result = subprocess.run([node, str(FRONT/'node_modules/@playwright/test/cli.js'), 'test'], cwd=FRONT, env=env)
            if result.returncode:
                report['browser'] = 'failed'
                return result.returncode
            report['browser'] = 'passed'
            report['browserReportSha256'] = hashlib.sha256((CACHE/'browser-results.json').read_bytes()).hexdigest()
            data = json.loads(fixtures.read_text(encoding='utf-8'))
            student, _ = login(env['ERP_BASE_URL'], **data['student'])
            previous = student.data('GET', '/notices')
            stop(backend)
            backend = subprocess.Popen([str(java), '-jar', str(jar)], cwd=run, env=env, stdout=backend_log, stderr=subprocess.STDOUT)
            wait_http(backend_url + '/health', backend)
            student, _ = login(env['ERP_BASE_URL'], **data['student'])
            current = student.data('GET', '/notices')
            require({n['id'] for n in previous} == {n['id'] for n in current}, 'Restart lost notices')
            require(student.data('GET', '/auth/me').get('theme') == 'pink', 'Restart lost user theme')
            chat = student.data('GET', '/chat/conversations/' + data['chatConversationId'] + '/messages?limit=100')
            require(any(m['id'] == data['chatMessageId'] for m in chat['items']), 'Restart lost chat messages')
            require(any(m['id'] == data['chatStickerMessageId'] and m.get('stickerId') == 'received' for m in chat['items']), 'Restart lost sticker messages')
            teacher, _ = login(env['ERP_BASE_URL'], **data['counselor'])
            require(teacher.request('GET', '/files/' + data['chatFileId'] + '/download').status == 200, 'Restart lost chat file access')
            require(tree_sha256(web_directory) == frontend_sha, 'Frontend changed during acceptance')
            require(hashlib.sha256(jar.read_bytes()).hexdigest() == report['jarSha256'], 'Backend changed during acceptance')
            print('PASS persistent encrypted data and preferences survive server restart.', flush=True)
            report.update(status='passed',restart='passed')
            return 0
        finally:
            stop(mobile)
            stop(frontend)
            stop(backend)
            fixtures.unlink(missing_ok=True)
            if report['status'] == 'running':
                report['status'] = 'failed'
            report['finishedAt'] = datetime.now(timezone.utc).isoformat()
            report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    raise SystemExit(main())
