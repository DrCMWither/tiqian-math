# 公式中的宿主文字

公式里的 `\text{...}` 和原始中日韩文字由宿主文字系统排版。数学核心负责 TeX 样式、盒子
位置、基线和公式内部的组合关系，不重新实现宿主的字体 fallback、bidi 或文字 shaping。

## Compose 默认路径

`math-compose` 会自动用当前 `TextStyle` 测量每个文字原子，并把结果作为不可拆分的 TeX
文字盒交给数学布局。盒子包含宽度、ascent、descent、绘制边界和一个不透明的重放 id；布局结果只
移动整个盒子，不读取或重排里面的字形。

绘制时会重放测量阶段得到的同一个 `TextLayoutResult`。因此 Compose 选择的字体、fallback、bidi、
复杂文字 shaping 和字重会原样保留，普通应用不需要提供 `MathTextRunProvider`。

## 精确逐字形路径

需要与外部排版引擎共享字体决策和字形位置时，可以显式提供 `MathTextRunProvider`。这个路径返回
`MeasuredMathRun`，其中包含：

- 映射回原始源码的 cluster；
- glyph id、advance、placement、逻辑度量和绘制边界；
- 每个字形的稳定 face id；
- 字体角色、请求与实际字重、选择原因和替换原因。

每个 face id 必须只有一个重放所有者。数学字体目录与宿主文字目录同时声明同一个 id 时，预检会
报告 `ReplayFaceOwnershipConflict`，不会按目录顺序猜测。

提椠接入使用这条路径，使公式内文字和正文共享字体 fallback、shaping 与度量证据。它仍然不参与
TeX 的脚本样式、基线移动、碰撞约束或公式断行。

## 其他前端

其他平台前端可以选择任一边界：

1. 实现宿主文字盒的测量与不透明重放目录；
2. 实现逐字形的 `MathTextRunProvider` 与对应 face 重放目录。

两种返回结果都会进入同一个 `MathLayoutResult`。前者保存宿主原生文字能力，后者提供更细的字形级
诊断。测量结果无法被同一后端重放时，预检会报告 `NonReplayableHostTextRun`，不会静默换字体或
伪造字形。

`SkiaMathTextRunProvider.fromBytes` 和 `AndroidMathTextRunProvider.fromBytes` 是用于确定性预览和简单
LTR 文字的单字体适配器。它们不负责 Unicode bidi 段落和多字体 fallback；需要这些能力时应使用
平台文字盒或提椠适配器。
