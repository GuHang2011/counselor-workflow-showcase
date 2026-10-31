# v1.3.0 导入、导出与打印源码摘录

本版公开 22 份审阅源文件，包含导入控制器、输出权限、文件校验、前端操作及测试。`shared/csv.ts` 为原有公式转义辅助模块，随本版一并列出。路径及 SHA256 见 [source-manifest.json](source-manifest.json)。源文件保持本地原样，这些摘录不能独立运行完整系统。

| 入口 | 内容 |
| --- | --- |
| `backend/.../WorkImportController.java` | 任务与床位的模板数据预检、权限复核、原子提交和重试 |
| `backend/.../StudentRecordsController.java`、`CollegeWorkController.java` | 学生和宿舍资料输出范围 |
| `backend/.../ChatService.java`、`ChatController.java` | 聊天完整历史与北京时间日期筛选 |
| `backend/.../AttachmentController.java`、`ResumableUploadController.java` | TXT / CSV 附件类型与编码检查 |
| `backend/.../WorkIoContractTests.java` | 本版 9 项权限、事务、历史与附件测试 |
| `frontend/shared/table-output.ts`、`csv.ts` | 中文表格、CSV 转义、文本节点打印 |
| `frontend/src/components/WorkImport.vue`、`ChatRecordOutput.vue` | 模板、错误清单、确认导入与聊天输出 |
| 学生、任务、宿舍、聊天和材料组件 | 电脑、手机响应式页面与移动 H5 操作 |

`backend/.../` 在上表表示 `backend/src/main/java/com/example/Kcsj/erp/`，测试文件位于 `backend/src/test/java/com/example/Kcsj/`。验收使用隔离账号和合成资料。没有公开有效登录口令、会话、真实学生资料、上传文件或部署二进制。

[使用说明](../../docs/data-io-v1.3.0.md) · [截图](../../docs/screenshots-v1.3.0.md) · [验收结果](../../docs/evidence/v1.3.0.json)
