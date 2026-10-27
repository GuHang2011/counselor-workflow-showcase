# 上线前优化代码摘录

这些文件来自 2026-10-27 至 2026-10-28 的实际 Vue 应用修改。它们保留原始实现，供代码审阅和接入参考；本目录没有完整业务组件、后端或项目构建配置，不能作为独立应用直接启动。公开展示页仍使用仓库根目录的 HTML/CSS/JavaScript。

| 文件 | 关注点 |
| --- | --- |
| [FeatureSearch.vue](src/components/FeatureSearch.vue) | 全部授权入口、中文输入法、键盘选择、焦点和 ARIA 状态 |
| [TaskReminder.vue](src/components/TaskReminder.vue) | 读取失败禁止覆盖偏好、重试、逐条进入、保存防重入 |
| [AiWorkbench.vue](src/components/AiWorkbench.vue) | 服务状态重试、按写作场景降级、优化失败保留原文 |
| [prelaunch.css](src/prelaunch.css) | 手机首屏、搜索弹窗高度、打印和键盘焦点 |
| [icons.ts](src/icons.ts) | 46 个实际图标的显式导入 |
| [integration.patch](integration.patch) | App 会话恢复和搜索接入、工作台标题换行、图标/CSS 注册 |
| [prelaunch.spec.ts](tests/browser/prelaunch.spec.ts) | 10 项新增浏览器回归，依赖完整应用的隔离测试环境 |
| [source-manifest.json](source-manifest.json) | 原始相对路径及源文件 SHA256，用于核对摘录 |

`src/api.ts` 和 `shared/` 是组件依赖的请求适配与类型上下文。只包含代码，没有真实令牌或账号。请求使用 `/api/` 同源路径；服务端必须自行执行角色和数据权限检查，不能把搜索入口过滤当作授权。

接回完整应用时，将对应文件放回相同相对路径，审阅并应用 `integration.patch`；在主 HTML 添加 `<link rel="icon" type="image/svg+xml" href="/favicon.svg">`。补丁针对本轮修改前的本地版本，其他版本须逐段合并。原项目使用 Vue 3、TypeScript、Element Plus，且 `@` 指向 `src/`。这些旧依赖仍有审计告警，不能仅凭组件运行正常判断可正式上线。

浏览器用例从 `.cache/ui-fixtures.json` 读取隔离验收临时账号，运行结束清理。此文件不在公开仓库中；不要为运行这些用例填入正式账号。公开仓库可直接运行的测试仍是根目录 `node --test tests/contracts.test.mjs`。

完整问题、结果和边界见 [验收记录](../../docs/prelaunch-review-20261028.md)。
