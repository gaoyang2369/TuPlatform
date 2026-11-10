# TuPlatform · 邻课

面向深圳坪山区的供需撮合产品，个人开发、Web首发。技术栈：Vue 3＋TypeScript、Java/Spring Boot、PostgreSQL。

2026-11-10已接入真实PostgreSQL和163 SMTP，完成账号/基本资料联调，并实现需求三步草稿的保存、重开修改、失败恢复和离开确认。V1、V2已在获准的业务库及专用测试库执行。提交审核、发现列表、撮合及联系方式授权仍待后续开发，当前不是完整首版，也未部署到服务器。

业务规则从[首版规格](docs/mvp-spec.md)进入；当前进度看[交付计划](docs/tasks/mvp-delivery-plan-2026-11-07.md)，验收看[核心清单](docs/acceptance/mvp-cases.md)。

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

## 后端与本地配置

要求JDK 21或更高；当前使用JDK 25编译。Spring Boot固定3.5.16，数据库迁移由Flyway管理。配置字段见[backend/.env.example](backend/.env.example)，运行配置见[application.yml](backend/src/main/resources/application.yml)。复制示例为backend/.env后填写私密值；从backend目录运行时自动读取，进程环境变量优先。mvnw.ps1会进入该目录；.env已被Git忽略。

Windows可在项目根目录运行：

~~~powershell
.\backend\mvnw.ps1 test
.\backend\mvnw.ps1 package
# 填好backend/.env，必要时先开启下述SSH隧道：
.\backend\mvnw.ps1 spring-boot:run
~~~

脚本优先使用PATH中的Maven；否则下载并校验项目内的Maven 3.9.11，工具及依赖放在.tools下，不安装系统服务。其他平台可在backend目录使用Maven 3.6.3+执行mvn test、mvn package及mvn spring-boot:run。

可用DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD分项配置，也可用DB_URL/DB_USERNAME覆盖。库名区分大小写，当前服务器实际库名为tuplatform。首次启动会执行尚未应用的V1/V2迁移，当前不自动创建数据库或运营账号。后端默认只监听127.0.0.1，本地HTTP须显式设置SECURE_COOKIES=false；HTTPS部署使用true。

当前服务器PostgreSQL只监听回环地址。本地联调使用SSH本地转发，未修改服务器监听、防火墙、角色权限或重启服务。在单独终端运行以下命令并保持开启，按提示输入服务器密码：

~~~powershell
.\backend\open-db-tunnel.ps1 -ServerHost YOUR_SERVER_IP -ServerUser YOUR_SERVER_USER
~~~

隧道默认本地端口15432；backend/.env中的DB_URL使用jdbc:postgresql://127.0.0.1:15432/tuplatform?sslmode=require。脚本忽略用户SSH配置中的其他转发，只绑定本地127.0.0.1。首次连接请核对主机指纹。TLS已验证可用，当前require加密不等于已配置数据库CA证书校验。

邮件默认MAIL_ENABLED=false：注册可保存但明确返回未受理发信，未验证账号不能登录业务。配置SMTP_HOST/PORT/USERNAME/PASSWORD、SMTP_FROM（或MAIL_FROM）及PUBLIC_BASE_URL后开启MAIL_ENABLED。163端口465使用SMTP_SECURE=true和SMTP_STARTTLS=false；端口587通常使用相反设置。当前163诊断邮件已由用户确认收到，实际注册验证及重置模板也已被SMTP接受。忘记密码的受理响应不承诺送达，重置邮件在事务提交后由有限的内存队列发送；进程退出或发信故障时可能需要重新申请。

TEACHER_CATEGORIES与REQUEST_CATEGORIES默认留空，不把历史类别代码自动视为真实开放。本次测试库显式配置DRAWING，仅用于合成测试；真实开放按业务决定配置。类别为空时草稿仍可保存其他内容。

## 测试

普通代码测试不需要数据库、Docker或SMTP。已准备额外的PostgreSQL集成测试，默认跳过；只有显式设置以下环境变量才会执行：

~~~powershell
$env:RUN_DATABASE_TESTS = 'true'
$env:TEST_DB_URL = 'jdbc:postgresql://YOUR_TEST_SERVER:5432/linke_test?sslmode=verify-full'
$env:TEST_DB_USERNAME = 'YOUR_TEST_USER'
$env:TEST_DB_PASSWORD = 'YOUR_TEST_PASSWORD'
.\backend\mvnw.ps1 test
~~~

测试只接受库名以_test结尾的专用测试库，不读取普通DB_URL。测试会执行迁移并创建/清理合成账号；常规数据库测试替换邮件传输。当前28项后端测试（含8项真实数据库测试）通过；两项真实SMTP测试单独显式启用，不在常规回归中重复发信。

浏览器联调在frontend运行npm.cmd run test:e2e，需要上述TEST_DB_*环境变量、已启动的测试后端8081、代理到8081的Vite5174，以及本机Chrome。测试后端用DB_URL指向专用测试库、MAIL_ENABLED=false、TEACHER_CATEGORIES=DRAWING、REQUEST_CATEGORIES=DRAWING；Vite用API_PROXY_TARGET=http://127.0.0.1:8081和npm.cmd run dev -- --port 5174启动。Playwright会创建并清理合成账号，只接受_test库；使用桌面及手机宽度仿真，不代表真机验证。结果和截图在.tools/stage1-browser，实际覆盖范围见核心验收记录。

原Figma及[只读设计预览](docs/design/interaction-preview.html)继续作为设计参考；当前源码和测试证据与历史模拟演示分开记录。
