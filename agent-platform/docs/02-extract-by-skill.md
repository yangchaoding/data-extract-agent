# 按 skill 的定义抽取数据（干部任免审批表）

## 这个功能是做什么的

把 OCR 识别结果变成结构化数据：`extract` 命令按一个 skill 的字段定义，把一份文档里的值抽出来，每个值附上**原文摘抄**和**在原件上的位置**，并标明有没有通过程序的核对。

这一篇用仓库里现成的 `personnel-appointment`（干部任免审批表）这个 skill 走一遍。换成别的 skill 时命令完全一样，只换 `--skill` 后面的名字。

什么时候该用：手上有 PaddleOCR-VL 的识别结果（JSON 或放着 JSON 的目录），要按固定的字段清单把内容抽出来入库或复核。

什么时候不该用：

- 手上是 PDF、图片、Word——先要识别或读取，见 [01-read-paddleocr-vl.md](01-read-paddleocr-vl.md)；
- 只是想把识别结果看一遍、确认读对了——用 `inspect` 和 `chunks`，不调用模型，几秒钟出结果；
- 字段清单还没定——先写一份字段定义（[examples/schemas](../../examples/schemas/) 里有几份可以照着改），用 `--schema` 直接试。

## 开始之前

- JDK 21，构建过一遍（见 [01-read-paddleocr-vl.md](01-read-paddleocr-vl.md) 的"第 1 步：构建"）；
- **一个模型接口**。文档的全文会发给它，所以要看清楚发到哪去了：
  - 默认用 `application.yml` 里配的内网服务（`agent.llm.base-url`，默认 `http://10.10.10.123:9503/v1`）；
  - 用 `--base-url` 临时换一个，或者用环境变量 `EXTRACT_LLM_BASE_URL`、`OPENAI_BASE_URL`；
  - 接口地址的主机要在 `agent.llm.allowed-hosts` 名单里（本机地址总是允许）。不在名单里时程序会拒绝调用并告诉你加什么。
- 示例数据：`samples/personnel_form_1.6.json`（虚构的一份两页任免表，PaddleOCR-VL 1.6 的输出）；
- skill 在仓库的 `.claude/skills/` 下，`application.yml` 里默认就指向它（`agent.skills.dir`）。

**手边没有模型服务时**，可以用仓库里的假模型服务把流程走一遍（它只认识这份示例表）：

```bash
python agent-platform/tools/demo_model.py     # 另开一个窗口，按 Ctrl+C 停止
```

它只按正则回话，不能说明真实模型的效果，但读取、切片、拼提示词、核对证据、定位出处、汇总结果这些走的都是正式的代码。下面的操作步骤就是用它跑的，每一步的输出都是实际执行的结果。

## 操作步骤

### 第 1 步：看有哪些 skill

```bash
java -jar target/agent-platform-0.1.0-SNAPSHOT.jar skills
```

```
personnel-appointment  干部任免审批表（第 1 版），可以用来抽取
    干部任免审批表（也叫任免表、干部任免表）：一到两页的大表，栏目有姓名、籍贯、出生年月、现任职务、拟任职务、拟免职务、简历、家庭成员及主要社会关系。不是合同、发票、年报。
```

每个 skill 是一个目录，里面有 `skill.yaml`（清单：名称、描述、字段定义在哪、引擎参数）和 `SKILL.md`（做法说明）。这一篇用的是 `.claude/skills/personnel-appointment/`，字段定义就是仓库里那份 `examples/schemas/personnel_appointment.yaml`。

### 第 2 步：先确认读进来的是这份表

```bash
java -jar target/agent-platform-0.1.0-SNAPSHOT.jar chunks samples/personnel_form_1.6.json --show
```

```
# personnel_form_1.6：2 页，3 个版面块，约 1003 token，切成 1 片
   c1 |    第1-2页 |  1005 token | - [含表格]
# 干部任免审批表

| 姓名 | 林致远 | 性别 | 男 | 出生年月（岁） | 1975.03（49岁） | 照片 |
...
```

这一步不调用模型。确认三件事：文档名对不对、内容完不完整、切成几片。任免表大约 1000 token，整份放进一次调用，通常是 1 片；`[含表格]` 说明这张表被认成了表格。

### 第 3 步：抽取

```bash
java -jar target/agent-platform-0.1.0-SNAPSHOT.jar extract samples/personnel_form_1.6.json \
    --skill personnel-appointment -o extract_out/personnel_form_1.6.json \
    --base-url http://127.0.0.1:9599/v1
```

- `-o` 是结果写到哪。只有一份文档时是一个文件名（上一级目录不存在会自动建），多份文档时是一个目录；
- 不给 `-o` 就打一份 JSON 到标准输出；
- `--base-url` 这一行是给假模型服务用的。用真实的模型服务时去掉它。

标准错误输出里有每个文档一行的概况：

```
personnel_form_1.6: 通过 22，未通过核对 0，未找到 4
```

假模型服务那个窗口里每收到一次调用会打印一行。这次是 4 次调用：一共 26 个字段，整份表是一片，一次调用最多抽 8 个字段，所以分成 8、8、8、2 四批。

### 第 4 步：看结果

打开 `extract_out/personnel_form_1.6.json`。骨架是这样的（值照实贴，中间省略了一部分字段，坐标只留了四位小数）：

```json
{
  "document": "personnel_form_1.6",
  "schema": "personnel_appointment",
  "data": {
    "姓名": "林致远",
    "出生年月（岁）": "1975.03（49岁）",
    "拟免职务": null,
    "家庭成员及主要社会关系": [ { "称谓": "妻子", "姓名": "苏婉清", "年龄": "47", "政治面貌": "中共党员", "工作单位及职务": "青岚市第二中学教师" }, … ]
  },
  "fields": {
    "姓名": {
      "status": "ok",
      "value": "林致远",
      "evidence": "| 姓名 | 林致远 | 性别",
      "chunk": "c1",
      "pages": "1",
      "bbox": [104, 257, 1083, 1453],
      "locator": {
        "origin": "OCR",
        "evidence_regions": [ { "page": 1, "box": [0.0873, 0.1526, 0.9093, 0.8628], "granularity": "BLOCK" } ],
        "value_regions": []
      }
    },
    "呈报单位": { "status": "ok", "value": "中共青岚市委组织部", "pages": "2", "…": "…" }
  },
  "summary": { "ok": 22, "unverified": 0, "missing": 4, "needs_review": [], "chunks": 1, "llm_calls": 4, "prompt_tokens": 11508, "completion_tokens": 2763 }
}
```

### 第 5 步：批量

输入可以给多个，每一项是一个文件，或者一个目录（里面每页一个 `{文件名}_{页码}_res.json`，同一份文档的几页算一份）。下面把示例任免表和测试用的合同样例一起交给它：

```bash
java -jar target/agent-platform-0.1.0-SNAPSHOT.jar extract samples src/test/resources/fixtures/paddle/sample_contract \
    --skill personnel-appointment -o extract_out --base-url http://127.0.0.1:9599/v1
```

```
personnel_form_1.6: 通过 22，未通过核对 0，未找到 4
sample_contract: 通过 0，未通过核对 0，未找到 26，需要复核：姓名
```

多份文档时 `-o` 是目录（不给就是 `extract_out`），每份写一个 `<文档名>.json`，这里是 `personnel_form_1.6.json` 和 `sample_contract.json`。

第二行就是"拿错了 skill"的样子：合同不是任免表，26 个字段一个都找不到，必填的 `姓名` 进了复核清单。一份文档失败（比如模型调用出错）不影响其余的，标准错误里会写 `文档名: 失败 - 原因`，最后的退出码是 1。

## 结果怎么看

### `data` 和 `fields`

`data` 只有值，直接入库用。`fields` 是同一个值的完整记录：状态、值、原文摘抄、出处。**要看哪个值可不可信，看 `fields`，不要只看 `data`。**

### 三种状态

| status | 含义 | 怎么处理 |
|---|---|---|
| `ok` | 值逐字出自原文的这个位置 | 还要抽查，见下 |
| `unverified` | 有值，但没通过程序的核对，`issues` 里写着原因 | 对照 `evidence` 和原文，判断是值错了还是摘抄得不够逐字 |
| `missing` | 没找到，`data` 里是 `null` | 看 `searched`：`1/1` 说明全文都看过了。任免表里空栏很常见（拟免职务、专业技术职务经常空着），属于正常 |

`summary.needs_review` 是程序认为要人看一眼的字段：没通过核对的、必填却没找到的、过程中出过状况的（比如输出被截断、列表没收敛）。**这个清单是空的，不代表值都对**，见下面的"核对的能力边界"。

`ok` 的意思被刻意收窄成"值确实写在引用的那段原文里"，不是"保证取对了"。同一行里有几栏时（籍贯和出生地在一行、现任和拟任在一行），程序能确认值在这一行里，确认不了取的是哪一栏。所以 `personnel-appointment` 的 SKILL.md 第 4 节列了必须抽查的几处，照着看。

### 出处（`pages`、`bbox`、`locator`）

- `pages`、`bbox` 是给人看的：页码和版面框（像素）。这份表从第 1 页跨到第 2 页，`姓名` 在第 1 页、`呈报单位` 在第 2 页，各自给各自的页和那一页上那一段表格的框。列表字段的每一项在 `items` 里各有自己的出处。
- `locator` 给程序用：`origin` 说明文字是怎么来的（`OCR` 是识别出来的，`TEXT_LAYER`、`NATIVE` 是读出来的），`evidence_regions` 是摘抄在原件上的位置（相对比例，0 到 1，原点在左上角），`granularity` 说明这个框有多准。
- `granularity` 现在都是 `BLOCK`：PaddleOCR-VL 只给到版面块的框，整张表只有一个框。细到行、单元格的坐标要等读取器提供，`value_regions` 因此是空的。

### 抽查

照着 `.claude/skills/personnel-appointment/SKILL.md` 第 4 节做。要点：同一行里成对的字段（籍贯/出生地、全日制/在职教育、现任/拟任/拟免职务、审批机关意见/行政任免机关意见）要对着 `evidence` 看一眼取反了没有；家庭成员的项数要等于切片里填了内容的行数（表头行和空行不算）。

### 文件在哪里、要不要提交

任免表是人事材料，结果文件里有本人和家属的个人信息，只写到指定的位置，不要提交进仓库。`agent-platform/extract_out/` 和仓库根目录的 `extract_out/` 已经在 `.gitignore` 里；写到别的目录时自己留意。

## 常见问题

**连不上模型接口**

```
模型接口调用失败：连不上模型接口 http://10.10.10.123:9503/v1/chat/completions：java.net.http.HttpConnectTimeoutException: HTTP connect timed out
```

先确认机器能不能连到那个服务（`curl http://10.10.10.123:9503/v1/models`），不在内网时用 `--base-url` 指向别处。

**接口地址不在允许的名单里**

```
错误：模型接口 http://example.com/v1 的主机 example.com 不在允许的名单里。抽取时文档的内容会发给这个接口，所以只允许发往登记过的地址。确认可以发给它之后，把 example.com 加进配置 agent.llm.allowed-hosts（或者环境变量 EXTRACT_LLM_ALLOWED_HOSTS，多个用逗号隔开）。现在允许的是：[10.10.10.123]
```

这是有意拦住的：程序在连接之前就拒绝了，文档没有发出去。确认那个服务可以接收这类材料之后，再把它加进名单。

**skill 的名字写错了**

```
错误：没有名为 invoice 的 skill。现有的：personnel-appointment
```

**`missing` 有 4 个，是不是漏了**

这份示例表里 `专业技术职务`、`拟免职务` 是空栏，`审批机关意见`、`行政任免机关意见` 表里根本没有，所以是 4 个 `missing`。看 `searched` 是 `1/1` 就说明全文都找过了。

**字段全 `missing`，一个都没找到**

多半是 skill 用错了（拿任免表的 skill 去抽合同），或者模型接口回的东西不是 JSON。看 `fields.<名字>.issues` 里的原话。

**抽取很慢，一份表要两三分钟**

多半是模型在思考。默认的内网模型是带思考的，每次调用先想一大段再给结果，时间和输出的 token 大部分花在这上面。关掉思考：

```bash
EXTRACT_LLM_ENABLE_THINKING=false java -jar target/agent-platform-0.1.0-SNAPSHOT.jar extract samples/personnel_form_1.6.json \
    --skill personnel-appointment -o extract_out/personnel_form_1.6.json
```

上面是 Git Bash 的写法。cmd 里先执行 `set EXTRACT_LLM_ENABLE_THINKING=false`，PowerShell 里先执行 `$env:EXTRACT_LLM_ENABLE_THINKING="false"`。要长期生效，改 `application.yml` 里的 `agent.llm.enable-thinking`。

- 不设（默认）：请求里不带这个参数，由模型服务自己决定；`false` 关掉；`true` 明确打开。
- 在示例任免表上，关掉之后抽出来的值完全相同，输出的 token 从一万五降到两千多，整条命令从将近 3 分钟降到半分钟左右。
- **这只在一份干净的样例上验证过，所以默认没有关。** 真实的表识别有噪声，思考对"同一行取错栏"有没有帮助还不知道。关之前拿几份真实的表各跑一遍，对比两次的 `data` 和 `needs_review`。
- 它只对认 `chat_template_kwargs.enable_thinking` 的服务有用（vLLM 部署的 Qwen 等）。服务不认这个参数、直接返回 400 时，程序会去掉它重试，不会因此失败。

**输出被截断**

`issues` 里出现"模型的输出被截断，结果可能不全（调大 EXTRACT_LLM_MAX_TOKENS）"。程序会把这次的任务对半拆开重做（先少抽几个字段，再少看几个片段）；拆到最小还是放不下就记下来。处理办法是调大 `EXTRACT_LLM_MAX_TOKENS`。

**值里出现 `\n` 两个字符，不是换行**

表格里一格分几行写的内容（简历、年度考核结果），片段里就是写成 `\n` 两个字符的。模型照抄下来的话，值里也是字面的反斜杠加 n（假模型服务就是这样）；也有的模型会把它写成真正的换行（内网的 `cb-llm` 实测是这样）。两种写法都能通过核对。读结果时要统一的话，把 `\n` 两个字符换成换行即可。

**同一个值在两个字段里**

任免表同一行有好几栏，模型有可能取错栏。程序核对不出来，靠抽查。发现问题不要直接改结果文件——文件里的值都带着程序核对过的出处，手改的值没有；把字段、现在的值、原文那一行告诉用户，由用户决定重跑还是手工更正。

**想换一份字段定义试试**

```bash
java -jar target/agent-platform-0.1.0-SNAPSHOT.jar extract samples/personnel_form_1.6.json \
    --schema ../examples/schemas/personnel_appointment.yaml -o extract_out/form.json --base-url http://127.0.0.1:9599/v1
```

`--schema` 和 `--skill` 二选一。字段定义可以直接改仓库里那份（改完 `extract` 立刻生效），也可以复制一份改。

**引擎参数**

`--top-k`（每个字段每一轮看几个片段）、`--max-rounds`（一个字段最多找几轮）、`--no-route`（不让模型按目录定位，只用关键词检索）、`--workers`（同时进行的模型调用数）、`--chunk-tokens`、`--context-tokens`。不给就用 skill 清单里写的，再不给就用默认值。字段定义里可以按字段单独设 `top_k` 和 `exhaustive`。
