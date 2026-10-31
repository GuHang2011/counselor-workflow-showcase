"""Chat acceptance using the isolated accounts created by erp_acceptance.py."""
import json
import os
from pathlib import Path
import secrets
import struct
import time
import zlib

from erp_smoke import Api, login, multipart_file, require


def main():
    base = os.environ['ERP_BASE_URL']
    fixture_path = Path(os.environ['ERP_UI_FIXTURES'])
    fixture = json.loads(fixture_path.read_text(encoding='utf-8'))
    student, student_user = login(base, **fixture['student'])
    teacher, teacher_user = login(base, **fixture['counselor'])
    admin, _ = login(base, **fixture['admin'])
    public = Api(base)
    public.reject('GET', '/chat/conversations', expected=(401,))
    contacts = student.data('GET', '/chat/contacts')
    require(any(c['id'] == teacher_user['id'] for c in contacts), 'Assigned counselor missing from contacts')
    require(all('passwordHash' not in c and 'phone' not in c for c in contacts), 'Contacts expose private fields')
    conversation = student.data('POST', '/chat/conversations', {'recipientId': teacher_user['id']})
    repeated = teacher.data('POST', '/chat/conversations', {'recipientId': student_user['id']})
    require(repeated['id'] == conversation['id'], 'Duplicate conversation created')
    route = '/chat/conversations/' + conversation['id']
    admin.reject('GET', route + '/messages', expected=(403,))
    print('PASS chat authentication, scoped contacts and conversation membership', flush=True)

    payload = {'clientMessageId': secrets.token_hex(16), 'content': '隔离验收：老师您好，请核对材料。', 'attachmentIds': []}
    sent = student.data('POST', route + '/messages', payload)
    replay = student.data('POST', route + '/messages', payload)
    require(sent['id'] == replay['id'], 'Retry created a duplicate message')
    student.reject('POST', route + '/messages', dict(payload, content='Changed content'), expected=(409,))
    student.reject('POST', route + '/messages', {'clientMessageId': secrets.token_hex(16), 'content': ' ', 'attachmentIds': []}, expected=(400,))
    require(teacher.data('GET', '/chat/unread')['unreadCount'] == 1, 'Incoming message not counted')
    print('PASS chat text delivery, idempotent retry, empty input and unread count', flush=True)

    later = student.data('POST', route + '/messages', {'clientMessageId': secrets.token_hex(16), 'content': '隔离验收：第二条消息', 'attachmentIds': []})
    teacher.data('POST', route + '/read', {'throughSequence': sent['sequence']})
    require(teacher.data('GET', '/chat/unread')['unreadCount'] == 1, 'Read receipt accidentally marked later message')
    teacher.data('POST', route + '/read', {'throughSequence': later['sequence']})
    require(teacher.data('GET', '/chat/unread')['unreadCount'] == 0, 'Unread count did not clear')
    latest = student.data('GET', route + '/messages?limit=1')
    older = student.data('GET', route + '/messages?limit=1&beforeSequence=' + str(latest['nextBefore']))
    require(latest['items'][0]['id'] == later['id'] and older['items'][0]['id'] == sent['id'], 'History pagination is not stable')
    print('PASS chat read cursor and chronological pagination', flush=True)

    def chunk(kind, data):
        return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data) & 0xffffffff)

    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', 2, 2, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress((b'\x00' + b'\x44\x88\xcc' * 2) * 2)) + chunk(b'IEND', b'')
    upload, content_type = multipart_file(png)
    attachment = student.request('POST', '/files', raw=upload, content_type=content_type).json()['data']
    for _ in range(60):
        attachment = student.data('GET', '/files/' + attachment['id'])
        if attachment.get('processingStatus') == 'ready':
            break
        require(attachment.get('processingStatus') in ('queued', 'processing'), 'File processing failed')
        time.sleep(.5)
    require(attachment.get('processingStatus') == 'ready', 'File processing timeout')
    teacher.reject('GET', '/files/' + attachment['id'] + '/download', expected=(403,))
    file_message = student.data('POST', route + '/messages', {'clientMessageId': secrets.token_hex(16), 'content': '', 'attachmentIds': [attachment['id']]})
    received = teacher.request('GET', '/files/' + attachment['id'] + '/download')
    require(received.body.startswith(b'\x89PNG'), 'Authorized file download did not return the image')
    admin.reject('GET', '/files/' + attachment['id'] + '/download', expected=(403,))
    teacher.reject('POST', route + '/messages', {'clientMessageId': secrets.token_hex(16), 'content': '', 'attachmentIds': [attachment['id']]}, expected=(403,))
    print('PASS chat file-only delivery, recipient download and third-party denial', flush=True)
    sticker_payload = {'clientMessageId': secrets.token_hex(16), 'stickerId': 'received'}
    sticker = student.data('POST', route + '/messages', sticker_payload)
    require(sticker['content'] == '[表情包：收到]' and sticker['stickerId'] == 'received', 'Sticker lacks readable fallback')
    require(student.data('POST', route + '/messages', sticker_payload)['id'] == sticker['id'], 'Sticker retry duplicated delivery')
    student.reject('POST', route + '/messages', dict(sticker_payload, stickerId='thanks'), expected=(409,))
    student.reject('POST', route + '/messages', {'clientMessageId': secrets.token_hex(16), 'stickerId': 'https://example.org/image.png'}, expected=(400,))
    print('PASS bundled sticker delivery, readable fallback, safe identifiers and retry', flush=True)
    fixture.update(chatConversationId=conversation['id'], chatMessageId=file_message['id'], chatFileId=attachment['id'], chatStickerMessageId=sticker['id'])
    fixture_path.write_text(json.dumps(fixture), encoding='utf-8')


if __name__ == '__main__':
    main()
