# 本地启动与验收

在Windows项目根目录双击`local.cmd`，或在PowerShell执行：

~~~powershell
.\local.cmd
~~~

首次会构建前端和后端、初始化无邮箱运营账号，随后打开`http://127.0.0.1:8180`。不需要另外启动Vite。已有JDK 21+、Node.js 24.11+、npm；无可用Maven时沿用项目包装脚本下载项目内工具，不改全局配置。

本脚本仅在本机回环地址监听。它沿用`backend/.env`的数据库和邮件私密配置，不在本机安装PostgreSQL。使用本机`15432`端口的SSH隧道时，每次启动都会检查隧道，包括后端已在运行的情况。隧道未连接时，按新窗口的SSH提示登录，并保持窗口打开。启动器等到隧道端口可用后才继续打开网页。若60秒内未连通，完成SSH登录后再运行`local.cmd`。不会修改服务器配置或重启服务器服务。

启动时，Flyway 核对实际连接的数据库，并读取本次打包的待执行迁移。共享库 `tuplatform` 没有待执行迁移时，直接运行 `.\local.cmd`。有待执行迁移时，启动窗口会列出 SQL 文件名。在 `backend/src/main/resources/db/migration/` 核对这些文件，取得该库授权后，指定本次批准的最高版本。例如批准到 V13：

~~~powershell
.\local.cmd -ApproveThrough V13
~~~

如果待执行清单包含 V14，上述命令会在执行任何迁移前退出。旧参数 `-ApplyNotificationMigration` 等价于 `-ApproveThrough V10`。批准只对本次启动有效。确认实际连接到专用 `tuplatform_test` 且指定 `-DatabaseName tuplatform_test` 时，启动器自动应用该库的待执行迁移，再初始化运营账号。

## 运营登录

账号为`linke_operator`，业务库的随机密码保存于项目内的`.tools/local-run/operator-credentials.local`，测试库的新凭据使用同目录的`operator-credentials.tuplatform_test.local`。以启动窗口显示的文件路径为准，仅当前Windows用户可读写。文件不入Git，不要转发截图或上传。账号仅有运营权限，未替本人选择需求方或老师身份，登录直接进入审核页。没有真实邮箱，不发送验证/重置邮件；数据库只保存密码散列。

重复启动不会覆盖运营密码，也不会授予已有同名普通账号权限。忘记密码时明确执行：

~~~powershell
.\local.cmd ResetOperator
~~~

此操作生成新密码、更新私密凭据文件并撤销该运营账号旧会话，不影响其他账号。若提示存在未完成的凭据交接文件，保留`.pending.local`文件并检查，避免丢失唯一密码。

## 状态、停止和端口

~~~powershell
.\local.cmd Status
.\local.cmd Stop
# 默认8180被占用时，可以改用其他空闲端口：
.\local.cmd -Port 8182
~~~

重复启动会在检查数据库隧道后复用已有本地打包应用及其端口。停止命令只处理状态文件中与本项目JAR路径、Java进程名和启动时间均匹配的进程；PID被其他程序复用时拒绝操作。既有5173/8080开发服务和数据库SSH隧道不会被停止。停止后再次启动会重新构建，纳入最新源码。

构建与运行产物位于`.tools/local-run`，与开发用`backend/target`分开；运行日志为`backend.log`和`backend-error.log`。端口被其他程序占用时会报错，不抢占端口或停止其他服务。

## 验收范围

- 现有四个类别在需求、老师两侧全部开启：绘画、舞蹈、其他课外技能、课外学习。
- 普通账号仍经邮箱验证。启动器沿用后端的 `MAIL_ENABLED` 和 SMTP 配置，测试库关闭真实发信。业务通知的本地预览在 `backend.log` 中搜索「本地通知邮件」。邮件按钮使用 `APP_BASE_URL`，未配置时使用本次启动的本机地址和端口。
- 默认使用既有`tuplatform`业务库；验收创建的账号、需求和关系会保留，不自动清理。与旧开发服务使用同一库，关闭旧页面可减少操作混淆。
- 推荐分别使用需求方、老师、运营三个独立浏览器会话，按主验收清单走投递与邀请、互选、授权、撤回及字段变更后双方重确认。运营账号不承担供需双方角色，不能审核本人提交。
- 本地HTTP的会话Cookie仅为本地运行设置，不是公网部署配置。邮件链接指向本机地址；不能用手机上的127.0.0.1访问这台电脑。本次不开放局域网入口或部署服务器。

技术验证可显式设置测试库环境并使用`scripts/local.ps1 -DatabaseName tuplatform_test`，此时关闭真实邮件；该参数不会替调用者改写数据库连接，连接的实际库名必须一致。日常验收直接运行`local.cmd`，无需此参数。
