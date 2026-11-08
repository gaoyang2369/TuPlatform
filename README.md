# TuPlatform · 邻课

面向深圳坪山区的供需撮合产品，个人开发、Web首发。技术栈：Vue 3＋TypeScript、Java/Spring Boot、PostgreSQL。

2026-11-08已开始第一段真实开发，目前已写入注册/邮箱验证、登录/退出、正常找回密码、单一身份、本人资料和老师资料主动公开的前后端代码及数据库迁移。数据库、SMTP及真实联调尚未配置；这不是业务已验收或已部署的版本。

业务规则从[首版规格](docs/mvp-spec.md)进入；当前进度看[交付计划](docs/tasks/mvp-delivery-plan-2026-11-07.md)，验收看[核心清单](docs/acceptance/mvp-cases.md)。需求草稿、审核公开、投递互选及联系方式授权继续按后续业务段开发。

## 前端

要求Node.js ≥24.11.0。依赖已锁定；首次使用在frontend中运行npm.cmd ci，然后：

~~~powershell
cd frontend
npm.cmd run dev
~~~

默认打开终端给出的地址。当前入口调用真实API，不使用浏览器模拟账号。未启动后端时，会提示连接失败并保留表单输入。开发代理默认转发/api到http://127.0.0.1:8080，可按[前端配置示例](frontend/.env.example)设置API_PROXY_TARGET；前端配置中不能放数据库或邮件凭据。

~~~powershell
npm.cmd test
npm.cmd run typecheck
npm.cmd run build
~~~

构建结果为frontend/dist，部署时前端及/api应同源。旧App.vue、domain.ts和原型测试保留作历史参考，不从当前入口加载；旧测试可单独运行npm.cmd run test:legacy。当前构建不再导出旧p0-prototype.html，旧导出脚本与旧演示ZIP不是当前交付物。

## 后端与待填配置

要求JDK 21或更高；当前使用JDK 25编译。Spring Boot固定3.5.16，数据库迁移由Flyway管理。配置字段见[backend/.env.example](backend/.env.example)，运行配置见[application.yml](backend/src/main/resources/application.yml)。Spring不会自动读取.env文件，需要把值注入进程环境或IDE运行配置。

Windows可在项目根目录运行：

~~~powershell
.\backend\mvnw.ps1 test
.\backend\mvnw.ps1 package
# 提供DB_URL、DB_USERNAME、DB_PASSWORD等环境变量后再启动：
.\backend\mvnw.ps1 spring-boot:run
~~~

脚本优先使用PATH中的Maven；否则下载并校验项目内的Maven 3.9.11，工具及依赖放在.tools下，不安装系统服务。其他平台可在backend目录使用Maven 3.6.3+执行mvn test、mvn package及mvn spring-boot:run。

后续接入服务器PostgreSQL时，需要JDBC地址（包含IP/域名、端口、库名及实际TLS参数）、用户名和密码；仅有IP和端口不足以建立连接。首次启动会在该数据库执行V1迁移，当前不自动创建数据库或运营账号。后端默认只监听127.0.0.1，按实际运行位置设置SERVER_ADDRESS。本地HTTP开发须显式设置SECURE_COOKIES=false；HTTPS部署使用true。

邮件默认MAIL_ENABLED=false：注册可保存但明确返回未受理发信；未验证账号不能登录业务，找回密码统一提示邮件服务不可用。后续填SMTP_HOST/PORT/USERNAME/PASSWORD、MAIL_FROM及PUBLIC_BASE_URL后开启MAIL_ENABLED。不会用日志、模拟链接或跳过验证代替邮件。忘记密码的受理响应不承诺送达，重置邮件在事务提交后由有限的内存队列发送；进程退出或发信故障时可能需要重新申请。生产邮件可靠性仍需实测。

TEACHER_CATEGORIES默认留空，不把历史类别代码自动视为真实开放。合成联调可显式设为DRAWING,DANCE,OTHER_SKILL,EXTRACURRICULAR_STUDY；真实开放按业务决定配置。

## 后续数据库验证

普通代码测试不需要数据库、Docker或SMTP。已准备额外的PostgreSQL集成测试，默认跳过；只有显式设置以下环境变量才会执行：

~~~powershell
$env:RUN_DATABASE_TESTS = 'true'
$env:TEST_DB_URL = 'jdbc:postgresql://YOUR_TEST_SERVER:5432/linke_test?sslmode=verify-full'
$env:TEST_DB_USERNAME = 'YOUR_TEST_USER'
$env:TEST_DB_PASSWORD = 'YOUR_TEST_PASSWORD'
.\backend\mvnw.ps1 test
~~~

测试只接受库名以_test结尾的专用测试库，不读取普通DB_URL。测试会执行迁移并创建/清理合成账号，邮件传输仍为替身；真实邮件、浏览器跨账号、数据库并发及手机操作还需在配置后按核心验收执行。16项核心验收目前全部待真实验证。

原Figma及[只读设计预览](docs/design/interaction-preview.html)继续作为设计参考；当前源码和测试证据与历史模拟演示分开记录。
