# agent-platform

通用 agent 平台的 Java 实现（Spring Boot）。第一个能力是文档抽取：读取识别结果，按 skill 定义抽取数据，并把每个值拿回原文核对。

整体方案在仓库根目录的 `SPRINGBOOT_SERVICE_DESIGN.md`。仓库根目录下的 Python 实现（`src/extract_agent`）是参考实现，这里的代码是照着它移植的，并用它做基准来测试。

## 现在做到哪了

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | 工程骨架、分层规则测试、出处（坐标）模型 | 已完成 |
| 1 | 读取 PaddleOCR-VL 的识别结果 | 已完成 |
| 2 | 文本比对、检索、切片 | 已完成 |
| 3 | 字段定义、提示词、数值与核对、模型客户端 | 已完成 |
| 4 | 按 skill 定义完成抽取 | 已完成，用真实模型跑过示例任免表；出处的精度还没收尾（见下） |
| 5 | 批量 HTTP 接口、异步任务、文件存储、观测、规则路由 | 已完成（单实例文件存储），使用方法见 [阶段五教程](docs/03-batch-http.md) |
| 6 及以后 | 见方案第 19 节 | 未开始 |

阶段 4 在真实模型上的验证（2026-11-12，内网的 `cb-llm`，示例任免表）：

- **结果和假模型服务跑出来的一致**：通过 22，未通过核对 0，未找到 4，4 次调用。Python 版用同一份表、同一个模型跑出来的值也相同。抽取流程和 Python 版的一致性另外用录播验证过（31 个用例，提示词逐字相同、结果相同）。
- **这个模型默认带思考，一份表要将近 3 分钟。** 关掉思考（`agent.llm.enable-thinking`，见 [docs/02-extract-by-skill.md](docs/02-extract-by-skill.md) 的"抽取很慢"）之后值完全相同，半分钟左右。默认没有关。
- **只验证了这一份虚构的、识别得很干净的样例。** 真实的任免表还没有跑过；关思考对准确率有没有影响，要拿真实的表对比之后才知道。
- 教程里的输出仍然是用假模型服务跑的：不在内网也能照着重现。真实模型的结果在值上相同，不同的是 token 用量、原文摘抄的写法，以及多行的栏（简历、年度考核结果）值里是真正的换行。

阶段 5 提供了这些功能：

- 用 `serve` 启动 HTTP 服务；通过 JSON 或 multipart 上传一份或多份 PaddleOCR-VL 识别结果，提交后立即返回任务 ID。
- 按文档进入有界队列并执行，可轮询、取结果、取消、删除；幂等键防止重复抽取，同时提交同一幂等键也只建一个任务。一份文档失败或未确定 skill，其余文档继续处理。
- 文件、任务状态、结果保存在 `agent.store.dir`，重启后未完成的文档重新排队；已完成的结果和幂等键保留。可按保留天数自动清理。
- `skill: "auto"` 按标题和关键词规则选 skill，结果带候选、得分和依据；无法确定时返回 `unrouted`。
- `/actuator/health` 检查任务存储，`/actuator/metrics` 提供排队数、执行数、模型并发、调用次数、token 和耗时。
- HTTP 结果的 `files` 和 `locator` 填入识别结果文件的 ID、SHA-256 和页码映射，补齐出处的文件层。

阶段 5 已在 2026-11-13 验收：JDK 21 下 `mvn package` 通过，276 项测试全部成功；使用打包后的 JAR 和本机假模型实际跑过教程的演示脚本及 curl 步骤，验证批量部分成功、幂等、multipart、结果溯源、指标和任务清理。测试与演示都不依赖真实模型服务。

**当前部署是单个实例、本机文件存储。** 文件哈希指向提交的 OCR JSON；原始 PDF、扫描件的留存和展示会在后续读取阶段接入。证据位置仍到版面块（`BLOCK`），值本身的精细位置（单元格、字）等待读取器提供，因此 `value_regions` 仍为空。

规则路由只实现方案 9.4 的第一级。模型分类、按调用方授权、场景码和 Skill 中心按后续阶段实施；HTTP 不支持回调，也不接受调用方指定模型地址、skill 目录或并发数。

模型接口（`LlmClient`）在阶段 3 定了，skill 的清单（`skill.yaml`）在阶段 4 定了。工具接口要到阶段 11 才第一次用到，到那时再定，免得现在凭空设计出来以后又改。

已经能用的功能和教程见 [docs/README.md](docs/README.md)。

## 环境

- JDK 21
- Maven 3.6.3 或更高

`mvn` 用的 JDK 由环境变量 `JAVA_HOME` 决定。版本不对时构建会直接报错，并提示需要 JDK 21。

## 构建

先把 `JAVA_HOME` 指向 JDK 21。用 IntelliJ IDEA 下载的 JDK 一般在用户目录的 `.jdks` 下。

Windows 命令提示符：

```
set "JAVA_HOME=C:\Users\你的用户名\.jdks\openjdk-21.0.2"
mvn package
```

PowerShell：

```
$env:JAVA_HOME="C:\Users\你的用户名\.jdks\openjdk-21.0.2"
mvn package
```

Git Bash：

```
export JAVA_HOME="C:/Users/你的用户名/.jdks/openjdk-21.0.2"
mvn package
```

构建会跑全部测试，生成 `target/agent-platform-0.1.0-SNAPSHOT.jar`。只想打包、不跑测试时用 `mvn package -DskipTests`。

**关于依赖下载：** `.mvn/settings.xml` 是只对本项目生效的 Maven 配置，它不设任何镜像，直接从 Maven 中央仓库下载。这样做是因为有的机器在 Maven 的全局配置里设了镜像，镜像失效之后什么都下载不了。公司有内部仓库的话，在这个文件的 `<mirrors>` 里加上。

## 运行

命令行用法：

```
java -jar target/agent-platform-0.1.0-SNAPSHOT.jar inspect <文件或目录>… [--show] [--json]
java -jar target/agent-platform-0.1.0-SNAPSHOT.jar chunks  <文件或目录>… [--chunk-tokens N] [--context-tokens N] [--show]
java -jar target/agent-platform-0.1.0-SNAPSHOT.jar skills
java -jar target/agent-platform-0.1.0-SNAPSHOT.jar extract <文件或目录>… --skill 名称 [-o 输出]
```

前三个不调用模型。`extract` 会把文档的内容发给模型接口，接口地址在 `application.yml` 的 `agent.llm` 下配置，只允许发往登记过的主机。不带参数运行会打印全部选项。

HTTP 服务用法：

```
java -jar target/agent-platform-0.1.0-SNAPSHOT.jar serve
```

默认监听 `http://127.0.0.1:8080`，只接受本机连接；向其他机器开放时必须配置 `AGENT_API_TOKEN`。模型地址、端口、存储、上传上限和并发等配置见 `application.yml`。HTTP 请求不能覆盖模型和存储配置。

详细的步骤和输出的解释：读取见 [docs/01-read-paddleocr-vl.md](docs/01-read-paddleocr-vl.md)，命令行抽取见 [docs/02-extract-by-skill.md](docs/02-extract-by-skill.md)，HTTP 批量抽取、存储和观测见 [docs/03-batch-http.md](docs/03-batch-http.md)。

## 测试

```
mvn test
```

测试包括以下几类：

| 类别 | 测什么 | 在哪 |
|---|---|---|
| 差分测试 | 同样的输入，Java 版的结果和 Python 版逐项相同 | `*GoldenTest`、`TableParserTest` 的前半部分 |
| 录播测试 | 抽取的整条流程：发给模型的每一条提示词和 Python 版逐字相同，调用的次数和顺序相同，最后的结果相同 | `ExtractionParityTest` |
| 单元测试 | Java 版特有的行为：单元格里的换行、标题的识别、跨页表格每一行的来源、出处、skill 的加载、命令行 | `PaddleOcrVlJsonReaderTest`、`TableParserTest`、`DocumentLoaderTest`、`ProvenanceTest`、`SkillLoaderTest`、`ExtractCommandTest` 等 |
| 分层规则 | 包之间的依赖方向没有被破坏 | `LayerRulesTest` |
| 装配 | 整个应用能启动，各层接得上 | `AgentPlatformApplicationTests` |
| HTTP 服务 | 在本机随机端口通过真实 HTTP 调用，检查上传、异步任务、鉴权、异常和观测 | `api/http/*IntegrationTest` |

测试都不调用真实的模型。假模型有三种，在 `src/test/java/.../testsupport` 下：`RuleLlm` 按正则在给出的片段里找答案，`ScriptedLlm` 按写好的脚本回复，`ReplayLlm` 按 Python 版录下来的回复回答。

### 差分测试的基准数据

基准数据由 `tools/make_golden.py` 生成：它用 Python 版处理一批输入，把结果存到 `src/test/resources/golden/` 下，输入存到 `src/test/resources/fixtures/` 下。Java 的测试读同样的输入，结果必须和存下来的相同。

什么时候要重新生成：改了 Python 版的读取、表格、文本工具、抽取流程，或者要加新的用例。在仓库根目录执行：

```
.venv\Scripts\python agent-platform\tools\make_golden.py
```

加用例的办法写在这个脚本开头的说明里。

重新生成之后，`golden/chunks.json` 里个别检索得分的最后一位小数可能和上次不同：Python 里集合的遍历顺序每次运行不一样，浮点数累加的先后也就不一样。测试比较得分时留了余量，不受影响；这种变化可以不提交。

Java 版有四处是故意和 Python 版不一样的，这些地方不做差分测试，由单元测试覆盖：

| 不同之处 | 原因 |
|---|---|
| 表格单元格里的换行保留下来（Python 版压成了空格） | 一格里分行写的内容（简历的各段经历）丢了换行就分不开了 |
| 文档最前面的图题、表题当作文档标题 | 真实的 1.6 输出里，文档标题被版面模型判成了图题 |
| 跨页的表格记着每一行在哪一页、哪个框 | Python 版拼接之后只留第一页的框，第二页的值带着第一页的坐标 |
| 抽取结果里每个值多一项 `locator`，页码和框给的是证据所在的那一页 | Python 版对跨页表格里的值给的都是"第 1-2 页"和第一页的框。录播测试比较结果之前会把这一处按 Python 版的口径还原 |

## 目录结构

```
agent-platform/
  pom.xml
  .mvn/                        只对本项目生效的 Maven 配置
  samples/                     示例数据（虚构的）
  docs/                        使用教程，一个功能一篇
  tools/make_golden.py         生成差分测试的基准数据
  src/main/java/com/cb/agent/
    AgentPlatformApplication   应用入口
    api/cli/                   接入层：命令行
    api/http/                  接入层：HTTP 控制器、请求解析、鉴权、统一异常
    app/document/              应用层：读取文档这个用例
    app/extract/               应用层：抽取编排、异步任务、文件出处
    core/trace/                出处模型：位置、粒度、文字的来源
    core/llm/                  模型接口、用量、全局并发闸门
    core/skill/                skill 清单加载、规则路由
    core/store/                任务存储接口
    capability/document/       文档读取
      model/                   统一文档模型：Document、Block、Table
      reader/                  读取器接口；标题层级的推断；去掉排版标记
        paddle/                PaddleOCR-VL 的 JSON 读取器
      table/                   表格解析：HTML、OTSL、管道文本
      text/                    文本工具：token 估算、归一化
    infra/llm/                 OpenAI 兼容模型客户端
    infra/store/               本机任务文件存储
    config/                    Spring 的装配
  src/test/                    测试、输入（fixtures）、基准数据（golden）
```

## 分层

```
api  →  app  →  core、capability  ←  infra
```

| 包 | 放什么 | 约束 |
|---|---|---|
| `api` | 命令行和 REST 接口 | 只调 `app`，可以用文档模型，不直接调读取器 |
| `app` | 用例的编排 | 不依赖 `api` |
| `core` | 通用 agent 的核心接口 | 不依赖其他任何包，不依赖 Spring |
| `capability` | 能力，每个能力一个包 | 只依赖 `core`，不依赖 Spring |
| `infra` | 模型客户端、文件存储等的实现 | 不被 `core`、`capability` 引用 |
| `config` | 把上面这些装配起来 | |

这些约束由 `LayerRulesTest` 检查。加新代码时如果它挂了，失败信息里会写明是哪个类引用了不该引用的类。

## 约定

- **所有代码都要有注释，用中文。** 公开的类和方法写文档注释；方法内部的注释解释为什么这样做；从 Python 版移植来的地方注明对应的函数。
- **每个功能都要有教程。** 放在 `docs/` 下，写法见 [docs/README.md](docs/README.md)。
- **不可见的字符、长得相似的标点不直接写在源码里**，用 `\uXXXX` 的写法。例如不换行空格、零宽字符、各种横线和引号。直接写在源码里看不出对不对。
