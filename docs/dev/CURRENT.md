# 当前工作

更新时间：2026-09-25 22:10 +0800（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前任务：TASK-002 DONE（独立 Review 复核 PASS，已提交）；TASK-003 READY，未开始
任务内位置：TASK-003 清单已向用户汇报，等待确认后开工
本轮允许修改：docs/dev/（TASK-002 收尾）
本轮明确不做：TASK-003 实施

TASK-002 结果摘要（详见 PROGRESS 与 git log）：
- backend：Boot 4.1.1 父 POM、Java 21、5 Module、mvnw 3.9.16（Central＋SHA-256）；系统 mvn 3.6.3，必须用 backend/mvnw
- ai-runtime：uv＋Python 3.13，fastapi 0.141.1/uvicorn 0.54.0；uv.lock 按 pypi.org 生成；本机有 UV_DEFAULT_INDEX 镜像时 `uv sync --locked` 需临时 `env -u UV_DEFAULT_INDEX`
- web：npm，react 19.3.0、vite 8.3.1、typescript 7.0.2
- .gitignore 覆盖 target/.venv/node_modules/dist/.env*/Zone.Identifier
- 未验证：Windows 原生 mvnw.cmd（NOT VERIFIED）

未提交修改：无（TASK-002 已提交）
当前阻塞：无

下一步具体动作：
1. 用户确认 TASK-003 清单后开工：Enforcer（Java 21、Maven 3.9+、依赖收敛、禁止动态版本）＋Spotless；锁定 MyBatis/springdoc/Flyway patch；补 07 §120 红线（见 PROGRESS 待处理问题）

本任务需要读取的规格章节（TASK-003）：08 TASK-003；07 §5、§6～§8、§115～§120、§125、§139

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
