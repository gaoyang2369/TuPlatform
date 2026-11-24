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

发送和接受意向时会说明本轮联系方式交换。手机号、微信不随公开资料展示；只有互选且双方同意仍有效时，对方才能查看。支持停止分享，已分享字段变更后需要重新确认；旧记录不追溯授权，邮箱不分享。老师可以单独提交学籍或技能证书供运营核验，家长只看得到核验徽章。

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
| `PILOT_SCHOOL_NAME` | 老师资料的默认院校名，默认「深圳技术大学」，老师可在表单中修改。 |
| `AVATAR_DIRECTORY` | 头像持久化目录，默认是后端工作目录下的 `data/avatars`。打包运行时建议使用固定绝对路径，随数据库一同备份。 |
| `VERIFICATION_DIRECTORY` | 私有证件目录，默认位于后端运行账号主目录的 `.tuplatform/private_uploads/verifications`。自定义时使用仅服务账号可读写的持久化绝对路径，放在所有 Web 根目录、源码与静态发布目录之外，随数据库备份。 |
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

### 老师主页与简历验收

在专用测试库启动前后端后，用合成老师账号打开「我的资料」，填写可授年级、科目和一条完整案例，选择试课政策。点击「预览家长看到的样子」可核对未保存的编辑。保存后点击「预览并公开」并确认。

上传一份不含联系方式的 PDF（最大 5 MiB）。用另一浏览器会话登录需求方，从「发现老师」核对学院、年级和试课标签，打开资料抽屉核对案例并下载附件。老师删除、替换附件后原地址失效，停止公开后需求方无法再读取附件。PDF 数据保存在 PostgreSQL 中，随数据库备份。

| 操作 | 接口 | 输入与结果 |
| --- | --- | --- |
| 读取当前资料 | `GET /api/v1/me/profile` | 老师的完整资料在 `teacher` 内，包含附件名称与当前访问路径 |
| 保存老师资料 | `PUT /api/v1/me/teacher-profile` | 传 `expectedVersion`、基本资料及结构化字段。返回最新资料与版本 |
| 上传或替换 PDF | `POST /api/upload/resume` | multipart 的 `file`，可传 `expectedVersion`。返回 `resumePdfUrl`、`resumePdfName`、`version` |
| 删除自己的 PDF | `DELETE /api/upload/resume` | 可传查询参数 `expectedVersion`，返回空附件及当前版本 |
| 查看或下载 | `GET /api/v1/resumes/{id}.pdf` | 默认查看，`?download=true` 下载。仅本人或有权浏览当前公开资料的需求方可读 |

附件写入同样支持 `/api/v1/upload/resume`。沿用同源会话、CSRF 和 `X-Account-Id`。表单不接受自填附件地址。公开和停止公开沿用现有版本检查及幂等键。

### 本校学籍与技能证书验收

1. 按上文「浏览器测试」启动专用 `_test` 库、8081 测试后端及 5174 前端。V9 会在该测试库创建认证表。以下步骤均使用合成证件、已验证邮箱的老师和需求方、独立运营账号，三个角色分别登录独立浏览器会话。
2. 老师在「我的资料」补齐教学资料，保存并确认公开。在「本校学籍与资质认证」选择一份学生证测试图片或 PDF，点击「提交本校学籍认证」。状态变为「审核中」。技能证书可以单独提交。
3. 运营账号在「需求审核」展开「老师认证审核」，按申请人编号（老师的 `GET /api/v1/me` 返回的 `id`）核对申请。点击「下载证件核对」，确认材料能证明该老师在试点高校在读，然后点击「确认核验通过」。也可填写缺少的信息并驳回，老师刷新认证状态后可查看原因并重新提交。
4. 老师点击「刷新认证状态」，应显示「已核验」。需求方刷新「发现老师」，找到该老师并打开「老师资料」，卡片和抽屉均应显示「本校学籍已核验」。技能证书通过后会叠加第二枚徽章。
5. 复制老师的「下载本人已提交材料」链接。老师和运营账号可下载，需求方访问返回 404，未登录访问返回 401。在后端直接请求 `http://127.0.0.1:8081/private_uploads/verifications/<申请 UUID>.<实际扩展名>` 应返回 404。
6. 将这位合成老师的公开资料设为可授「初二」、教授「数学」、线下服务「坪山街道」且支持试课。需求方在「发现老师」选择这三个条件，再开启「支持试课」「仅看已核验」。刷新或在同一需求方会话的新标签页打开当前链接，确认条件保留且能找到这位老师。
7. 将科目改为测试库中无人教授的科目，确认出现「当前条件下暂未找到完全匹配的老师」。点击「清除筛选条件」应恢复全部老师。再次打开空结果链接，点击「发布我的需求，让本校老师主动联系你」应直接进入草稿第一步。直接返回「我的需求」核对草稿数量，发生编辑并自动或手动保存后才会新增记录。

自动复现无需手工创建账号。在已配置上述测试服务和 `TEST_DB_*` 的终端进入 `frontend`，运行 `npm.cmd run test:e2e -- verifications.spec.ts`。夹具创建三类合成账号，在桌面和手机宽度分别验证提交、驳回、重提、审核、下载权限和徽章。测试后端可使用独立的临时证件目录，便于清理合成材料。

### 需求隐私、草稿与待审推荐验收

1. 在上述专用测试库登录合成需求方。新建需求，填写标题并停顿约 1.5 秒，看到「已自动保存」及时间后返回列表。重开同一草稿，核对标题和草稿数量。
2. 选择街道及模糊片区，另填一个容易识别的合成楼栋地址。补齐后提交审核，在进度页和「我的需求」待审卡片核对推荐原因，点击老师打开资料并发送直接邀请。
3. 审核通过后用老师会话检查公开列表和详情 JSON，只能看到街道、片区等公开信息。对这条公开需求投递，双方确认并交换联系方式后，老师可在本次撮合详情查看具体地址。任一方停止分享后刷新，地址应随联系方式隐藏。

## 文档导航

- [首版规格](docs/mvp-spec.md)：当前业务规则与字段约束。
- [本地启动与验收](docs/local-run.md)：Windows 打包运行、运营账号、状态与停止命令。
- [核心验收清单](docs/acceptance/mvp-cases.md)：需求方与老师的完整流程及边界检查。
- [产品体验评审](docs/design/product-experience-review.md)：界面截图、改进记录与对应验证结果。
- [交付计划](docs/tasks/mvp-delivery-plan-2026-11-07.md)：开发进度与后续事项。
- [文档索引](docs/README.md)：设计、接口和历史材料入口。

参与修改前请阅读 [AGENTS.md](AGENTS.md)，遵循现有代码模式，使用合成数据，并验证受影响的双方流程。旧原型、演示包和归档接口仅供历史参考；当前实现以 `AccountApp.vue`、后端源码和首版规格为准。
