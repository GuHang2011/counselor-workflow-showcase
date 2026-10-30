# Changelog

## [1.1.1] - 2026-10-31

- 按辅导员工作流项目用途整理公开代码摘录：前端目录统一为 `frontend`，后端为 `backend`。
- 同步摘录说明、打包脚本、打包回归夹具和源码校验清单中的路径。
- 历史验收报告与原始版本标签保留，新增迁移说明以区分当前目录与历史来源。

## [1.1.0] - 2026-10-30

### 界面与交互
- 采用清爽校园办公风格，登录页使用学院主题原创插画；手机首屏可直接登录。
- 首页优先展示待办与进度，管理员查看人员进度，辅导员聚焦自己的工作。
- 统一导航、表格、表单和弹窗；任务详情直接进入办理；未保存内容关闭前确认。

### 安全修复
- 隔离旧账号延迟响应，断网退出立即清除本地身份，取消离开页面后的附件下载。
- 五处 CSV 导出统一防范公式注入；登录失败分别按账号与来源限流。
- 修复 AI 配置持久化和实际请求不一致；密钥加密保存、不回显，仅超级管理员配置。
- AI 远程地址使用 HTTPS，禁止自动重定向，限制响应大小；宝塔模板增加浏览器安全策略。
- 更新 Vue、Lodash、Rollup 等依赖，桌面工程审计告警由 25 项降至 9 项。

### 发布管理与验证
- 新增 VERSION、逐版本说明、版本校验和版本化打包入口；禁止覆盖旧包和旧标签。
- 应用验证：132 项后端测试、10 项共享客户端/CSV 测试、15 项 HTTP 检查、32 项浏览器回归及重启持久化通过。
- 完整说明见 [v1.1.0](docs/releases/v1.1.0.md)。目标 MySQL、HTTPS、病毒扫描和旧框架风险仍需上线前处理。


## 2026-10-28

- Published actual Vue extracts for feature search, reminder preferences and AI fallback, with integration changes for session recovery, dashboard labels and icon imports.
- Added 10 browser regression cases as source references and the sanitized result of the full 32-case isolated run.
- Added four current mobile/desktop screenshots and a gallery on the static showcase.
- Documented unresolved dependency warnings and target Baota/MySQL validation boundaries.

## Earlier updates

- Added the Smart Engineering Technology College login, counselor dashboard shortcut and feature-search screenshots from the isolated review environment.
- Added a `Ctrl/Cmd + K` feature search to the static showcase with keyword filtering and direct section navigation.
- Documented the 2026-10-26 UI refresh, role-aware entry points and validation evidence in `docs/ui-refresh-20261026.md`.
- Added the role and module switching showcase and standalone workflow state model.
- Added architecture, API contract, and reading notes.
- Added actual blank desktop/mobile login screenshots and a public-data boundary statement.
- Restricted transfers to the current owner, required an explicit next owner, and checked stale versions.
- Required explicit server deduplication support and a stable key for mutation retry eligibility.
- Added automated contract checks and local run instructions.
## 2026-10-09

- Added sanitized task, dormitory and AI-news screenshots for desktop and mobile review.
- Documented report generation, PPT output, bulk file review, account states and AI data-boundary decisions.
- Clarified that the public repository is a static showcase and does not contain the production application or private data.
