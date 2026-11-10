# TuPlatform · 邻课

面向深圳坪山区的供需撮合产品，个人开发、Web首发。技术栈：Vue 3＋TypeScript、Java/Spring Boot、PostgreSQL。

2026-11-10已完成本地首版撮合闭环：注册登录、本人资料、需求草稿/提交审核/公开、老师发现、投递/邀请、回应互选，以及本人私密手机号/微信、双方逐项授权、撤回和已选字段变更后的双方重确认。邮箱不分享。获准的业务库及专用测试库均已执行V1—V5并通过重复启动校验；合成数据库和Chrome桌面/手机宽度验收完成。未部署到服务器，真实开放类别及运营账号尚未开放。

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

可用DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD分项配置，也可用DB_URL/DB_USERNAME覆盖。库名区分大小写，当前服务器实际库名为tuplatform。启动会校验V1—V5并执行尚未应用的迁移；两库V5已获许可执行，后续新增迁移仍须先准备具体脚本并取得许可。当前不自动创建数据库或运营账号。后端默认只监听127.0.0.1，本地HTTP须显式设置SECURE_COOKIES=false；HTTPS部署使用true。

当前服务器PostgreSQL只监听回环地址。本地联调使用SSH本地转发，未修改服务器监听、防火墙、角色权限或重启服务。在单独终端运行以下命令并保持开启，按提示输入服务器密码：

~~~powershell
.\backend\open-db-tunnel.ps1 -ServerHost YOUR_SERVER_IP -ServerUser YOUR_SERVER_USER
~~~

隧道默认本地端口15432；backend/.env中的DB_URL使用jdbc:postgresql://127.0.0.1:15432/tuplatform?sslmode=require。脚本忽略用户SSH配置中的其他转发，只绑定本地127.0.0.1。首次连接请核对主机指纹。TLS已验证可用，当前require加密不等于已配置数据库CA证书校验。

邮件默认MAIL_ENABLED=false：注册可保存但明确返回未受理发信，未验证账号不能登录业务。配置SMTP_HOST/PORT/USERNAME/PASSWORD、SMTP_FROM（或MAIL_FROM）及PUBLIC_BASE_URL后开启MAIL_ENABLED。163端口465使用SMTP_SECURE=true和SMTP_STARTTLS=false；端口587通常使用相反设置。当前163诊断邮件已由用户确认收到，实际注册验证及重置模板也已被SMTP接受。忘记密码的受理响应不承诺送达，重置邮件在事务提交后由有限的内存队列发送；进程退出或发信故障时可能需要重新申请。

TEACHER_CATEGORIES与REQUEST_CATEGORIES默认留空，不把历史类别代码自动视为真实开放。本次测试库显式配置DRAWING，仅用于合成测试；真实开放按业务决定配置。类别为空时草稿仍可保存其他内容。

匿名可从“浏览需求”查看当前有效公开内容，详情链接可刷新重开。需求方在“我的需求”保存、预览并主动选择本次声明后提交；可从“发现老师”选本人有效公开需求邀请。主动公开资料的老师可从公开需求详情投递，双方在“撮合进度”核对当时内容并回应。到期/老师停止公开阻止新增和重发，已有待回应仍可处理；旧轮次与新轮次分开，不自动互选或分享联系方式。运营入口仅对服务端operator_granted=true的已验证账号显示；仅在测试库对合成账号设置该应用标记，未向业务库真实账号授权，也未改变PostgreSQL角色。实际运营账号由用户指定后单独授予，不提供客户端自选或通用权限后台。当前审核结果通过本人进度页刷新读取，不额外发送审核邮件。

## 测试

普通代码测试不需要数据库、Docker或SMTP。已准备额外的PostgreSQL集成测试，默认跳过；只有显式设置以下环境变量才会执行：

~~~powershell
$env:RUN_DATABASE_TESTS = 'true'
$env:TEST_DB_URL = 'jdbc:postgresql://YOUR_TEST_SERVER:5432/linke_test?sslmode=verify-full'
$env:TEST_DB_USERNAME = 'YOUR_TEST_USER'
$env:TEST_DB_PASSWORD = 'YOUR_TEST_PASSWORD'
.\backend\mvnw.ps1 test
~~~

测试只接受库名以_test结尾的专用测试库，不读取普通DB_URL。测试会执行迁移并创建/清理合成账号；常规数据库测试替换邮件传输。当前71项后端测试通过（32项普通检查、39项真实数据库，含12项联系方式用例）；两项真实SMTP测试此前已单独通过，本阶段跳过，不重复发信。首轮70项通过后补充过期邮件令牌拒绝及双字段分享定向验证，重复执行不重复计数。

浏览器联调在frontend运行npm.cmd run test:e2e，需要上述TEST_DB_*环境变量、已启动的测试后端8081、代理到8081的Vite5174，以及本机Chrome。测试后端用DB_URL指向专用测试库、MAIL_ENABLED=false、TEACHER_CATEGORIES=DRAWING、REQUEST_CATEGORIES=DRAWING；Vite用API_PROXY_TARGET=http://127.0.0.1:8081和npm.cmd run dev -- --port 5174启动。Playwright会创建并清理合成账号，只接受_test库；运营与到期夹具也仅操作这些合成数据。44个唯一场景最终通过，桌面1366×900和手机宽度390×844各22项；首轮42项通过，换账号测试补齐退出等待后6项定向复测通过，不重复累计。证据在.tools/stage5-browser/results.json、contact-final-results.json、final-browser-summary.json及双方授权截图；E2E_RESULTS_FILE可指定输出路径。手机宽度仿真不代表真机验证。前端8项、类型检查及生产构建通过，后端可运行JAR打包通过；实际边界见核心验收记录。

两种身份都可在“私密联系方式”保存或删除选填手机号/微信；本轮互选详情中选择本人分享字段、核对实际值并明确同意。只有双方有效同意才展示选中值；撤回停止本关系双方后续平台访问，保留对方独立意愿。已选字段改值或删除后双方须重新确认，旧幂等回执不会恢复旧值。结束核查两库业务数据均0，测试服务8081/5174已停止，本地5173/8080及既有隧道保留。真实类别和运营账号、部署方案需另行落实；服务器配置变更仍须先询问。

原Figma及[只读设计预览](docs/design/interaction-preview.html)继续作为设计参考；当前源码和测试证据与历史模拟演示分开记录。
