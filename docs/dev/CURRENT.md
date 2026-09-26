# 当前工作

更新时间：2026-09-26（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；HEAD 为 TASK-011 提交（紧随 334e388）；无 remote
当前任务：TASK-011 DONE（独立 Review PASS，已提交）；TASK-012 READY（后端，按用户授权直接开始）
任务内位置：TASK-012 开始前
本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

未提交修改：无（TASK-011 已提交）
当前阻塞：无

下一步具体动作：
1. TASK-012 Incident / Investigation 基础表：读 08 TASK-012；04 §12～§16（incident、incident_affected_resource、investigation）；01 生命周期

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
