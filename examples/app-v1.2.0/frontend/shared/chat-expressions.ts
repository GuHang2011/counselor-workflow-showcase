import received from './chat-stickers/received.png';
import okay from './chat-stickers/okay.png';
import thanks from './chat-stickers/thanks.png';
import hardWork from './chat-stickers/hard-work.png';
import cheer from './chat-stickers/cheer.png';
import great from './chat-stickers/great.png';
import wait from './chat-stickers/wait.png';
import done from './chat-stickers/done.png';
import checkFiles from './chat-stickers/check-files.png';
import together from './chat-stickers/together.png';
import morning from './chat-stickers/morning.png';
import sorry from './chat-stickers/sorry.png';

export const CHAT_STICKERS = [
  { id: 'received', label: '收到', src: received }, { id: 'okay', label: '好的', src: okay },
  { id: 'thanks', label: '谢谢', src: thanks }, { id: 'hard-work', label: '辛苦了', src: hardWork },
  { id: 'cheer', label: '加油', src: cheer }, { id: 'great', label: '点赞', src: great },
  { id: 'wait', label: '稍等一下', src: wait }, { id: 'done', label: '已完成', src: done },
  { id: 'check-files', label: '请查收', src: checkFiles }, { id: 'together', label: '合作愉快', src: together },
  { id: 'morning', label: '早上好', src: morning }, { id: 'sorry', label: '抱歉', src: sorry },
] as const;

export const CHAT_EMOJI = [
  { symbol: '😊', label: '微笑' }, { symbol: '😄', label: '开心' },
  { symbol: '👍', label: '赞' }, { symbol: '👏', label: '鼓掌' },
  { symbol: '🤝', label: '握手' }, { symbol: '🙏', label: '感谢' },
  { symbol: '💪', label: '加油' }, { symbol: '🎉', label: '庆祝' },
  { symbol: '❤️', label: '爱心' }, { symbol: '🌹', label: '鲜花' },
  { symbol: '✅', label: '完成' }, { symbol: '👌', label: '好的' },
  { symbol: '🙌', label: '太棒了' }, { symbol: '🤗', label: '拥抱' },
  { symbol: '😅', label: '不好意思' }, { symbol: '🤔', label: '思考' },
  { symbol: '👀', label: '关注' }, { symbol: '📌', label: '记下了' },
  { symbol: '📚', label: '学习' }, { symbol: '☕', label: '休息一下' },
  { symbol: '☀️', label: '晴天' }, { symbol: '🌟', label: '星星' },
  { symbol: '🎂', label: '生日快乐' }, { symbol: '👋', label: '挥手' },
] as const;

export function chatSticker(id?: string) { return CHAT_STICKERS.find(item => item.id === id); }
export function chatMessageText(message: { content?: string; stickerId?: string }) {
  const sticker = chatSticker(message.stickerId);
  return sticker && message.content === `[表情包：${sticker.label}]` ? '' : message.content || '';
}
export function chatMessagePreview(message?: { content?: string; stickerId?: string; attachmentIds?: string[] }) {
  if (!message) return '暂无消息';
  const sticker = chatSticker(message.stickerId);
  const description = sticker ? `[表情包：${sticker.label}]` : message.stickerId ? '[表情包]' : '';
  return [description, chatMessageText(message), message.attachmentIds?.length ? `[附件 ${message.attachmentIds.length}]` : ''].filter(Boolean).join(' ');
}
