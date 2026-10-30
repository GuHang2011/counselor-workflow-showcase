# v1.1.0 实际代码摘录

本目录整理 v1.1.0 的 UI、安全修复与版本化打包源码。v1.1.1 将前后端子目录统一为 `frontend`、`backend`，并同步打包脚本及测试夹具的路径；应用业务源码未改写。共 37 份源码/配置文件，当前文件校验值见 [source-manifest.json](source-manifest.json)。这些摘录缺少完整业务模块和运行环境，不能直接启动完整系统；历史 `vue-prelaunch` 目录保留当时快照。原始路径及原文件校验值可通过不可变的 [v1.1.0 标签](https://github.com/GuHang2011/counselor-workflow-showcase/tree/v1.1.0/examples/app-v1.1.0) 核对。

| 范围 | 文件与作用 |
| --- | --- |
| 会话与导出 | `frontend/shared/api-client.ts`、`csv.ts`；对应 `tests/*.test.cjs` |
| 校园办公 UI | `src/App.vue`、`workspace.css`、`components/EngineeringIllustration.vue`、`WorkDashboard.vue`、`CollegeWork.vue` |
| AI 与附件交互 | `src/components/AiWorkbench.vue`、`MaterialUpload.vue` |
| 后端保护 | `backend/src/main/java/com/example/Kcsj/erp/` 中的登录与 AI 配置实现 |
| 后端回归 | `backend/src/test/java/com/example/Kcsj/` 下的三个测试类 |
| 依赖与部署 | 前端锁文件、POM、CSP/Nginx 示例、版本化打包及打包保护测试 |

`application.properties` 只包含环境变量占位符及非敏感默认值；测试中的口令和令牌是合成夹具，不是可用账号。未发布数据库、附件、真实配置和完整日志。配置示例不能直接替代目标服务器审核。

公开仓库可直接运行 `python scripts/check_version.py`、`python -m unittest discover -s tests -p test_version.py` 和 `node --test tests/contracts.test.mjs`。应用摘录的单元测试需要 TypeScript，完整业务测试需要原应用的隔离后端。打包入口也只在完整项目中使用。

完整变更见 [v1.1.0 说明](../../docs/releases/v1.1.0.md)，逐条风险见 [安全复查](../../docs/security-review-20261030.md)。
