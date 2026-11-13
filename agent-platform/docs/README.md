# 使用教程

每个功能一篇教程，结构相同：这个功能是做什么的、开始之前、操作步骤、结果怎么看、常见问题。教程里的命令都实际执行过，贴出来的是真实的输出。

## 功能一览

| 功能 | 状态 | 教程 |
|---|---|---|
| 读取 PaddleOCR-VL 的识别结果（一份或多份 JSON） | 已完成 | [01-read-paddleocr-vl.md](01-read-paddleocr-vl.md) |
| 切片预览 | 已完成，命令行和 HTTP 都能预览 | [命令行](02-extract-by-skill.md)、[HTTP](03-batch-http.md) |
| 按 skill 定义抽取数据（例如干部任免审批表） | 已完成。用真实模型跑过示例任免表，结果和教程里的一致；教程里的输出是用假模型服务跑的，不在内网也能重现 | [02-extract-by-skill.md](02-extract-by-skill.md) |
| 批量接口（HTTP）、异步任务、文件存储、观测 | 已完成并通过 HTTP 验收，单实例运行 | [03-batch-http.md](03-batch-http.md) |
| 按标题和关键词自动选 skill（规则路由） | 已完成，选不出时返回候选与依据 | [03-batch-http.md](03-batch-http.md) |
| 读取 txt、Xiaomi-OCR 的输出 | 未开始 | |
| 读取 Word、Excel、文字版 PDF、OFD | 未开始 | |
| 扫描件和图片（调用 OCR） | 未开始 | |
| 在原件上定位（溯源的展示） | 未开始 | |
| 通用 agent（对话） | 未开始 | |
| 文档问答 | 未开始 | |
| 生成报告 | 未开始 | |
| Skill 中心 | 未开始 | |

各阶段的内容和顺序见仓库根目录的 `SPRINGBOOT_SERVICE_DESIGN.md` 第 19 节。

## 从哪开始

第一次用，按这个顺序：

1. 看 [../README.md](../README.md) 的"构建"一节，把项目构建出来。
2. 照着 [01-read-paddleocr-vl.md](01-read-paddleocr-vl.md) 用自带的示例数据走一遍。
3. 照着 [02-extract-by-skill.md](02-extract-by-skill.md) 把示例任免表抽一遍。手边没有模型服务也能走通，教程里有说明。
4. 照着 [03-batch-http.md](03-batch-http.md) 启动服务，通过 HTTP 提交任免表与合同，查看路由、任务和结果。
5. 换成你自己的识别结果再走一遍。

## 写教程的规矩

- 每个功能做完时，教程和代码在同一次提交里。
- 每一步都要实际执行过，输出照实贴，不凭印象写。
- 功能改了，教程跟着改。
- 示例数据用虚构的，不放真实的人事、财务材料。
