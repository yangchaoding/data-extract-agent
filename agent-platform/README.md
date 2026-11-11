# agent-platform

通用 agent 平台的 Java 实现（Spring Boot）。第一个能力是文档抽取：读取识别结果，按 skill 定义抽取数据，并把每个值拿回原文核对。

整体方案在仓库根目录的 `SPRINGBOOT_SERVICE_DESIGN.md`。仓库根目录下的 Python 实现（`src/extract_agent`）是参考实现，这里的代码是照着它移植的，并用它做基准来测试。

## 现在做到哪了

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | 工程骨架、分层规则测试、出处（坐标）模型 | 已完成 |
| 1 | 读取 PaddleOCR-VL 的识别结果 | 已完成 |
| 2 | 文本比对、检索、切片 | 未开始 |
| 3 | 字段定义、提示词、数值与核对、模型客户端 | 未开始 |
| 4 | 按 skill 定义完成抽取 | 未开始 |
| 5 及以后 | 见方案第 19 节 | 未开始 |

阶段 0 里，模型、工具、skill 这几组接口还没有定。它们要到阶段 3、4 才第一次被用到，到那时再定，免得现在凭空设计出来以后又改。

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

构建会跑全部测试，大约一分钟。生成 `target/agent-platform-0.1.0-SNAPSHOT.jar`。只想打包、不跑测试时用 `mvn package -DskipTests`。

**关于依赖下载：** `.mvn/settings.xml` 是只对本项目生效的 Maven 配置，它不设任何镜像，直接从 Maven 中央仓库下载。这样做是因为有的机器在 Maven 的全局配置里设了镜像，镜像失效之后什么都下载不了。公司有内部仓库的话，在这个文件的 `<mirrors>` 里加上。

## 运行

现在只有命令行这一种用法：

```
java -jar target/agent-platform-0.1.0-SNAPSHOT.jar inspect <文件或目录>… [--show] [--json]
```

详细的步骤和输出的解释见 [docs/01-read-paddleocr-vl.md](docs/01-read-paddleocr-vl.md)。

## 测试

```
mvn test
```

测试分四类：

| 类别 | 测什么 | 在哪 |
|---|---|---|
| 差分测试 | 同样的输入，Java 版的结果和 Python 版逐项相同 | `*GoldenTest`、`TableParserTest` 的前半部分 |
| 单元测试 | Java 版特有的行为：单元格里的换行、标题的识别、跨页表格每一行的来源 | `PaddleOcrVlJsonReaderTest`、`TableParserTest`、`DocumentLoaderTest` |
| 分层规则 | 包之间的依赖方向没有被破坏 | `LayerRulesTest` |
| 装配 | 整个应用能启动，各层接得上 | `AgentPlatformApplicationTests` |

### 差分测试的基准数据

基准数据由 `tools/make_golden.py` 生成：它用 Python 版处理一批输入，把结果存到 `src/test/resources/golden/` 下，输入存到 `src/test/resources/fixtures/` 下。Java 的测试读同样的输入，结果必须和存下来的相同。

什么时候要重新生成：改了 Python 版的读取、表格、文本工具，或者要加新的用例。在仓库根目录执行：

```
.venv\Scripts\python agent-platform\tools\make_golden.py
```

加用例的办法写在这个脚本开头的说明里。

Java 版有三处是故意和 Python 版不一样的，这些地方不做差分测试，由单元测试覆盖：

| 不同之处 | 原因 |
|---|---|
| 表格单元格里的换行保留下来（Python 版压成了空格） | 一格里分行写的内容（简历的各段经历）丢了换行就分不开了 |
| 文档最前面的图题、表题当作文档标题 | 真实的 1.6 输出里，文档标题被版面模型判成了图题 |
| 跨页的表格记着每一行在哪一页、哪个框 | Python 版拼接之后只留第一页的框，第二页的值带着第一页的坐标 |

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
    app/document/              应用层：读取文档这个用例
    core/trace/                出处模型：位置、粒度、文字的来源
    capability/document/       文档读取
      model/                   统一文档模型：Document、Block、Table
      reader/                  读取器接口；标题层级的推断；去掉排版标记
        paddle/                PaddleOCR-VL 的 JSON 读取器
      table/                   表格解析：HTML、OTSL、管道文本
      text/                    文本工具：token 估算、归一化
    config/                    Spring 的装配
  src/test/                    测试、输入（fixtures）、基准数据（golden）
```

## 分层

```
api  →  app  →  core、capability  ←  infra
```

| 包 | 放什么 | 约束 |
|---|---|---|
| `api` | 命令行，以后还有 REST 接口 | 只调 `app`，可以用文档模型，不直接调读取器 |
| `app` | 用例的编排 | 不依赖 `api` |
| `core` | 通用 agent 的核心接口 | 不依赖其他任何包，不依赖 Spring |
| `capability` | 能力，每个能力一个包 | 只依赖 `core`，不依赖 Spring |
| `infra` | 模型客户端、存储等的实现（还没有） | 不被 `core`、`capability` 引用 |
| `config` | 把上面这些装配起来 | |

这些约束由 `LayerRulesTest` 检查。加新代码时如果它挂了，失败信息里会写明是哪个类引用了不该引用的类。

## 约定

- **所有代码都要有注释，用中文。** 公开的类和方法写文档注释；方法内部的注释解释为什么这样做；从 Python 版移植来的地方注明对应的函数。
- **每个功能都要有教程。** 放在 `docs/` 下，写法见 [docs/README.md](docs/README.md)。
- **不可见的字符、长得相似的标点不直接写在源码里**，用 `\uXXXX` 的写法。例如不换行空格、零宽字符、各种横线和引号。直接写在源码里看不出对不对。
