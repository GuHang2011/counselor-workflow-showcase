# v1.2.0 聊天表情包源码与素材

本次公开 24 份原始文件，包含前后端代码、回归检查、素材说明及 12 张原创 PNG。路径映射和 SHA256 见 [source-manifest.json](source-manifest.json)。这里是审阅摘录，无法单独运行完整应用。

| 入口 | 内容 |
| --- | --- |
| `frontend/src/components/InternalChat.vue`、`ChatExpressions.vue` | 电脑/响应式聊天、选择预览、草稿及发送重试 |
| `frontend/mobile/src/components/MobileChat.vue`、`MobileChatExpressions.vue` | 移动 H5 表情面板和双向收发 |
| `frontend/shared/chat-expressions.ts`、`chat-stickers/` | 两端共用 12 张图片、24 个常用表情及文字回退 |
| `backend/src/main/java/com/example/Kcsj/erp/ChatService.java`、`ChatStickerCatalog.java` | 内置标识、会话权限和幂等校验 |
| `backend/src/test/java/com/example/Kcsj/ChatContractTests.java` | 消息历史、非法标识、重试及附件权限测试 |
| `tests/erp_chat_smoke.py`、`tests/erp_acceptance.py` | HTTP 和后端重启后的表情消息持久化检查 |
| `frontend/src/App.vue` | 功能搜索支持“表情包” |

源文件保留本地原样；测试中使用的原工程目录由 `localSource` 标明。测试依赖未公开的完整应用和隔离环境。没有公开有效登录口令、会话、真实学生资料、上传文件或部署二进制。

[使用说明](../../docs/chat-stickers-v1.2.0.md) · [截图](../../docs/screenshots-v1.2.0.md) · [验证结果](../../docs/evidence/v1.2.0.json)
