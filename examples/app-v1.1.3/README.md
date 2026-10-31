# v1.1.3 用户体验优化摘录

本目录保留 9 份当前源码与测试，覆盖导航、工作台、任务页面、汇报、响应式样式和任务排序。文件映射及 SHA256 见 [source-manifest.json](source-manifest.json)。`CollegeWork.vue` 的 scoped CSS 已拆到同目录 `college-work.css`，二者需要同时集成。本版没有修改后端代码。

| 范围 | 文件 |
| --- | --- |
| 手机页头、输入框与弹窗 | `frontend/src/App.vue`、`frontend/src/workspace.css` |
| 首页单一选中分类、待办与进度 | `frontend/src/components/WorkDashboard.vue` |
| 任务排序、范围切换、失败重试、发布表单 | `frontend/src/components/CollegeWork.vue`、`frontend/src/components/college-work.css` |
| 汇报覆盖确认、范围提示与编辑保护 | `frontend/src/components/CollegeReports.vue` |
| 排序逻辑与边界测试 | `frontend/shared/college-work.ts`、`frontend/tests/college-work.test.cjs` |
| 批量审核与筛选回归 | `frontend/tests/browser/functional-design.spec.ts` |

这是审阅摘录，不能独立运行；完整应用其余模块及测试环境在本地。历史摘录保留在 [app-v1.1.2](../app-v1.1.2/README.md) 和 [app-v1.1.0](../app-v1.1.0/README.md)。浏览器边界场景使用隔离账号和合成数据，不含真实学生、密码、会话或附件。

[逐项问题与解决](../../docs/ux-review-20261031.md) · [版本说明](../../docs/releases/v1.1.3.md) · [验收摘要](../../docs/evidence/v1.1.3.json)
