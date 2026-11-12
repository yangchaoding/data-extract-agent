# extract-agent

把 PaddleOCR-VL（v1 / v1.5 / v1.6）的识别结果变成结构化数据：自动切片、定位、抽取，并且把模型给出的每个值拿回原文逐字核对。

```
识别结果（JSON / Markdown）
   │  读取：版面块 → 标题层级、表格网格、跨页表格拼接、去掉重复的页眉页脚
   ▼
切片：按章节断开；表格整体保留，大表按行拆并重复表头、题注、单位说明
   ▼
定位：关键词检索（BM25）+ 让模型按目录挑片段
   ▼
抽取：候选片段重合的字段合并到同一次调用；每个值必须附原文摘抄
   ▼
核对：摘抄是不是原文？值能不能从摘抄里读出来？单位换算由程序做
   │  没通过 → 带着具体问题重问；没找到 → 换下一批片段；列表 → 向相邻片段延伸
   ▼
结果：值 + 原文摘抄 + 页码 + 坐标 + 状态（ok / unverified / missing）
```

## 安装

需要 Python 3.10 及以上。

```bash
python -m venv .venv
.venv\Scripts\activate          # Linux / macOS: source .venv/bin/activate
pip install -e ".[dev]"
```

## 配置模型

通过 OpenAI 兼容接口调用模型（国内外大多数服务商和 vLLM、Ollama 等本地部署都提供这种接口）。

开发期间的默认配置直接写在 `src/extract_agent/llm.py` 顶部：

```python
DEFAULT_LLM_BASE_URL = "http://10.10.10.123:9503/v1"
DEFAULT_LLM_MODEL = "cb-llm"  # 使用 /v1/models 返回的 id
DEFAULT_LLM_API_KEY = "EMPTY"  # 无需密钥，使用客户端占位值
```

不设置环境变量即可运行下方的抽取命令。配置优先级为：命令行参数 > `EXTRACT_LLM_*` > `OPENAI_*` > 代码默认值。
接口地址只填到 `/v1`，客户端自动追加 `/chat/completions`；访问上述开发服务时会绕过系统代理。

| 环境变量 | 含义 |
|---|---|
| `EXTRACT_LLM_MODEL` | 模型名，也可以用 `--model` 传 |
| `EXTRACT_LLM_BASE_URL` | 接口地址，也可以用 `--base-url` 传；不设时用 `OPENAI_BASE_URL` |
| `EXTRACT_LLM_API_KEY` | API key；不设时用 `OPENAI_API_KEY`。本地服务可以不设 |
| `EXTRACT_LLM_MAX_TOKENS` | 单次输出的上限，可选。抽长列表时输出被截断就调大它 |
| `EXTRACT_LLM_ENABLE_THINKING` | 要不要让模型先思考再回答，可选。不设时由模型服务自己决定；`false` 关掉思考，`true` 明确打开 |

接口不支持 `response_format` 或自定义 `temperature` 时会自动去掉这两个参数重试；不认思考开关时也一样。

**关于思考开关：** 默认的内网模型是带思考的，抽取时大部分时间和输出花在思考上。在示例任免表上，关掉之后结果相同，耗时和输出的 token 都降到原来的几分之一。这只在一份干净的样例上验证过，所以默认没有关；要关的话，先拿几份真实的文档比一比开和关的结果。它只对认 `chat_template_kwargs.enable_thinking` 的服务有用（vLLM 部署的 Qwen 等）。

## 使用

先看切片结果，不调用模型：

```bash
extract-agent chunks examples/ocr/sample_contract
extract-agent chunks examples/ocr/sample_contract --chunk-tokens 200 --context-tokens 0 --show
```

抽取：

```bash
extract-agent run examples/ocr/sample_contract --schema examples/schemas/contract.yaml -o result.json
```

输入可以是一个文件，也可以是目录。目录里有多份文档的结果时，每份文档输出一个 JSON 到 `-o` 指定的目录（默认 `extract_out`）。

### 支持的输入

| 输入 | 说明 |
|---|---|
| `save_to_json()` 保存的 JSON | 每页一个 `{文件名}_{页码}_res.json`。**推荐**：有页码和坐标，结果能定位到原文位置 |
| 服务化接口的返回 | `result.layoutParsingResults[].prunedResult` |
| `restructure_pages(concatenate_pages=True)` 的结果 | 所有页合成一个结果，块上没有页码，出处只能给到章节 |
| `save_to_markdown()` 保存的 Markdown | 没有坐标；单个文件时没有页码 |

跨页表格不管有没有先经过 `restructure_pages(merge_tables=True)` 都会拼起来；`format_block_content=True` 的结果也能读。

### 常用参数

| 参数 | 默认 | 含义 |
|---|---|---|
| `--chunk-tokens` | 1000 | 每个切片的大小上限 |
| `--context-tokens` | 6000 | 一次调用里片段总量的上限；整篇文档不超过它时不切片 |
| `--top-k` | 5 | 每个字段每一轮看几个片段 |
| `--max-rounds` | 4 | 一个字段最多找几轮 |
| `--no-route` | | 不让模型按目录定位，只用关键词检索 |
| `--workers` | 4 | 并发调用数 |

token 数是按字符估算的（中文一字约一个 token），不是模型分词器的结果。`--context-tokens` 只算片段，提示词里还有字段说明和要求，所以要比模型的上下文窗口留出足够余量。

## Schema

用 YAML 或 JSON 描述要抽的字段。`examples/schemas/` 下有合同、年报、增值税发票、通用文档和干部任免审批表五个示例。

干部任免审批表使用 `examples/schemas/personnel_appointment.yaml`，包含 25 个本人字段，以及家庭成员及主要社会关系列表（称谓、姓名、年龄、政治面貌、工作单位及职务）。年月保留原文，不推算年龄；未提供或留空的字段输出 `null`，未提供家庭成员时输出 `[]`，原文明写的“无”仍保留为字符串。

```yaml
name: contract
description: 采购、服务类合同        # 会告诉模型这是什么文档
fields:
  - name: total_amount              # 输出 JSON 的键
    title: 合同总金额
    type: number                    # string / number / integer / date / boolean / enum / list
    unit: 元                        # 原文是"万元"时由程序换算
    required: true                  # 找不到会进入 needs_review
    description: 合同含税总价        # 口径、怎么判断
    hints: [合同总价, 价税合计]       # 原文里可能的叫法，用来定位

  - name: payment_schedule
    type: list
    fields:                         # 每一项的子字段；不写表示字符串列表
      - {name: stage, title: 期次}
      - {name: amount, type: number, unit: 元}
```

其他属性：

- `options`：`enum` 的可选项。
- `verbatim: false`：这个 string 允许用自己的话概括（默认必须是原文里的文字）。
- `top_k`：这个字段每一轮看几个片段。
- `exhaustive: true`：通读全文找这个字段。用于散落在全文各处的列表；会把每个片段都送给模型，费用随文档长度增长。

同一个指标在文档里出现多处时（年报的摘要、主要会计数据、报表、附注），在 `description` 里写清口径：合并还是母公司、本期还是上期。

要加一种新的文档类型，见 [NEW_DOCTYPE_GUIDE.md](NEW_DOCTYPE_GUIDE.md)：以银行流水和会议纪要为例，从看切片、写 schema、试跑、调整到加业务校验的完整步骤。

## 输出

```json
{
  "document": "sample_contract",
  "schema": "contract",
  "data": { "party_a": "星河智造科技有限公司", "total_amount": 1280000, "sign_date": "2024-03-18", "...": "..." },
  "fields": {
    "total_amount": {
      "status": "ok",
      "value": 1280000,
      "evidence": "含税总价为人民币1,280,000.00元",
      "chunk": "c1",
      "pages": "2",
      "bbox": [120, 210, 1120, 290],
      "section": "第三条 合同价款及支付"
    }
  },
  "summary": { "ok": 13, "unverified": 0, "missing": 0, "needs_review": [], "chunks": 1, "llm_calls": 2 }
}
```

- `data`：只有值，直接入库用。
- `fields`：每个值的出处。`bbox` 是证据所在版面块在该页图像上的 `[x1, y1, x2, y2]`；跨页表格的 `pages` 是 `"2-3"`。列表字段每一项各有自己的出处。
- `status`：
  - `ok`：通过了程序核对。
  - `unverified`：有值但没通过核对，`issues` 里写着原因。值仍然保留，需要人看。
  - `missing`：没找到。`searched` 是"看过的片段数/总片段数"，没看全时不代表文档里没有。
- `summary.needs_review`：需要人看一眼的字段。包括没通过核对的、没找到的必填字段、读出了两个不同值的（见 `alternatives`）、列表可能不完整的。

## 程序核对什么，不核对什么

核对的：

- **摘抄是原文**。忽略空白、标点、全半角、大小写和千分位的差别，除此之外必须逐字相同；改一个字、一个数都不通过。摘抄可以是几段（表头加一行、中间用省略号略过），每一段都要是原文。
- **数字写在摘抄里**。模型照原文写数字和单位，换算成字段的 `unit` 由程序做。单位取自数字后面紧跟的（`5,000万元`）、表头里的（`金额(万元)`）或表格上方的（`单位：万元`）；模型写的单位和原文对不上会被标出来。会计写法的括号负数、中文大写金额都认。
- **日期是摘抄里的某个日期**。年月日必须属于同一个日期，不能从两个日期里各取一部分。
- **照抄型的字符串是原文里的文字**，并且摘抄引的确实是它所在的地方。

不核对的：

- `enum`、`boolean` 和 `verbatim: false` 的字符串是模型的判断，程序只能保证它引的摘抄是真的。
- **取的是不是该取的那个数**。摘抄里有"本期"和"上期"两个数时，程序能确认模型给的数确实在这一行里，但不能确认它取对了列。`ok` 的意思是"值确实出自原文的这个位置"，不是"保证正确"。
- 字段没有设 `unit` 时不会套用表格上方的"单位：万元"。金额字段请设 `unit`。

## 作为库使用

```python
from extract_agent import ExtractionAgent, llm_from_env, load_document, load_schema

doc = load_document("ocr_out/contract_001")          # 多份文档用 load_documents
schema = load_schema("examples/schemas/contract.yaml")
result = ExtractionAgent(llm_from_env()).run(doc, schema)

result.data                    # {字段名: 值}
result.needs_review            # 需要人看的字段
result.fields["total_amount"]  # FieldResult：status、items（值、摘抄、片段、版面块）、issues
```

识别结果已经在内存里（比如刚从服务接口拿到）时用 `document_from_result(data)`。换别的模型客户端，只要实现 `complete(system, user) -> str` 即可。

## Claude Code Skill

干部任免审批表的抽取做成了一个 Claude Code Skill：`.claude/skills/personnel-appointment/`。在这个仓库里打开 Claude Code，说"把 ocr_out/xxx 这份干部任免审批表抽出来"就会用上它，也可以用 `/personnel-appointment` 直接调用。

它固定使用 `examples/schemas/personnel_appointment.yaml`。跑完之后会区分"原文空栏"和"需要复核"，并抽查同一行里容易取反的字段（籍贯和出生地、全日制和在职教育、审批机关意见和行政任免机关意见等）：这几对字段引的是同一行原文，取反了程序核对不出来。

想在别的项目里也能用，把它装到个人目录：

```bash
mkdir -p ~/.claude/skills/personnel-appointment
cp .claude/skills/personnel-appointment/SKILL.md examples/schemas/personnel_appointment.yaml ~/.claude/skills/personnel-appointment/
```

再在 `~/.claude/skills/personnel-appointment/local.md` 里写上本机 extract-agent 的位置（或者把 `extract-agent` 加进 PATH）。改了 `SKILL.md` 或 schema 之后，重新执行上面的 `cp`。

逐步的操作说明，以及怎么把它交给别的 agent，见 [SKILL_GUIDE.md](SKILL_GUIDE.md)。

## Java 实现（进行中）

`agent-platform/` 是正在做的 Java 版（Spring Boot），目标是一个通用 agent 平台，文档抽取是它的第一个能力。方案见 [SPRINGBOOT_SERVICE_DESIGN.md](SPRINGBOOT_SERVICE_DESIGN.md)，进度和用法见 [agent-platform/README.md](agent-platform/README.md)。

这里的 Python 实现是它的参考实现：Java 版照着它移植，并用它的输出做基准来测试。改了读取、表格、文本工具之后，要重新生成基准数据，做法见 agent-platform/README.md 的"测试"一节。

## 测试

```bash
pytest
```

测试不调用真实模型：agent 的流程用按规则回复的假模型测，模型客户端和命令行用本机的一个假服务走真实的 HTTP 请求。

## 目前的状况

- **已用开发接口的 `cb-llm` 模型跑通仓库合同样例**，13 个字段通过程序核对。提示词、切片大小、`top_k` 这些默认值仍没有经过真实业务文档上的效果评估，建议先拿几份有标准答案的文档看一下 `unverified` 和 `missing` 的比例。
- **还没有用真实的 PaddleOCR-VL-1.6 输出验证过**。输入格式是对照 PaddleX 里结果类的源码写的，`examples/ocr/sample_contract` 是按这个格式手写的样例。
- 只处理文字。印章、图表在识别结果里有文字内容时会用上；图片本身不看。
- 标题层级在 JSON 结果里没有，是按编号样式（第一章、一、1.1 …）推断的；编号不规范的文档，章节路径可能不准，但不影响抽取。
