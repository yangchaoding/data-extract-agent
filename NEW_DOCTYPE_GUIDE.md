# 新增一种文档类型：以银行流水和会议纪要为例

这份教程带你一步一步给 extract-agent 加一种新的文档类型。用两个例子贯穿全程，它们正好是两个极端：

| | 银行流水 | 会议纪要 |
|---|---|---|
| 内容形态 | 跨页的长表格 | 自由文本 |
| 主要难点 | 行数多、金额多、不能漏行 | 要概括、要判断、人名和事项混在句子里 |
| 用到的能力 | 列表字段、通读全文、金额核对 | 字符串列表、带子字段的列表、允许概括、枚举 |

文中的两份 schema 和对账脚本都用虚构的样例实际跑过，贴出来的输出是真实结果。没有用真实的银行流水和纪要验证过，真实的识别结果会有错字和版式差异，要按第 5 步自己调。

---

## 先说结论：不用改代码

加一种文档类型，要做的是写一个 schema 文件。代码里的读取、切片、定位、抽取、核对对所有文档都一样。

全部步骤：

1. 准备识别结果
2. 看切片，确认读对了
3. 写 schema
4. 试跑
5. 读结果，调 schema
6. 加业务校验（程序核对不了的部分）
7. 做成 Skill（可选）

最后的产出是一个 YAML 文件，流水再加一个对账脚本，想让 Claude Code 一句话调用的话再加一个 Skill 目录。

开始之前先判断一件事：**银行给的如果是 Excel 或 CSV，不需要这套工具**，直接读文件就行。这套工具处理的是扫描件、PDF 经过 PaddleOCR-VL 识别之后的结果。

---

## 第 1 步：准备识别结果

把一份文档的识别结果放进一个目录，每页一个 JSON：

```
ocr_out/
  bank_202401/
    bank_202401_0_res.json
    bank_202401_1_res.json
  minutes_0520/
    minutes_0520_0_res.json
    minutes_0520_1_res.json
```

`ocr_out/` 已经被 git 忽略，放在这里不会被提交。同一份文档的多页文件算一份文档。

---

## 第 2 步：看切片，确认读对了

这一步不调用模型，几秒钟出结果。先看它，是因为模型只能看到切片里的文字，切片里是错的，后面怎么调 schema 都没用。

```bash
extract-agent chunks ocr_out/bank_202401 --show
```

虚构样例（2 页、24 笔交易）的输出：

```
# bank_statement：2 页，14 个版面块，约 963 token，切成 1 片
   c1 |    第1-2页 |   966 token | - [含表格]
```

对照下面的清单检查。

**银行流水要看的：**

| 看什么 | 正常的样子 | 不正常时 |
|---|---|---|
| 跨页的表格是否拼成了一张 | 一张表从第一笔连到最后一笔，中间没有重复的表头 | 每页各成一张表：多半是两页的列数不一样（识别错了），或者中间隔着别的内容 |
| 表头 | 第一行是"交易日期、摘要、借方发生额…"，下面是 `\| --- \|` | 表头行被当成了数据，或者数据行被当成了表头 |
| 单位说明 | "单位：元"紧挨在表格上方 | 被分到了别的片里：金额字段的单位会判断不了 |
| 页眉页脚 | 银行名称、"第 1 页 共 2 页"只出现一次 | 每页重复：干扰不大，可以不管 |

**会议纪要要看的：**

| 看什么 | 正常的样子 |
|---|---|
| 议题标题 | 以 `##` 开头，各自成行 |
| 开头的基本信息 | 时间、地点、主持人、出席各占一行 |
| 正文 | 每个议题的讨论和决定跟在它的标题下面 |

文档超过 6000 token 时会被切成多片。想提前看切开之后的样子，把预算调小：

```bash
extract-agent chunks ocr_out/bank_202401 --chunk-tokens 300 --context-tokens 600 --show
```

样例被切成 5 片，表格按行拆成 4 段，每一段都重复了表头和"单位：元"：

```
   c3 |    第1-2页 |   289 token | - [含表格]
币种：人民币 单位：元
（表格分段 2/4，表头已重复）
| 序号 | 交易日期 | 摘要 | 借方发生额 | 贷方发生额 | 余额 | 对方户名 |
| --- | --- | --- | --- | --- | --- | --- |
| 9 | 2024-01-31 | 利息 |   | 1,042.36 | 972,845.79 |   |
```

---

## 第 3 步：写 schema

### 3.1 先把要抽的东西列成表

每一项回答三个问题：是一个值还是多个值？是什么类型？是照抄原文还是要概括、要判断？

| 你要的东西 | 怎么写 |
|---|---|
| 一段照抄的文字（户名、地点） | 什么都不用写，默认就是 `string` |
| 金额、数量 | `type: number`，**必须写 `unit`**（如 `unit: 元`） |
| 具体的日期 | `type: date` |
| 不是具体日期的时间（"5月底前"、"上午9:00"） | 用 `string`。写成 `date` 会因为"不是日期"而通不过核对 |
| 几个选项里选一个 | `type: enum` 加 `options` |
| 要用自己的话概括 | `string` 加 `verbatim: false` |
| 多个值，每个是一段文字（出席人员） | `type: list`，不写 `fields` |
| 多个值，每个有几栏（交易明细） | `type: list` 加 `fields` |
| 多个值，散在全文各处 | 再加 `exhaustive: true` |

每个字段还可以写：

- `title`：中文名。
- `hints`：原文里可能的叫法。它决定了程序先去哪几片里找，写得越贴近原文越准。
- `description`：口径。同一类东西在文档里出现好几处时，在这里写清楚要哪一个。
- `required: true`：找不到时进入复核清单。

### 3.2 银行流水的 schema

保存为 `examples/schemas/bank_statement.yaml`：

```yaml
name: bank_statement
description: >
  银行账户交易明细（流水）。表头信息（户名、账号、期间、期初余额）在第一页，交易明细是可能跨很多页的表格。
  金额照原文写，不要自己加减。借方发生额是支出，贷方发生额是收入，一行里通常只有其中一栏有数，空着的那一栏写 null。
  "合计"、"承前页"、"过次页"这类汇总行不是交易，不要放进交易明细。
fields:
  - name: account_name
    title: 户名
    required: true
    hints: [户名, 账户名称, 客户名称]

  - name: account_no
    title: 账号
    required: true
    hints: [账号, 账户, 卡号]
    description: 照抄原文的数字，分组书写时中间的空格可以去掉。

  - name: bank_branch
    title: 开户行
    hints: [开户行, 开户机构, 开户网点]

  - name: period_start
    title: 起始日期
    type: date
    hints: [查询期间, 起止日期, 交易期间]
    description: 流水覆盖期间的第一天。

  - name: period_end
    title: 截止日期
    type: date
    hints: [查询期间, 起止日期, 交易期间]
    description: 流水覆盖期间的最后一天，不是打印日期。

  - name: opening_balance
    title: 期初余额
    type: number
    unit: 元
    hints: [期初余额, 上期余额, 承前余额]

  - name: closing_balance
    title: 期末余额
    type: number
    unit: 元
    hints: [期末余额, 账户余额]

  - name: total_debit
    title: 借方发生额合计
    type: number
    unit: 元
    hints: [借方发生额合计, 支出合计, 借方合计]

  - name: total_credit
    title: 贷方发生额合计
    type: number
    unit: 元
    hints: [贷方发生额合计, 收入合计, 贷方合计]

  - name: transactions
    title: 交易明细
    type: list
    exhaustive: true
    hints: [交易日期, 摘要, 借方发生额, 贷方发生额, 余额, 对方户名]
    description: >
      交易明细表里的每一笔交易，一行一项，按原文顺序。evidence 摘抄这一整行。
      内容完全相同的两行是两笔交易，都要列出。
    fields:
      - name: seq
        title: 序号
        description: 这一行的序号或流水号，照抄原文；表里没有这一栏时写 null。
      - name: date
        title: 交易日期
        type: date
        required: true
      - name: summary
        title: 摘要
      - name: debit
        title: 借方发生额
        type: number
        unit: 元
        description: 支出金额；这一栏空着时写 null。
      - name: credit
        title: 贷方发生额
        type: number
        unit: 元
        description: 收入金额；这一栏空着时写 null。
      - name: balance
        title: 余额
        type: number
        unit: 元
        description: 这笔交易之后的账户余额。
      - name: counterparty
        title: 对方户名
        description: 对方户名栏的文字；空着时写 null。
```

这份 schema 里有四处是有意这样写的。

**交易明细设了 `exhaustive: true`。** 默认情况下，一个字段只看最相关的几片。流水的每一片里都有交易，所以要通读全文。代价是每一片都会发给模型，耗时和费用随页数增长。

**每一行必须带上 `seq` 或 `balance`，否则会丢交易。** 这是最容易踩的坑。文档被切成多片时，程序会把不同片里内容相同的条目当成重复的，合并成一条。同一天两笔相同的手续费，如果只抽日期、摘要、金额，分在两片里时就只剩一笔。带上余额或序号之后，两行的内容不再相同，就不会被合并。实测的结果：

```
同一次回复里两条相同的行 -> 2 项
分在两次回复里的两条相同的行 -> 1 项      ← 丢了一笔
分在两次回复里、带余额的两行 -> 2 项
```

**金额字段都写了 `unit: 元`。** 不写单位，程序不会去看表格上方的"单位：元"或"单位：万元"，原文用万元时结果会差一万倍。

**汇总行在 `description` 里明确排除。** "合计""承前页""过次页"长得和交易行一样，不说清楚会被当成交易。

你的流水格式不同时，按下面的方式改 `transactions` 的子字段（这几种写法没有实测过）：

| 你的流水 | 怎么改 |
|---|---|
| 列名是"收入""支出" | 把 `debit`、`credit` 的 `title` 改成"支出""收入" |
| 只有一栏"交易金额"，另有一栏"借贷标志" | 用一个 `amount`（`number`）加一个 `direction`（`enum`，选项为借、贷） |
| 只有一栏金额，用正负号表示收支 | 用一个 `amount`，在 `description` 里写"负数表示支出，照原文保留负号" |
| 有"交易流水号""凭证号" | 加一个子字段，它比序号更适合用来区分各行 |

### 3.3 会议纪要的 schema

保存为 `examples/schemas/meeting_minutes.yaml`：

```yaml
name: meeting_minutes
description: >
  会议纪要。开头是会议的基本信息（时间、地点、主持人、出席人员），后面按议题分段记录讨论情况和决定。
  只抽取纪要里写明的内容。人名照抄原文，不要带上职务；没有写明责任单位或完成时限的，对应的值写 null，不要推断。
fields:
  - name: title
    title: 会议名称
    required: true
    description: 纪要的标题。

  - name: doc_no
    title: 文号
    hints: [文号, 纪要编号, 〔, 号]

  - name: meeting_type
    title: 会议类型
    type: enum
    options: [办公会, 党组会, 专题会, 其他]
    description: 根据标题判断。

  - name: meeting_date
    title: 会议日期
    type: date
    hints: [时间, 会议时间, 召开]
    description: 会议召开的日期，不是印发日期。

  - name: meeting_time
    title: 会议时间
    hints: [时间, 会议时间]
    description: 时间一栏的完整原文，包括星期和几点。

  - name: location
    title: 地点
    hints: [地点, 会议地点]

  - name: chair
    title: 主持人
    hints: [主持, 主持人]
    description: 只写姓名。

  - name: recorder
    title: 记录人
    hints: [记录, 记录人]
    description: 只写姓名。

  - name: attendees
    title: 出席人员
    type: list
    hints: [出席, 参加人员, 参会人员]
    description: 出席人员的姓名，每人一项；不包括列席和请假的人。

  - name: observers
    title: 列席人员
    type: list
    hints: [列席]
    description: 列席人员，每人一项。
    fields:
      - name: name
        title: 姓名
        required: true
      - name: position
        title: 职务
        description: 姓名前面写的职务；没有写时为 null。

  - name: absentees
    title: 请假人员
    type: list
    hints: [请假, 缺席]
    description: 请假或缺席的人，每人一项。
    fields:
      - name: name
        title: 姓名
        required: true
      - name: reason
        title: 原因
        description: 括号里写的请假原因；没有写时为 null。

  - name: topics
    title: 议题
    type: list
    description: 各项议题的标题，照抄原文，不带前面的序号。

  - name: resolutions
    title: 议定事项
    type: list
    exhaustive: true
    hints: [会议决定, 会议要求, 会议同意, 原则同意, 负责, 牵头]
    description: >
      会议作出的每一项决定或工作要求，一件事一项。只列有明确动作的决定，"会议认为""会议指出"这类评价不算。
      evidence 摘抄写明这件事的那一两句原文，要包含责任单位和时限。
    fields:
      - name: topic
        title: 所属议题
        description: 这项决定属于哪个议题，写议题标题里的关键词即可。
        verbatim: false
      - name: content
        title: 决定内容
        required: true
        verbatim: false
        description: 用一句话概括要做什么。
      - name: owner
        title: 责任单位
        description: 负责或牵头的单位、人员，照抄原文。
      - name: deadline
        title: 完成时限
        description: 原文写的时限，照抄，例如"6月15日前"；不要换算成日期。

  - name: summary
    title: 会议概要
    verbatim: false
    description: 用两三句话概括这次会议讨论了什么、定了什么。
```

这份 schema 里要注意的地方：

**日期和时间分成了两个字段。** `meeting_date` 是 `date` 类型，输出 `2024-05-20`，方便入库和排序。`meeting_time` 是字符串，保留"（星期一）上午9:00"。`date` 类型只留日期，时刻会丢。

**完成时限是字符串，不是日期。** 原文写的是"5月底前""6月15日前"，没有年份，也不一定是具体的某一天。写成 `date` 会通不过核对。

**哪些照抄、哪些概括，是逐个字段决定的。** 责任单位和时限照抄，程序能核对它们确实写在原文里。决定内容和会议概要设了 `verbatim: false`，由模型概括。

**人名的粒度靠 `description` 控制。** 原文是"办公室主任孙伟"，写"孙伟"和写"办公室主任孙伟"都能通过核对，因为两者都是原文里的文字。要哪一种，得在说明里写明"只写姓名"。

---

## 第 4 步：试跑

```bash
mkdir -p extract_out
extract-agent run ocr_out/bank_202401 --schema examples/schemas/bank_statement.yaml -o extract_out/bank_202401.json
extract-agent run ocr_out/minutes_0520 --schema examples/schemas/meeting_minutes.yaml -o extract_out/minutes_0520.json
```

两点容易出错：

- 只有一份文档时 `-o` 必须是文件，而且它所在的目录要先建好。把目录传给 `-o` 会报 `Permission denied`。
- 一次要几分钟。样例的实测时间是流水 2 分 33 秒、纪要 2 分 36 秒，各调了 2 次模型。

跑完后屏幕上有一行汇总：

```
bank_statement: 通过 10，未通过核对 0，未找到 0
meeting_minutes: 通过 14，未通过核对 0，未找到 0
```

流水结果里的一笔交易：

```json
{
  "value": {"seq": "1", "date": "2024-01-05", "summary": "货款", "debit": null,
            "credit": 86000, "balance": 1336000, "counterparty": "青岚市远航贸易有限公司"},
  "evidence": "| 1 | 2024-01-05 | 货款 |   | 86,000.00 | 1,336,000.00 | 青岚市远航贸易有限公司 |",
  "pages": "1-2"
}
```

纪要结果里的一条议定事项：

```json
{
  "value": {"topic": "2024年度部门预算调整方案", "content": "将调整方案报市财政局审批",
            "owner": "财务科", "deadline": "5月底前"},
  "evidence": "会议要求，财务科按程序于5月底前将调整方案报市财政局审批。"
}
```

样例里还能看到两个细节：

- 账号原文是 `6222 0212 3456 7890 123`，结果是 `6222021234567890123`。分组书写的号码去掉空格后仍然能通过核对。
- 跨页表格里每一笔交易的 `pages` 都是 `"1-2"`。页码和坐标只精确到版面块，整张表是一个块，所以定位不到某一行在哪一页。

---

## 第 5 步：读结果，调 schema

打开结果文件，先看 `summary.needs_review`，再看状态不是 `ok` 的字段。每个有问题的字段都有 `issues`，里面写着原因。按下表对号入座。

| `issues` 里的话，或者现象 | 原因 | 怎么改 |
|---|---|---|
| `必填字段没有找到` | 原文的叫法和 `hints` 对不上，或者原文确实没有 | 回到第 2 步的切片里找到这个值，把它旁边的文字加进 `hints` |
| 状态是 `missing`，`searched` 是 `3/20` 这样没看全 | 只看了最相关的几片，没找到 | 加 `hints`；或者给这个字段加大 `top_k`；散在全文的列表用 `exhaustive: true` |
| `片段里找不到这一段` | 模型摘抄时改了字 | 一般会自动重问一次。反复出现时，在 `description` 里写明 evidence 摘抄哪一行，摘抄得短一些 |
| `evidence 里没有 … 这个数` | 模型自己做了加减或换算，或者这个值原文里根本没有 | 需要计算的值不要让模型抽。抽原始的数，计算放到第 6 步 |
| `这个数的单位和原文注明的不一样` | 模型写的单位和表头、题注里的对不上 | 确认字段写了 `unit`；回到切片看"单位：…"的说明是否和表格在同一片里 |
| `… 不是日期` | 原文不是一个具体的日期 | 把类型改成 `string` |
| `值 … 不是原文里的文字` | 这个字段需要概括，却按照抄来核对 | 加 `verbatim: false` |
| `… 不在可选项 … 中` | 选项没有覆盖到 | 给 `options` 加一个"其他" |
| `别的片段里还读出了不同的值` | 同一个东西在文档里写了几处，数值不同 | 在 `description` 里写清口径（要哪一页、哪一栏、哪个时点的） |
| `列表可能不完整` | 列表字段找到最后一轮还在延伸 | 加 `exhaustive: true`，或者运行时调大 `--max-rounds` |
| `模型的输出被截断` | 一次要输出的条目太多 | 见下面"长文档"一节 |
| 交易笔数比原文少，但没有任何 `issues` | 内容相同的行被合并了 | 给每一行加一个能区分的子字段（序号、流水号、余额） |
| 把合计行、表头行当成了一项 | 没说清楚哪些行不算 | 在 `description` 里明确排除 |

改完 schema 重跑，直到样例文档的结果让你满意。然后换几份版式不同的文档再试，确认不是只对这一份有效。

### 长文档

流水可能有几十页。有三个参数要配合着调：

| 参数 | 作用 | 流水的建议 |
|---|---|---|
| `--context-tokens`（默认 6000） | 一次调用里放多少原文 | 调小到 2000 左右。一次放得越多，模型一次要输出的行就越多，越容易被截断 |
| 环境变量 `EXTRACT_LLM_MAX_TOKENS` | 模型单次输出的上限 | 调大 |
| `--workers`（默认 4） | 同时发几个请求 | 模型服务扛得住就调大 |

输出被截断时，程序会把任务对半拆开重做，结果仍然是对的，但会多花时间。

样例只有 24 笔，用小预算强制切成 5 片后跑了 6 次模型、2 分 48 秒，结果和不切片时完全一样。更长的流水没有实测过，上面的参数建议是根据代码的行为推出来的，第一次跑长流水时先拿一份试。

---

## 第 6 步：加业务校验

到这里，状态为 `ok` 的值只说明一件事：**这个值确实写在引用的那段原文里**。有两类问题程序查不出来，需要你补上。

### 6.1 银行流水：用余额对账

程序查不出来的是：

- **取错了栏。** 借方和贷方在同一行，把两者取反，值仍然写在那一行里，照样是 `ok`。
- **漏了行。** 少抽一笔不会有任何提示。

流水自带校验关系：上一笔余额减借方加贷方，等于这一笔余额。把下面的脚本保存为 `scripts/check_statement.py`：

```python
"""银行流水抽取结果的对账检查：程序核对不了"取对了哪一栏"和"有没有漏行"，用余额来查。

用法：python check_statement.py 结果.json

检查三件事：
  1. 逐笔：上一笔余额 - 借方 + 贷方 = 这一笔余额
  2. 首尾：期初余额对得上第一笔，最后一笔余额等于期末余额
  3. 合计：各笔借方、贷方之和等于表里写的合计
任何一处对不上都说明有漏行、多行，或者把借方和贷方取反了。
"""

import json
import sys
from decimal import Decimal


def dec(value):
    return Decimal(str(value)) if value is not None else Decimal(0)


def main(path: str) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    data = json.load(open(path, encoding="utf-8"))["data"]
    rows = data.get("transactions") or []
    problems = []

    balance = data.get("opening_balance")
    balance = dec(balance) if balance is not None else None
    for n, row in enumerate(rows, 1):
        debit, credit = dec(row.get("debit")), dec(row.get("credit"))
        if row.get("balance") is None:
            problems.append(f"第 {n} 笔（{row.get('date')} {row.get('summary')}）没有余额，后面的逐笔核对从下一笔重新开始")
            balance = None
            continue
        now = dec(row["balance"])
        if balance is not None and balance - debit + credit != now:
            problems.append(
                f"第 {n} 笔（{row.get('date')} {row.get('summary')}）余额对不上："
                f"上一笔 {balance} - 借 {debit} + 贷 {credit} = {balance - debit + credit}，表里是 {now}"
            )
        balance = now

    closing = data.get("closing_balance")
    if closing is not None and balance is not None and dec(closing) != balance:
        problems.append(f"最后一笔余额 {balance} 不等于期末余额 {dec(closing)}")

    for key, column, label in (("total_debit", "debit", "借方"), ("total_credit", "credit", "贷方")):
        total = data.get(key)
        if total is not None:
            got = sum((dec(r.get(column)) for r in rows), Decimal(0))
            if got != dec(total):
                problems.append(f"{label}发生额合计：各笔相加是 {got}，表里写的是 {dec(total)}")

    print(f"共 {len(rows)} 笔交易")
    if problems:
        print(f"对不上 {len(problems)} 处：")
        for p in problems:
            print("  -", p)
        return 1
    print("逐笔余额、期初期末、借贷合计都对得上")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1]))
```

运行：

```bash
python scripts/check_statement.py extract_out/bank_202401.json
```

正常时的输出：

```
共 24 笔交易
逐笔余额、期初期末、借贷合计都对得上
```

我故意把结果弄坏试了两次，它都准确指出了位置。删掉一笔手续费：

```
共 23 笔交易
对不上 2 处：
  - 第 8 笔（2024-01-31 利息）余额对不上：上一笔 971818.43 - 借 0 + 贷 1042.36 = 972860.79，表里是 972845.79
  - 借方发生额合计：各笔相加是 1742203.49，表里写的是 1742218.49
```

把一笔的借方和贷方取反：

```
共 24 笔交易
对不上 3 处：
  - 第 4 笔（2024-01-15 货款）余额对不上：上一笔 978550 - 借 128000 + 贷 0 = 850550，表里是 1106550
  - 借方发生额合计：各笔相加是 1870218.49，表里写的是 1742218.49
  - 贷方发生额合计：各笔相加是 732134.42，表里写的是 860134.42
```

对不上时，报出来的第一处就是出问题的位置。回到切片里找到那一行，对照原文。也可能是识别本身错了一个数字，这时抽取结果和识别结果一致，但和纸面不一致，要回去看原件。

### 6.2 会议纪要：列出要人看的字段

纪要没有余额这样的校验关系。程序查不出来的是模型的判断：

| 字段 | 程序能保证的 | 程序保证不了的 |
|---|---|---|
| `meeting_type`（枚举） | 引用的原文是真的 | 选得对不对 |
| `summary`、`content`（概括） | 引用的原文是真的 | 概括得准不准、全不全 |
| `resolutions`（列表） | 每一项都有原文依据 | 有没有漏掉一项决定；一件事拆成了几项还是合成了一项 |
| `owner`、`deadline`（照抄） | 确实是原文里的文字，并且出现在引用的那句话里 | — |

所以纪要的校验是人工看一遍，重点是三处：议定事项的条数对不对，每条的责任单位和时限有没有张冠李戴，概括有没有走样。每一条都带着 `evidence`，对照着看很快。

可以自动做的检查有限，例如出席人数和纪要里写的"应到 N 人，实到 N 人"是否一致，议定事项里有没有责任单位为空的。有需要的话仿照 6.1 写一个脚本。

---

## 第 7 步：做成 Skill（可选）

做成 Skill 之后，在 Claude Code 里说一句"把这份流水抽出来"，它就会按固定流程跑抽取、跑对账、汇报。不需要这个便利的话，到第 6 步就已经完成了。

以银行流水为例。

**1. 建目录，把对账脚本放进去：**

```bash
mkdir -p .claude/skills/bank-statement
cp scripts/check_statement.py .claude/skills/bank-statement/
```

**2. 新建 `.claude/skills/bank-statement/SKILL.md`**，内容如下。文件要存成 UTF-8、不带 BOM，第一行必须是 `---`。

````markdown
---
name: bank-statement
description: "从银行流水（账户交易明细、对账单）的 OCR 识别结果中抽取账户信息和逐笔交易，并用余额对账检查有没有漏行或借贷取反。用户要抽取、录入、结构化或核对银行流水、交易明细、对账单时使用。不用于发票、合同等其他文档。"
---

# 银行流水抽取

用 `extract-agent` 把银行流水的识别结果变成 JSON，再用余额对账。抽取和逐字核对由工具完成；你要做的是把它跑起来、跑对账、把对不上的地方准确地告诉用户。

流水含账户和交易对手的信息。结果只写到用户指定的位置或 `extract_out/`，汇报时说结论，不要把交易明细转述一遍。

## 1. 准备

**输入**：PaddleOCR-VL 的识别结果（JSON 或 Markdown 文件，或放着它们的目录）。用户给的是 PDF 或图片时要先识别；给的是 Excel 或 CSV 时不需要这个 skill，直接读文件。

**命令**：PATH 上的 `extract-agent`，没有就用当前目录的 `.venv/Scripts/extract-agent.exe`（Linux / macOS 是 `.venv/bin/extract-agent`）。都没有就问用户装在哪，不要自己安装。

**schema**：`examples/schemas/bank_statement.yaml`。

**模型接口**：流水全文会发给模型。运行前看环境变量 `EXTRACT_LLM_BASE_URL` 和 `OPENAI_BASE_URL`，指向外部服务时先向用户确认。

## 2. 运行

```bash
extract-agent chunks <输入> --show     # 不调用模型：确认跨页的表格拼成了一张
extract-agent run <输入> --schema examples/schemas/bank_statement.yaml -o <输出> --context-tokens 2000
```

- `chunks` 的输出里每份文档有一行 `# <文档名>：N 页…`，据此数清有几份文档。同一份流水的多页文件算一份。
- 只有一份文档时 `-o` 必须是文件，目录要先建好；用户给的是输出目录时写成 `<目录>/<文档名>.json`。有多份文档时 `-o` 是目录。
- 两页的流水约 3 分钟，页数多时成倍增加。超时时间至少给 10 分钟，长流水放到后台跑。

## 3. 对账

```bash
python ${CLAUDE_SKILL_DIR}/check_statement.py <输出>
```

`python` 不在 PATH 上时用 `.venv/Scripts/python.exe`。脚本只用标准库。

`ok` 只保证值写在引用的那一行里，查不出借方贷方取反，也查不出漏行。对账查的就是这两样：逐笔余额、期初期末、借贷合计。

对不上时，报出来的第一处就是出问题的位置。到切片里找到那一行对照：是抽取取错了栏、漏了行，还是识别结果本身和余额对不上（那是识别错了数字，要看原件）。

不要直接改结果文件。把是哪一笔、现在的值、原文那一行告诉用户，由用户决定重跑还是手工更正。

## 4. 汇报

说明通过、未通过核对、未找到三个计数，交易笔数，对账是否通过；对不上时说明是第几笔和原因；结果文件在哪。全部正常时一两句话即可。
````

**3. 检查格式并让它生效：**

```bash
claude plugin validate .claude/skills
```

然后重开 Claude Code，或者在会话里执行 `/reload-skills`。输入 `/` 能看到 `bank-statement` 就是成功了。

**4. 试用：**

```
把 ocr_out/bank_202401 这份银行流水抽出来
```

**会议纪要的 Skill** 照同样的结构写，改三处：

- `name` 改成 `meeting-minutes`，`description` 改成纪要的内容和触发词（会议纪要、会议记录、议定事项）。
- schema 换成 `examples/schemas/meeting_minutes.yaml`，去掉 `--context-tokens 2000`。
- 第 3 节"对账"换成"人工复核清单"，内容用 6.2 的三处重点。

写 Skill 时最要紧的是 `description`：Claude 只凭这一行判断要不要用它。把用户实际会说的词写进去，并写明不用于哪些文档。

这个模板是照着已经试跑过的 `personnel-appointment` 改写的，本身没有单独试跑过。写好之后，找一个没看过它的人或 agent 照着做一遍，卡住的地方就是要补的地方。`personnel-appointment` 第一次试跑时就在 `-o` 的用法上失败了一次，上面的模板已经把那一处写明白了。

想在别的项目里用、或者交给别的 agent，方法见 [SKILL_GUIDE.md](SKILL_GUIDE.md)。

---

## 检查清单

**每加一种文档类型：**

- [ ] 识别结果放进 `ocr_out/<名字>/`
- [ ] `extract-agent chunks … --show` 看过，表格和标题是对的
- [ ] schema 写好：金额都有 `unit`，不是具体日期的用 `string`，要概括的有 `verbatim: false`
- [ ] 列表字段的每一项有能区分的子字段
- [ ] 散在全文的列表设了 `exhaustive: true`
- [ ] 样例跑通，`needs_review` 里的每一项都看过
- [ ] 换两三份版式不同的文档试过
- [ ] 想清楚了程序核对不了什么，并有对应的校验（脚本或人工清单）

**做成 Skill 时再加：**

- [ ] `claude plugin validate` 通过
- [ ] `description` 里有用户会说的词
- [ ] 让没看过它的人或 agent 照着跑过一遍
