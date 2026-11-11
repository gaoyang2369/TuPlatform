# 邻课合成测试数据

批次：`linke-demo-20261111-v1`。用户授权后，向既有 `tuplatform` 数据库添加测试样本。脚本不改表结构，不运行迁移，不发送邮件。

| 类别 | 公开需求 | 公开老师 |
| --- | ---: | ---: |
| 课外学习 | 52 | 28 |
| 绘画 | 10 | 6 |
| 舞蹈 | 8 | 6 |
| 其他课外技能 | 10 | 8 |
| 合计 | 80 | 48 |

另外提供8条流程需求：草稿、待审核、驳回、过期各2条。合计88条需求、48份老师资料、128个合成账号（80需求方＋48老师）。老师在此产品中以公开资料承载供给，不另建“老师订单”。全部128份公开内容中，课外学习占62.5%。

覆盖小学、初中、高中、成年及其他阶段；坪山、坑梓、龙田、石井、马峦、碧岭六街道；线上与线下；时间可协商、偏好时段和精确时段；固定与可协商频率；老师区间报价与面议。预算均为每小时的测试参数，不代表调研所得的市场报价。地点显示“深圳市坪山区＋小区名”，不包含楼栋、单元或门牌。

每条需求包含学习现状、目标、预算、单次时长和老师期望；公开老师包含方向、虚构背景、教学方法、服务范围、报价与可用时间。按用户要求，页面标题、昵称、正文及老师介绍均按普通用户内容呈现，不显示“测试”“样本”“模拟设定”等标签。批次标识、确定性ID和保留域邮箱留在数据维护层，用于上线前识别和清理；姓名、背景和经历仍为虚构。

## 使用

本地应用为 <http://127.0.0.1:8180>。刷新“浏览需求”，用需求方登录后进入“发现老师”即可查看。

登录凭据保存在本地被Git忽略的 `.tools/demo-data/credentials.local.json`，包含每个合成账号的独立随机密码、显示名及身份。文件及未完成交接文件在创建前即受Windows目录ACL保护，只允许当前运行用户访问；不要将其提交到仓库。数据库只保存与应用一致的PBKDF2密码散列。

| 用途 | 账号 |
| --- | --- |
| 需求方，课外学习公开需求及一条草稿 | `lk_demo_r001` |
| 需求方，课外学习待审核需求 | `lk_demo_r019` |
| 需求方，课外学习驳回需求 | `lk_demo_r037` |
| 需求方，绘画过期需求 | `lk_demo_r055` |
| 课外学习老师 | `lk_demo_t001` |
| 绘画老师 | `lk_demo_t029` |
| 舞蹈老师 | `lk_demo_t035` |
| 其他课外技能老师 | `lk_demo_t041` |

合成账号使用 `@seed.linke.invalid` 邮箱并直接设为测试可登录状态；这不表示真实邮件验证或本人声明。预置公开需求的提交声明和审核记录仅是合成流程夹具，审核人为空，未冒用真实运营账号。所有合成账号均无运营权限，审核仍使用原有本地运营账号。

未预置投递、邀请、互选、联系方式或双方授权，便于自行测试撮合闭环。

## 工具与验证

从项目根目录执行：

```powershell
# 只读查看目标数据库及样本数量
powershell.exe -NoProfile -File scripts/seed-demo.ps1 -Action inspect
# 单事务试写、验证后回滚，不保存测试密码
powershell.exe -NoProfile -File scripts/seed-demo.ps1 -Action dry-run
# 正式插入；已有完整批次则验证后跳过，不覆盖内容或重置密码
powershell.exe -NoProfile -File scripts/seed-demo.ps1 -Action apply
# 核对账号、老师、需求及提交快照
powershell.exe -NoProfile -File scripts/seed-demo.ps1 -Action verify
# 将本批次原有文案改为自然展示，并同步小区地点（精确原值比对，保留密码、状态和日期）
powershell.exe -NoProfile -File scripts/seed-demo.ps1 -Action refresh-content
# 核对真实应用API的列表、所有样本详情、分页、筛选、状态隔离及双身份登录
python scripts/demo-data/verify-http.py
```

连接复用 `backend/.env` 和进程环境变量，实际库名必须与显式参数匹配。默认目标为 `tuplatform`；如需测试库，调用者必须先准备对应数据库连接，再传 `-DatabaseName tuplatform_test`。不会自动切库、创建角色或打开SSH窗口。

工具先校验已有V1—V5和确定性账号ID，再使用项目当前 `Rules`、`DraftRules`、`SubmissionRules` 校验样本，所有插入在同一事务内。已有账号冲突或部分批次存在时会中止。重复导入不续期；需求按应用规则在提交日期15天后的上海时间零点失效。首次导入到更晚日期前可运行 `python scripts/demo-data/generate.py` 更新期望开始日期；已有批次请勿重新生成后尝试覆盖。

本次内容修正使用 `refresh-content`，比对忽略目录内 `catalog-before-natural-content.json` 的原始内容后，只更新原批次的显示名、老师介绍、需求标题/目标/地点、对应提交快照及两条驳回文案。账号、密码、审核状态、预算、授课方式、时间与到期日均保留；内容版本正常递增。修改前内容保存在 `.tools/demo-data/natural-content-backup.local.json`。若相关文案已被用户独立编辑或需求已产生新版提交，工具中止并回滚，避免覆盖后续操作。已有撮合历史快照保持原样。

需要清理时可明确执行 `scripts/seed-demo.ps1 -Action remove`。清理只匹配本批次的确定性账号ID、账号名和保留域邮箱；如这些账号已产生任何撮合活动或额外需求，工具拒绝清理，避免级联丢失后续测试工作。清理后保留本地凭据文件供核对，因此重新导入前需人工确认并处理原凭据文件。

数据源为 `generate.py` 中逐条编写的情境和老师方向，`catalog.json` 是可检查的完整导入内容。验证结果保存在忽略目录 `.tools/demo-data/http-verification.json`。

2026-11-11执行结果：已在 `tuplatform` 提交本批次128个账号、88条需求、48份公开老师资料；事务试运行完成后已回滚，随后正式提交并校验。真实应用177次HTTP检查通过，含全部80条公开需求及48位老师详情、分页、四类别/六街道/双授课方式筛选、8条非公开状态隔离和双方身份登录。再次执行导入返回 `ALREADY_PRESENT`，未重复插入或更改密码。

## 调研参考

仅参考公开页面所列的课程方向、学段、授课时间和资料维度，再独立编写样本；未采集真实订单或联系人。

- [国家大学生就业服务平台：家教方向与线上、上门授课情境](https://www.ncss.cn/student/jobs/CnCaBnviKYQAsb1nsADR7V/detail.html)
- [国家大学生就业服务平台：学科、乐器、美术、舞蹈等课程方向](https://bift.ncss.cn/student/m/jobs/Pc3veFR8yXWri6yM6uVtUt/detail.html)
- [怡婷艺术：少儿艺术课程及一对一训练场景](https://cdytys.com/col.jsp?id=144)

## 群聊样板与地点修正

后续已读取用户补充的群聊需求文本，参考其中“科目、时间、小区、费用、老师要求”的信息粒度。群内重复转发、中介推广和邀请奖励文案不作为需求内容导入。

| 街道筛选代码 | 使用的小区名 |
| --- | --- |
| `PS_PINGSHAN` | 招商花园城、万科金域缇香 |
| `PS_KENGZI` | 金田风华苑、深业御园 |
| `PS_LONGTIAN` | 聚龙花园、中粮一品澜山 |
| `PS_SHIJING` | 燕澜和鸣花园、天健和悦府 |
| `PS_MALUAN` | 京基御景印象、心海城 |
| `PS_BILING` | 倚湖居花园、汤亨花园 |

这些地点只用作虚构需求的场景，未关联小区真实居民。地址与街道的对应参考以下公开来源：

- [坪山街道服务地图：招商花园、万科金域缇香](https://www.szpsq.gov.cn/zwgk/psqfwdt/ddzxchdg/psjd/)
- [金田风华苑社康：坑梓街道](https://www.szpsq.gov.cn/zwgk/zdlyxxgk/ggqsydw/ylwsjgxxgk/skzxylb/content/post_12183290.html)
- [公共住房通告：深业御园、聚龙花园等项目](https://www.szpsq.gov.cn/pszfbzzx/gkmlpt/content/7/7900/post_7900061.html)
- [公共租赁住房通告：聚龙花园一期位于龙田街道](https://www.szpsq.gov.cn/ztfw/zfbzfw/tzgg/content/post_11751517.html)
- [中粮一品澜山地图地址](https://www.amap.com/place/B02F38IYSV)
- [石井街道通报：燕澜和鸣花园](https://www.szpsq.gov.cn/zwgk/zfxx/content/post_12995775.html)
- [天健和悦府项目位置](https://zjj.sz.gov.cn/ris/bol/szfdc/projectdetail.aspx?id=128559)
- [马峦街道心海城](https://www.szpsq.gov.cn/ztfw/shbz/shfl/lnrfl/jgyljzzzcfw/content/post_12970504.html)
- [碧岭街道服务地图：倚湖居花园、汤亨花园](https://www.szpsq.gov.cn/zwgk/psqfwdt/dqfw/bljd/)
