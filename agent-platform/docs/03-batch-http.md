# 批量 HTTP 抽取、异步任务、存储和观测

## 这个功能是做什么的

业务系统上传 PaddleOCR-VL 的识别结果，服务立即返回任务 ID，在后台按 skill 抽取。调用方通过任务 ID 查进度、取结果、取消或删除，也可以让规则按文档标题和关键词选择 skill。

一份文档的抽取可能需要几分钟，适合用这个异步接口。上传的文件、任务状态和抽取结果会保存在本机目录，服务重启后仍然能查询。这里只接收 OCR 的 JSON；其他格式的读取按方案后续阶段实施。

## 开始之前

- JDK 21、Maven，以及能运行脚本的 Python 3。
- 仓库里的 `personnel-appointment` skill，默认目录为 `../.claude/skills`。
- 示例任免表 `samples/personnel_form_1.6.json`，以及三页合同 `src/test/resources/fixtures/paddle/sample_contract/`。这些材料都是虚构的。
- 一个 OpenAI 兼容的模型接口。本篇用 `tools/demo_model.py` 在本机提供假模型，便于离开内网也能复现。

下面的命令在 `agent-platform/` 目录执行，使用 PowerShell。2026-11-13 已用 JDK 21 实际执行本篇第 1 到 5 步和观测命令；全量构建的 276 项测试全部通过。要先构建：

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\openjdk-21.0.2"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn package
```

JDK 放在其他位置时修改第一行。构建成功后生成 `target/agent-platform-0.1.0-SNAPSHOT.jar`。

## 操作步骤

### 第 1 步：启动假模型

在第一个终端执行：

```powershell
python tools/demo_model.py --port 9599
```

启动时会打印：

```text
假模型服务已启动：http://127.0.0.1:9599/v1 （只认识示例任免表，按 Ctrl+C 停止）
```

它只按正则回答这份任免表，不能说明真实模型的准确率。文档读取、切片、提示词、证据核对、出处、排队和存储都由正式的 Java 代码完成。

### 第 2 步：启动 HTTP 服务

在第二个终端设置好 JDK 21，执行：

```powershell
java -jar target/agent-platform-0.1.0-SNAPSHOT.jar serve --server.address=127.0.0.1 --server.port=8080 --agent.llm.base-url=http://127.0.0.1:9599/v1 --agent.llm.model=demo --agent.store.dir=data/demo-http --agent.api.token=demo-token
```

服务监听 `http://127.0.0.1:8080`，本次演示的文件保存在 `data/demo-http/`。`demo-token` 是演示用的令牌，请求里带 `Authorization: Bearer demo-token`。

使用真实模型时，把 `agent.llm.base-url` 和 `agent.llm.model` 改成部署的配置。模型主机要在 `agent.llm.allowed-hosts` 中，本机地址自动允许。服务启动时检查模型配置，不会先发一条模型请求。

### 第 3 步：运行完整演示

在第三个终端执行：

```powershell
python tools/demo_http.py --base-url http://127.0.0.1:8080 --token demo-token
```

脚本会依次调用下面各个接口，检查回应，最后删除它创建的两个任务。以下为 2026-11-13 在刚启动的服务上实际运行的完整输出；脚本只打印稳定的摘要，任务 ID、时间和完整哈希通过请求检查但不打印：

```text
skills: [{"name":"personnel-appointment","version":1,"extractable":true}]
chunks: [{"name":"personnel_form_1.6","pages":2,"chunks":1}]
route: [{"name":"personnel_form_1.6","skill":"personnel-appointment","method":"rule","score":0.88},{"name":"sample_contract","skill":null,"method":"none","score":null}]
submit: {"http":202,"documents":2}
idempotency: {"http":200,"sameTask":true}
conflict: {"http":409,"code":"conflict"}
batch: {"status":"partial","documents":[{"name":"personnel_form_1.6","status":"succeeded"},{"name":"sample_contract","status":"unrouted"}],"llmCalls":4}
result: {"name":"林致远","ok":22,"unverified":0,"missing":4,"fileHashMatches":true,"filePages":[1,2]}
unrouted-result: {"http":409,"code":"conflict"}
multipart: {"http":202,"status":"succeeded","name":"multipart-form"}
health: {"status":"UP","storeUsable":true}
metrics: {"queued":0.0,"running":0.0,"llmInFlight":0.0,"llmWaiting":0.0,"llmCalls":8.0}
unauthorized: {"http":401,"code":"unauthorized"}
delete: {"tasks":2,"http":204}
```

`llmCalls` 是本次进程启动以来的累计值。上面在刚启动的服务上演示；同一个服务重复运行时它继续增加。

合同被标成 `unrouted`，因为现在只有任免表的 skill。服务没有拿任免表字段去抽合同，也没有为合同调用模型。一份成功、一份未能选出 skill，整个任务为 `partial`。

### 第 4 步：在自己的调用方里提交

演示脚本可以先生成同样的批量请求文件，便于用 curl 或业务系统调试：

```powershell
python tools/demo_http.py --write-request data/demo-request.json
curl.exe -s -H "Authorization: Bearer demo-token" -H "Content-Type: application/json" --data-binary "@data/demo-request.json" http://127.0.0.1:8080/api/v1/extractions
```

新任务回 `202 Accepted`，响应头 `Location` 是查任务的地址，响应体里有 `id`、`status`、`counts` 和 `documents`。把实际返回的 `id` 记下来。

请求的字段如下：

| 字段 | 给法 |
|---|---|
| `skill` | 指定名称，例如 `personnel-appointment`；写 `auto` 按规则选 |
| `documents` | 一组或多组文件。每组是 `{"name": "文档名", "files": [...]}`，`name` 可省略 |
| `documents[].files[]` | `{"name": "p1.json", "content": "文件的base64"}`；也可用 `json` 直接嵌入 OCR 对象或数组，`content` 和 `json` 选一个 |
| `documents[].format` | 可省略，或写 `auto`、`paddle-ocr-vl-json` |
| `options` | 可省略，支持 `chunkTokens`、`contextTokens`、`topK`、`maxRounds`、`route` |
| `idempotencyKey` | 可省略，1 到 200 个字符。同一个键和内容再次提交返回原任务，HTTP 为 200 |

PaddleOCR-VL 每页一个 JSON 时，把同一份文档的文件放在同一组。组内的不同文档会按原始文件名分开；如果给组指定了 `name`，这一组必须只读出一份文档。文件名不用带服务器路径。

`Idempotency-Key` 请求头也能给幂等键。请求头和请求体都给时必须相同。同一个键用于不同的 skill、引擎参数、文档名或文件内容时回 409；任务删除后，这个键可以再次用于新任务。

`options.route` 控制抽取引擎的片段定位；`skill: "auto"` 控制选哪套字段定义。并发数、模型地址和 skill 目录由服务配置，调用方不能在请求中覆盖。

只传一组文件，也可以用 multipart：

```powershell
curl.exe -s -H "Authorization: Bearer demo-token" -F "skill=personnel-appointment" -F "name=my-form" -F "files=@samples/personnel_form_1.6.json" http://127.0.0.1:8080/api/v1/extractions
```

每页一个文件时重复写 `-F "files=@文件路径"`。这几个文件算一组。表单里的引擎参数直接写 `-F "topK=5"`，不放在 `options` 中。

### 第 5 步：轮询、取结果、取消和删除

下面把 `实际返回的任务ID` 换成第 4 步得到的 ID：

```powershell
$taskId = "实际返回的任务ID"
curl.exe -s -H "Authorization: Bearer demo-token" "http://127.0.0.1:8080/api/v1/extractions/$taskId"
```

`queued`、`running` 时等一会儿再查。任务结束后，成功文档的 `documents[].index` 是取结果用的序号，从 0 开始：

```powershell
curl.exe -s -H "Authorization: Bearer demo-token" "http://127.0.0.1:8080/api/v1/extractions/$taskId/documents/0/result" -o data/demo-result.json
```

也可以在查任务时加 `?results=true`，成功文档的 `documents[].result` 会包含结果。日常轮询用默认的不带结果的请求即可。

取消用：

```powershell
curl.exe -s -X POST -H "Authorization: Bearer demo-token" "http://127.0.0.1:8080/api/v1/extractions/$taskId/cancel"
```

排队的文档立刻取消；执行中的文档会被打断，需要稍后再次查状态。取消的响应可能仍为 `running`，这时 `cancelRequested` 为 true。取消前已经成功的文档保留结果。已经结束的任务再取消，结果不变。

任务全部结束后删除：

```powershell
curl.exe -i -X DELETE -H "Authorization: Bearer demo-token" "http://127.0.0.1:8080/api/v1/extractions/$taskId"
```

成功回 204。记录、输入文件和结果都会删除；还没结束的任务回 409，需要先取消并等它停下来。

## 结果怎么看

### 任务和文档的状态

| 状态 | 含义 |
|---|---|
| `queued` | 等待执行 |
| `running` | 正在执行，或一部分已完成，其余仍等待 |
| `succeeded` | 抽取流水线执行成功，有结果 |
| `partial` | 任务里一部分文档成功，其余失败或未能确定 skill |
| `failed` | 执行失败，文档的 `error` 是原因 |
| `unrouted` | 没能确定 skill，`routing.candidates` 和 `reason` 给出依据 |
| `canceled` | 被取消；已经成功的文档仍可取结果 |

任务的 `counts` 分别统计这些文档状态。`usage` 记录 `llmCalls`、`promptTokens`、`completionTokens`；每份结束的文档还有 `elapsedMs`。任务成功表示流水线完成，仍要检查抽取字段。

抽取结果沿用命令行的 `data`、`fields`、`summary`，再增加 `skill`、`routing`、`files`。字段状态的含义见 [02-extract-by-skill.md](02-extract-by-skill.md)：`ok` 表示值逐字来自引用的原文位置；`unverified` 表示核对未通过；`missing` 表示没找到。`summary.needs_review` 里的字段需要复核。

### 规则路由与出处

`routing.method` 为 `explicit` 时是调用方指定，为 `rule` 时是规则选择，为 `none` 时未能确定。`score` 是规则得分，候选里的标题和关键词说明命中了什么。规则得分不是模型置信度。

`files` 列出上传文件的 ID、文件名、大小、SHA-256 和对应文档的页码。每个字段、列表项的 `locator.file_id`、`file_hash` 指向证据所在的文件，`evidence_regions` 指向页和版面块。证据跨多个文件时，文件层指向开头那一页，其余页可按 `files[].pages` 查。

当前的文件是上传的 OCR JSON。PaddleOCR-VL 提供的框细到版面块，`value_regions` 仍为空；原始 PDF、扫描图像以及单元格、字的细坐标按后续阶段接入。

### 存储与重启

本次演示的存储目录是：

```text
data/demo-http/<任务ID>/
  record.json                  任务、幂等键、文档状态和用量
  files/input/<组序号>/...     原样保存的上传文件
  files/result/<文档序号>.json 抽取结果
```

停掉服务后用同样的 `agent.store.dir` 启动，已结束的任务继续可查，未完成的文档重新排队，从头执行该文档。服务在处理同一文档时连续异常退出三次，会把它标成失败，需要确认服务稳定后重新提交。

缩小队列容量后，历史积压仍逐份恢复；恢复时会核对输入的 SHA-256，文件被改过则该文档失败，避免给新内容的抽取结果盖上旧文件哈希。

`agent.tasks.retention-days` 默认 30，已经结束且超过保留期的任务会自动删除。设为 0 不自动清理。此实现用于单个实例；不要让多个进程同时处理同一存储目录。

### 观测

下面两个地址无需 Bearer 令牌：

```powershell
curl.exe -s http://127.0.0.1:8080/actuator/health
curl.exe -s http://127.0.0.1:8080/actuator/metrics/agent.extraction.llm.calls
```

健康检查的 `components.tasks.details` 包括排队数、执行数、模型并发，以及任务存储是否可写、剩余磁盘空间。

| 指标 | 用途 |
|---|---|
| `agent.extraction.queued`、`agent.extraction.running` | 排队与正在处理的文档数 |
| `agent.llm.inflight`、`agent.llm.waiting` | 正在调用模型与等并发名额的调用数 |
| `agent.extraction.documents` | 结束的文档数，按 skill 和状态分 |
| `agent.extraction.duration` | 文档执行耗时，按 skill 和状态分 |
| `agent.extraction.llm.calls` | 模型调用次数，按 skill 分 |
| `agent.extraction.llm.tokens` | token 数，按 skill 和输入、输出分 |

日志记录任务 ID、文档名、状态、用量和耗时。指标在服务重启后重新累计，持久化任务里的用量仍保留。假模型的 token 数是字符数估算；真实模型时用接口回传的 usage。

## 常见问题

**回 401。** 检查 `Authorization: Bearer <令牌>` 与服务的 `agent.api.token` 是否一致。所有 `/api/` 请求都受同一令牌保护，包括取结果和删除。

**向其他机器开放后服务起不来。** `server.address` 不是本机地址时必须配置令牌。把地址改回 `127.0.0.1`，或者设置环境变量 `AGENT_API_TOKEN` 后重启。共享令牌当前只区分能否调用；按调用方授权和场景码属于 Skill 中心阶段。

**回 400。** 请求缺字段、参数错误、上传空文件、JSON 不是 PaddleOCR-VL 输出、skill 不存在，都会当场拒绝。响应格式为 `{"error":{"code":"bad_request","message":"具体原因"}}`，拒绝的提交不留下任务。

**回 413。** 默认最多 20 组文档、200 个文件、单文件 8 MB、文件合计 32 MB。大小由 `agent.api.max-*` 配置；正文还有为 base64 和请求外层预留空间的长度上限。分几次提交，或调整服务配置。

**回 411。** 请求使用了 `Transfer-Encoding: chunked`。接口要求上传时提供 `Content-Length`；常规 curl、脚本里的字节请求会自动给出长度。

**回 503，`error.code` 为 `queue_full`。** 排队容量满了，等 `Retry-After` 指定的秒数再提交。重复请求继续使用原来的幂等键。`agent.tasks.workers` 控制同时处理的文档数，`agent.llm.max-concurrency` 控制整个服务的模型并发。

**任务是 `unrouted`。** 查看候选和依据，选择适合的 skill 明确提交，或给新文档类型添加清单和字段定义。当前不自动调用模型分类，不用通用字段兜底。

**想在任务完成后回调业务系统。** `callback` 还不支持，提交这种字段会回 400。当前用任务 ID 轮询；HTTP 也不接受 `scenario`、`allowUnrouted` 等后续阶段的字段。

**改了 skill 是否要 reload。** 清单和字段定义在使用时从目录读取，新任务会看到修改；同一任务已准备的抽取定义会共用。当前不缓存 registry，因此无需 `/skills/reload` 接口。

**演示超时。** 脚本默认每个任务等 90 秒，可加 `--timeout 300`。先检查服务、假模型是否都在运行；使用真实模型时，耗时取决于模型。演示失败时脚本会尽力取消、删除自己创建的任务，清理失败的任务 ID 打在标准错误里。
