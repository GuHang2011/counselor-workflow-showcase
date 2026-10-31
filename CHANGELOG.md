# Changelog

## [1.3.1] - 2026-11-01

- 参考 Element 的一致、反馈、效率和可控原则，统一导航、按钮、输入框、表格与弹窗的层级、间距和边框。
- 任务范围采用清晰的选中下划线；导入与发布操作集中排列，筛选与导出打印分区；任务和学生表格操作列固定在右侧。
- 学生查询增加字段标签与回车提交，减少重复标题和嵌套留白，常用筛选折叠显示。
- 手机首页完整展示五类待办，不再依赖横向滑动；逾期状态保留醒目的文字与颜色。
- 登录页统一浅蓝配色和表单边框，保留学院特色插画；补全 Element Plus 弹窗的中文关闭标签。
- 使用现有 Vue 3 / Element Plus；无新增依赖、后端改动或数据库迁移。

## [1.3.0] - 2026-11-01

- 学生名单增加按当前列表导出 CSV 和打印；输出时重新校验范围。
- 工作任务增加 CSV / XLSX 导入、逐行检查、错误清单及确认发布，支持幂等重试。
- 床位导入升级为预检确认流程，保留入住关系并防止重复床位；床位、住宿变动和卫生记录分别导出、打印。
- 任务、谈话跟进、团党发展、卫生、归档使用中文栏目和专用打印表，支持浏览器另存为 PDF。
- 聊天记录支持按北京时间日期范围导出 TXT / CSV 和打印，包含完整历史、表情说明及附件名称。
- 聊天和业务附件新增 UTF-8 TXT / CSV 支持，保留文件检查、下载权限与安全响应头。
- 新增导入导出权限、事务、重试、日期边界和文本附件专项测试；导入预检接入资料留存策略。

## [1.2.0] - 2026-11-01

### 聊天表情包
- 内部聊天新增 12 张原创“校园小助手”办公表情包和 24 个常用表情，电脑网页与移动 H5 共用素材。
- 选择表情包后先预览，再点击发送；支持移除、按会话保留草稿，与文字或附件一起发送。
- 常用表情加入文字输入框；桌面支持在光标处插入，保留长度限制。
- 消息历史、会话摘要及已读状态支持表情包；发送失败保留选择，重试复用消息标识避免重复。
- 服务端只接受内置表情标识，沿用联系人权限与附件检查；旧客户端可显示文字说明。
- 无新增 SQL 迁移；新增 3 项后端契约测试、HTTP 与重启持久化检查。完整说明见 [v1.2.0](docs/releases/v1.2.0.md)。

## [1.1.3] - 2026-11-01

### 用户体验与界面
- 优化手机任务卡片和工具栏，下载打印进入“更多”，常用办理按钮突出。
- 切换任务范围清除残留筛选，逾期及临期任务优先显示。
- 读取失败提供重试并隐藏误导性列表与计数，保留当前汇报编辑。
- 发布表单重排提交要求，提供快捷日期；长弹窗保留底部操作。
- 汇报和新闻重新生成前确认，汇报提示范围变化并显示生成时间。
- 修复手机首页分类选中效果与编辑框名称，统一字号、状态颜色和留白。
- 15 项共享逻辑、37 项浏览器回归和隔离 HTTP/聊天/重启检查通过。后端产物沿用 v1.1.2，未重新运行其 135 项单元测试。
- 完整说明见 [v1.1.3](docs/releases/v1.1.3.md)。

## [1.1.2] - 2026-10-31

### 功能设计修复
- 待办弹窗和站内消息直接打开对应事项；同一事项关闭后可以再次打开。
- 已提交但尚待审核时，旧消息显示“此条提醒已结束”，避免误报任务完成。
- 手机增加当前筛选范围内的全选审核；改变筛选后排除不可见项，保留退回批注与版本校验。
- 未开始任务不提前催办；新任务不能使用已过期的截止时间。
- 谈话跟进与团党阶段更新要求下次提醒晚于当前时间，前后端共同验证。
- 页面每分钟刷新逾期状态；已提交、已办结和未开始任务不误计逾期。
- 个人统计加载失败显示重试，不展示假零或旧数字，并说明统计口径。

### 验证与交付
- 后端 135 项、共享逻辑 13 项、浏览器 37 项回归通过；15 项 HTTP 检查、聊天及重启持久化通过。
- 新增 3 项后端回归、3 项日期判断测试和 5 项浏览器场景；反转逾期判断的变异检查能发现错误。
- 版本继续使用独立 VERSION，保留 v1.1.1 公开目录调整；详见 [v1.1.2](docs/releases/v1.1.2.md)。

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
