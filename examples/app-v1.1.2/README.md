# v1.1.2 功能设计修复摘录

保留 9 份本轮变更的原始源码和回归测试，按 v1.1.1 的 frontend/backend 命名组织。每份文件的完整本地路径映射及 SHA256 见 [source-manifest.json](source-manifest.json)。历史摘录保留在 [app-v1.1.0](../app-v1.1.0/README.md)，这里不是可独立运行的完整应用。

| 范围 | 文件 |
| --- | --- |
| 提醒直达与重复打开 | `frontend/src/App.vue`、`frontend/src/components/TaskReminder.vue` |
| 手机全选、筛选范围、逾期和跟进校验 | `frontend/src/components/CollegeWork.vue`、`frontend/shared/college-work.ts` |
| 统计读取、重试与口径 | `frontend/src/components/CollegeReports.vue` |
| 服务端时间规则和催办 | `backend/src/main/java/com/example/Kcsj/erp/CollegeWorkController.java` |
| 业务回归 | `backend/src/test/java/com/example/Kcsj/CollegeWorkTests.java` |
| 界面与日期回归 | `frontend/tests/browser/functional-design.spec.ts`、`frontend/tests/college-work.test.cjs` |

浏览器新增场景通过真实隔离登录后使用合成接口数据模拟边界，不包含真实学生或账号资料。完整测试依赖未公开的应用其余模块与隔离测试环境；这里不提供可用登录口令、会话、数据文件或部署二进制。

[版本说明](../../docs/releases/v1.1.2.md) · [逐项问题与修复](../../docs/functional-review-20261031.md) · [验收摘要](../../docs/evidence/v1.1.2.json)
