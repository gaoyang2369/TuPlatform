# 页面样例源文件

当前保留三类资料：

| 文件 | 用途 |
| --- | --- |
| scenes.json、navigation.json | 当前首版固定场景、控件及导航，另保留标注延期的历史；画面状态不等于首版路由数 |
| preview-template.html、build-preview.mjs | 生成只读HTML样例 |
| validate-design.mjs | 核对场景引用、生成文件和当前文档链接，不执行真实业务 |

在项目根目录执行：

~~~powershell
node docs/design/source/build-preview.mjs
node docs/design/source/validate-design.mjs
~~~

只在修改预览源后重建。文本框/多行文本只读，复选与下拉控件禁用；导航按钮仅在固定合成状态间跳转。不运行真实登录、输入、保存、授权、到期或清理。延期样例折叠到历史区，主路径不进入延期流程。

旧的一次性Figma构建/收尾/同步脚本已移出当前目录并备份，避免误跑后复活延期流程。Figma以原文件和[节点索引](../figma-state.json)为准；下次改图前加载适用技能、读实际节点，按核心页面定向处理，不自动重建。

旧接口生成与校验来源只保留在[历史快照](../../archive/README.md)，不再从本目录生成整套扩展契约。当前接口工作入口为[首版接口说明](../../contracts/README.md)。
