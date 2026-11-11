# personnel-appointment Skill 操作指南

这个 Skill 让 Claude Code 按固定流程处理干部任免审批表：跑 `extract-agent` 抽取，读结果，抽查容易取错的字段，最后告诉你哪些要人看。本文说明怎么用它，以及怎么把它交给别的 agent。

Skill 本身在 `.claude/skills/personnel-appointment/SKILL.md`，用的字段定义在 `examples/schemas/personnel_appointment.yaml`。

---

## 一、在 Claude Code 里使用

### 第 1 步：确认前提（只需一次）

| 前提 | 怎么确认 |
|---|---|
| 装好了 extract-agent | 仓库根目录下执行 `.venv\Scripts\extract-agent --help` 有输出。没装就按 README 的"安装"一节执行 `pip install -e ".[dev]"` |
| 模型接口能访问 | 默认是 `src/extract_agent/llm.py` 顶部配置的内网服务。换服务时设置 `EXTRACT_LLM_BASE_URL`、`EXTRACT_LLM_MODEL` |
| 有识别结果 | PaddleOCR-VL 输出的 JSON（推荐）或 Markdown。Skill 不做 OCR，PDF 和图片要先识别 |

### 第 2 步：让 Skill 生效

Skill 是这次新建的，已经开着的会话不一定能看到它。

1. 在仓库根目录重新打开 Claude Code（或者在当前会话里执行 `/reload-skills`）。
2. 输入 `/`，列表里能看到 `personnel-appointment` 就说明加载成功。也可以直接问"现在有哪些 skill"。

想在命令行里检查文件格式：

```bash
claude plugin validate .claude/skills
```

### 第 3 步：放好识别结果

把一份表的识别结果放进一个目录，例如 `ocr_out/zhang_san/`，里面是每页一个的 `{文件名}_{页码}_res.json`。`ocr_out/` 已经被 git 忽略，人事材料不会被提交。

### 第 4 步：调用

两种方式都可以。

**直接说要做什么**（Claude 会自己判断用这个 Skill）：

```
把 ocr_out/zhang_san 这份干部任免审批表抽出来
```

**用斜杠命令点名调用**（不想靠它自己判断时）：

```
/personnel-appointment ocr_out/zhang_san
```

可以顺带说明输出位置，例如"结果放到 D:\data\out 下面"。不说的话写到当前目录的 `extract_out/`。

### 第 5 步：等它跑完

过程中 Claude 会依次做这些事，执行命令时可能会请你确认权限：

1. 找到 `extract-agent` 命令和 schema 文件。
2. 检查模型接口指向哪里。指向外部服务时，会先问你能不能把人事材料发过去。
3. 执行 `extract-agent chunks <输入> --show` 看切片，确认读进来的确实是任免表。
4. 执行 `extract-agent run <输入> --schema <schema> -o <输出>` 抽取。一份表 2 到 6 分钟，取决于模型服务的负载。
5. 读结果 JSON，抽查同一行里容易取反的字段。
6. 向你汇报。

### 第 6 步：看汇报和结果文件

汇报里会有每份文档的三个计数、需要你看的字段、哪些字段是原文空栏、结果文件的位置。

结果文件是 JSON，常用的是这三部分：

| 部分 | 内容 | 用途 |
|---|---|---|
| `data` | `{字段: 值}` | 直接入库 |
| `fields` | 每个值的状态、原文摘抄、页码、坐标 | 回查出处 |
| `summary.needs_review` | 工具认为要人看的字段 | 复核清单 |

三种状态的含义：

| 状态 | 含义 | 你要做什么 |
|---|---|---|
| `ok` | 值逐字出自原文 | 一般不用管；Claude 抽查发现取错栏时会单独指出 |
| `missing` | 没有值，`data` 里是 `null` | 多数是原文这一栏本来就空着（拟免职务、专业技术职务经常为空），属于正常 |
| `unverified` | 有值但没通过核对 | 对照原文看一眼，`issues` 里写着原因 |

### 第 7 步：处理需要复核的字段

Claude 发现问题时只会报告，不会直接改结果文件。原因是文件里每个值都带着程序核对过的出处，手改的值没有。你可以让它重跑，也可以明确让它更正某个值，它会在汇报里标明哪些是手工改的。

### 批量处理

把多份表的识别结果放在同一个目录下（文件按各自的原始文件名区分），然后说：

```
把 ocr_out/batch_01 下面的干部任免审批表都抽出来，结果放到 extract_out/batch_01
```

每份表输出一个 `<文档名>.json`。一份失败不影响其余的。份数多时 Claude 会放到后台跑，并且只抽查其中几份，汇报里会说明抽查了哪几份。

---

## 二、在别的项目里使用

项目级的 Skill 只在这个仓库里生效。个人级那份装在 `~/.claude/skills/personnel-appointment/`，在本机任何项目里都能用，里面有三个文件：

| 文件 | 作用 |
|---|---|
| `SKILL.md` | 和仓库里的那份完全相同 |
| `personnel_appointment.yaml` | schema 的副本，不在仓库里时用它 |
| `local.md` | 记着本机 extract-agent 装在哪 |

用法和第一部分相同，只是输入要写完整路径。

换一台机器安装：

```bash
mkdir -p ~/.claude/skills/personnel-appointment
cp .claude/skills/personnel-appointment/SKILL.md examples/schemas/personnel_appointment.yaml ~/.claude/skills/personnel-appointment/
```

然后新建 `~/.claude/skills/personnel-appointment/local.md`，写上那台机器上 extract-agent 的位置；或者把 `extract-agent` 加进 PATH，就不需要 `local.md`。

---

## 三、交给其他 agent 使用

可以。`SKILL.md` 是纯文本的操作说明，不依赖 Claude Code 的私有功能。任何 agent 只要满足下面四个条件就能照着做：

1. 能执行 shell 命令；
2. 能读文件（`SKILL.md`、schema、结果 JSON）；
3. 所在的机器上装了 extract-agent；
4. 能访问抽取用的模型接口。

要交给它的东西有三样：`SKILL.md`、`personnel_appointment.yaml`、extract-agent 的安装位置。

| 交给谁 | 怎么给 |
|---|---|
| 同事的 Claude Code | 把 `.claude/skills/` 提交进 git。对方拉代码后在仓库里打开 Claude Code 就有 |
| Claude Code 的子代理 | 在任务说明里写"先读 `<路径>/SKILL.md` 并照着做"，同时告诉它 Skill 目录在哪 |
| 用 Claude Agent SDK 写的 agent | SDK 从同样的目录加载 Skill，需要在选项里启用文件系统的设置来源并允许 Skill 工具。具体参数名以官方文档为准 |
| 其他支持 Agent Skills 格式的工具 | `SKILL.md` 加 frontmatter 是通用格式，把整个目录放进该工具的 skills 目录 |
| 不认识 Skill 的 agent | 把 `SKILL.md` 的正文作为任务说明或系统提示词的一部分交给它 |

交给 Claude Code 以外的 agent 时要改两处：

- **`${CLAUDE_SKILL_DIR}`**：只有 Claude Code 会把它替换成 Skill 目录的实际路径。交给别的 agent 时，把它改成真实路径，或者在任务说明里写明"Skill 目录是 …"。
- **`local.md`**：里面是这台机器的路径，换机器要重写。

有两种情况不适用：

- **在云端沙箱里运行的 agent**（例如上传到 claude.ai 的 Skill）。沙箱里没有 extract-agent，也访问不到内网的模型接口。
- **只能对话、不能执行命令的 agent**。它跑不了抽取，只能照着说明讲解。

还有一点要考虑：抽查这一步要读表的原文，所以表的内容会进入那个 agent 的上下文，也就是发给它背后的模型服务。人事材料交给外部模型驱动的 agent 之前，先确认这样做是否允许。

---

## 四、修改和维护

| 要改什么 | 改哪里 | 改完之后 |
|---|---|---|
| 字段、口径 | `examples/schemas/personnel_appointment.yaml` | 重新执行第二部分的 `cp`，同步到个人目录 |
| 流程、抽查项、汇报方式 | `.claude/skills/personnel-appointment/SKILL.md` | 同上 |
| 什么时候触发 | `SKILL.md` 开头 `description` 一行 | 同上；然后重开会话或 `/reload-skills` |

`description` 是 Claude 判断"要不要用这个 Skill"的唯一依据。发现它该触发时没触发，就把用户实际会说的词加进去。

---

## 五、常见问题

| 现象 | 原因和处理 |
|---|---|
| 说了"抽取任免表"但 Claude 没用 Skill | 会话是在 Skill 创建之前打开的：重开会话或执行 `/reload-skills`。仍然不行就用 `/personnel-appointment` 点名调用 |
| 提示找不到 `extract-agent` | 不在仓库目录里，PATH 上也没有。在仓库里运行，或在个人目录的 `local.md` 里写上安装位置 |
| `模型接口调用失败` | 模型服务访问不到，或模型名不对。检查网络和 `EXTRACT_LLM_BASE_URL`、`EXTRACT_LLM_MODEL` |
| 写结果时报 `Permission denied` 或找不到目录 | 只有一份文档时 `-o` 必须是文件（如 `out/zhang_san.json`），不能是目录，而且它的上级目录要先存在。同一份表的多页文件算一份文档 |
| `未找到` 的字段很多 | 先看是不是原文空栏。如果切片里那一栏有内容却没抽到，把切片里那一行发给 Claude 让它重跑 |
| 给的是 PDF 或图片 | Skill 不做 OCR，先用 PaddleOCR-VL 识别 |
