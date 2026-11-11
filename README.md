# TuPlatform · 邻课

邻课是一个面向深圳坪山区的老师与需求方撮合 Web 项目。需求方可以直接邀请老师，也可以发布需求、等待老师投递；双方互选后，在有效同意的范围内交换联系方式，再自行沟通。

项目由个人开发，目前处于本地 MVP 阶段，尚未部署为公开服务。仓库公开供阅读和交流，实际服务类别、运营账号与对外开放安排仍待落实。

## 核心功能

| 使用者 | 可以做什么 |
| --- | --- |
| 需求方 | 浏览老师并直接邀请；保存需求草稿、提交审核；处理老师投递与撮合进度 |
| 老师 | 维护并主动公开个人资料；浏览公开需求并投递；回应邀请 |
| 运营 | 审核需求，通过或填写理由驳回；运营权限由服务端单独授予 |
| 匿名访客 | 浏览审核通过且仍有效的公开需求 |

两条联系路径独立存在：

- **直接邀请**：发现老师 → 发送邀请（留言选填）→ 老师接受 → 交换联系方式，无需先发布需求。
- **发布需求**：填写需求 → 审核公开 → 老师投递 → 需求方接受 → 交换联系方式。

发送和接受意向时会说明本轮联系方式交换。手机号、微信不随公开资料展示；只有互选且双方同意仍有效时，对方才能查看。支持停止分享，已分享字段变更后需要重新确认；旧记录不追溯授权，邮箱不分享。老师资料为本人自述，不代表平台资质认证。

当前不包含站内聊天、预约排课、支付、评价或原生 App。完整规则见[首版规格](docs/mvp-spec.md)。

## 技术与目录

| 部分 | 技术 / 要求 |
| --- | --- |
| 前端 | Vue 3、TypeScript、Vite；Node.js ≥ 24.11.0 |
| 后端 | Java ≥ 21、Spring Boot 3.5.16；Maven ≥ 3.6.3 或仓库提供的 Windows 包装脚本 |
| 数据库 | PostgreSQL；使用 Flyway 管理迁移 |
| 测试 | Node.js Test Runner、JUnit / Spring Boot Test、Playwright |

```text
frontend/    前端应用，当前入口为 src/AccountApp.vue
backend/     API、权限校验、数据库迁移与后端测试
scripts/     本地启动脚本
tools/       本地验收辅助工具
docs/        业务规格、设计、验收和开发记录
```

## 本地开发

以下命令以 Windows PowerShell 为例。其他平台可在 `frontend` 中使用 `npm`，在 `backend` 中使用已安装的 Maven 执行对应命令。

### 1. 获取源码与依赖

```powershell
git clone https://github.com/gaoyang2369/TuPlatform.git
cd TuPlatform
cd frontend
npm.cmd ci
cd ..
```

### 2. 准备数据库与后端配置

自行准备一个独立的 PostgreSQL 开发数据库及有建表权限的账号。仓库不提供数据库、邮件服务或预置业务账号。

首次配置时，将 [backend/.env.example](backend/.env.example) 复制为 `backend/.env`；已有配置请直接编辑，避免覆盖：

```powershell
Copy-Item backend/.env.example backend/.env
```

按自己的环境填写以下配置，完整字段见示例文件和 [application.yml](backend/src/main/resources/application.yml)：

| 配置 | 说明 |
| --- | --- |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | 数据库地址、端口和实际库名，库名区分大小写 |
| `DB_USER` / `DB_PASSWORD` | 数据库账号与密码 |
| `DB_SSLMODE` | 按数据库 TLS 配置设置；仅本机无 TLS 的开发库可用 `disable` |
| `DB_URL` / `DB_USERNAME` | 可选，分别覆盖分项连接地址与用户名 |
| `PUBLIC_BASE_URL` | 用户访问前端的地址，用于邮件链接；本地开发可设为 `http://127.0.0.1:5173` |
| `SECURE_COOKIES` | 本地 HTTP 设为 `false`；HTTPS 部署设为 `true` |
| `MAIL_ENABLED` | 默认 `false`；完整注册、邮箱验证与找回密码需配置 SMTP 并开启邮件 |
| `TEACHER_CATEGORIES` / `REQUEST_CATEGORIES` | 默认留空，表示未开放类别；隔离的合成开发环境可分别设为 `DRAWING` |

后端启动时会校验并应用尚未执行的 Flyway 迁移，不会自动创建数据库。请使用自己的开发库；在共享数据库上应用新迁移前，应先审核脚本并取得该环境的授权。

`backend/.env` 已被 Git 忽略。不要提交真实凭据，也不要把数据库或 SMTP 配置放进前端。后端从工作目录读取 `.env`，进程环境变量优先；下面的包装脚本会自动进入 `backend` 目录。

### 3. 启动后端和前端

在项目根目录启动后端：

```powershell
.\backend\mvnw.ps1 spring-boot:run
```

默认监听 `http://127.0.0.1:8080`。Windows 包装脚本使用项目内缓存或 PATH 中的 Maven；需要下载时会校验 Maven 3.9.11，工具及依赖缓存在 `.tools` 中。

另开终端，在项目根目录启动前端：

```powershell
cd frontend
npm.cmd run dev
```

打开 `http://127.0.0.1:5173`。开发服务器将 `/api` 代理到后端；若要修改目标，参照 [frontend/.env.example](frontend/.env.example) 设置 `API_PROXY_TARGET`。当前应用调用真实 API，需要运行后端。

初次运行请留意：

- 关闭邮件时，注册请求不会发送验证邮件，未验证账号不能登录；请配置自己的 SMTP 后再走完整注册流程。
- 类别为空时，不能完成依赖类别的发布或老师公开流程；合成环境的测试类别不代表实际服务已开放。
- 普通账号不能自行获得运营权限。Windows 本地验收启动器可初始化专用运营账号并打包前后端，详见[本地启动与验收](docs/local-run.md)。该工具包含既有环境的数据库名和 SSH 隧道约定，使用前请核对配置。

生产构建分别运行 `npm.cmd run build`（在 `frontend` 中）和 `.\backend\mvnw.ps1 package`（在项目根目录）。前端输出为 `frontend/dist`；部署时前端与 `/api` 应同源。本地启动配置不等同于公网部署方案。

## 测试与验证

普通代码检查不需要数据库、Docker 或 SMTP：

```powershell
# 项目根目录
.\backend\mvnw.ps1 test

cd frontend
npm.cmd test
npm.cmd run typecheck
npm.cmd run build
```

### 数据库集成测试

数据库测试默认跳过，只接受库名以 `_test` 结尾的专用测试库。测试会执行迁移并创建、清理合成数据，不要连接业务数据库。

在项目根目录设置自己的测试连接后运行：

```powershell
$env:RUN_DATABASE_TESTS = 'true'
$env:TEST_DB_URL = 'jdbc:postgresql://YOUR_TEST_HOST:5432/tuplatform_test?sslmode=verify-full'
$env:TEST_DB_USERNAME = 'YOUR_TEST_USER'
$env:TEST_DB_PASSWORD = 'YOUR_TEST_PASSWORD'
.\backend\mvnw.ps1 test
```

按测试环境配置 TLS 与证书；普通数据库测试替换邮件传输。真实 SMTP 测试另行显式开启，会实际发信，不属于默认检查。

### 浏览器测试

Playwright 配置包含桌面 `1366 × 900` 和手机宽度 `390 × 844`，默认使用本机 Chrome。运行完整浏览器套件前需准备：

1. 上述专用测试库及 `TEST_DB_*` 环境变量，供浏览器测试夹具使用。
2. 监听 `8081` 的测试后端：将该进程的 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 设为同一测试库，设置 `SERVER_PORT=8081`、`SECURE_COOKIES=false`、`MAIL_ENABLED=false`，并将两个类别配置均设为 `DRAWING`。
3. 代理到测试后端的前端：在另一个终端的 `frontend` 目录，设置 `$env:API_PROXY_TARGET = 'http://127.0.0.1:8081'`，运行 `npm.cmd run dev -- --port 5174`。

随后在已设置 `TEST_DB_*` 的终端进入 `frontend`，运行 `npm.cmd run test:e2e`。手机宽度仿真不等于真机验证。历史检查结果与未验证边界见[核心验收清单](docs/acceptance/mvp-cases.md)及[产品体验评审](docs/design/product-experience-review.md)。

## 文档导航

- [首版规格](docs/mvp-spec.md)：当前业务规则与字段约束。
- [本地启动与验收](docs/local-run.md)：Windows 打包运行、运营账号、状态与停止命令。
- [核心验收清单](docs/acceptance/mvp-cases.md)：需求方与老师的完整流程及边界检查。
- [产品体验评审](docs/design/product-experience-review.md)：界面截图、改进记录与对应验证结果。
- [交付计划](docs/tasks/mvp-delivery-plan-2026-11-07.md)：开发进度与后续事项。
- [文档索引](docs/README.md)：设计、接口和历史材料入口。

参与修改前请阅读 [AGENTS.md](AGENTS.md)，遵循现有代码模式，使用合成数据，并验证受影响的双方流程。旧原型、演示包和归档接口仅供历史参考；当前实现以 `AccountApp.vue`、后端源码和首版规格为准。
