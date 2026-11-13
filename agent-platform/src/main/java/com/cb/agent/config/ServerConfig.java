package com.cb.agent.config;

import com.cb.agent.api.http.ApiGuardFilter;
import com.cb.agent.app.document.DocumentReadService;
import com.cb.agent.app.document.UploadLimits;
import com.cb.agent.app.extract.ExtractService;
import com.cb.agent.app.extract.ExtractionMeter;
import com.cb.agent.app.extract.ExtractionTaskService;
import com.cb.agent.core.llm.LlmGate;
import com.cb.agent.core.store.JobStore;
import com.cb.agent.infra.store.FileJobStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * 作为服务运行时（{@code serve}）才有的装配：任务的存储、全局的模型并发闸门、上传的大小上限、
 * 任务服务、指标、健康检查。各项配置的含义见 application.yml 里的注释。
 *
 * <p>命令行方式运行时这个类整个不生效（{@link ConditionalOnWebApplication}）：执行一条命令不该去读任务的存储，
 * 更不该把上次没做完的任务接着做起来。
 *
 * <p>配置写错了的（并发数是 0、存储目录建不了、模型接口地址不在名单里…）在启动时就报出来，服务不会起来；
 * 报错的样子见 {@link ConfigProblemAnalyzer}。
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ServerConfig {

    private static final Logger log = LoggerFactory.getLogger(ServerConfig.class);

    /** 表单里除了文件之外的文字项（skill、name、几个引擎参数…）留的个数。 */
    private static final int FORM_FIELD_ALLOWANCE = 16;

    /**
     * 任务的存储：本机的一个目录。里面是提交上来的文档和抽取结果，放在哪、谁能读，部署时要按材料的要求定。
     */
    @Bean
    public FileJobStore jobStore(@Value("${agent.store.dir:data/tasks}") String dir) {
        return checked(() -> {
            if (dir.isBlank()) {
                throw new IllegalArgumentException("agent.store.dir 是空的。要写一个目录，任务的文件和结果存在里面");
            }
            try {
                return new FileJobStore(Path.of(dir.strip()));
            } catch (UncheckedIOException e) {
                throw new IllegalArgumentException(e.getMessage() + "（配置 agent.store.dir）。原因：" + e.getCause());
            }
        });
    }

    /** 全局的模型并发闸门：所有任务同时发给模型服务的调用数加起来不超过它。 */
    @Bean
    public LlmGate llmGate(@Value("${agent.llm.max-concurrency:8}") int limit) {
        return checked(() -> {
            if (limit < 1) {
                throw new IllegalArgumentException("agent.llm.max-concurrency 要大于 0，现在是 " + limit);
            }
            return new LlmGate(limit);
        });
    }

    /** 一次请求里能交来多少东西。 */
    @Bean
    public UploadLimits uploadLimits(@Value("${agent.api.max-documents:20}") int maxDocuments,
                                     @Value("${agent.api.max-files:200}") int maxFiles,
                                     @Value("${agent.api.max-file-bytes:8388608}") long maxFileBytes,
                                     @Value("${agent.api.max-request-bytes:33554432}") long maxRequestBytes) {
        return checked(() -> new UploadLimits(maxDocuments, maxFiles, maxFileBytes, maxRequestBytes));
    }

    /**
     * 表单上传时，Tomcat 自己对"一次请求有几个部分"有一个上限（默认 50），比 {@code agent.api.max-files} 小。
     * 不改的话，传 60 个文件会在进到我们的检查之前就被它拒绝，报出来的原因也不是我们写的那一句。
     * 这里把它调到和我们的上限一致，文件个数只由 {@link UploadLimits} 一处来管。
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> uploadPartLimit(UploadLimits limits) {
        return factory -> factory.addConnectorCustomizers(
                connector -> connector.setMaxPartCount(limits.maxFiles() + FORM_FIELD_ALLOWANCE));
    }

    /**
     * 计量：把每份文档的结局记成指标，在 {@code /actuator/metrics} 里看。
     * <pre>
     *   agent.extraction.documents   文档份数，按 skill 和状态分
     *   agent.extraction.duration    一份文档从开始处理到结束用的时间，按 skill 和状态分
     *   agent.extraction.llm.calls   模型调用次数，按 skill 分
     *   agent.extraction.llm.tokens  模型用的 token，按 skill 和方向（type=prompt 输入、completion 输出）分
     * </pre>
     * 还没定下 skill 就结束了的文档（没能确定 skill、排队时被取消），skill 这一项记成 {@code none}。
     * 指标里只有数，没有文档名，更没有内容。
     */
    @Bean
    public ExtractionMeter extractionMeter(MeterRegistry registry) {
        return (skill, status, elapsedMs, usage) -> {
            String name = skill == null ? "none" : skill;
            Counter.builder("agent.extraction.documents").description("处理完的文档份数")
                    .tag("skill", name).tag("status", status).register(registry).increment();
            if (elapsedMs > 0) {
                Timer.builder("agent.extraction.duration").description("一份文档从开始处理到结束用的时间")
                        .tag("skill", name).tag("status", status).register(registry)
                        .record(Duration.ofMillis(elapsedMs));
            }
            Counter.builder("agent.extraction.llm.calls").description("模型调用次数")
                    .tag("skill", name).register(registry).increment(usage.calls());
            Counter.builder("agent.extraction.llm.tokens").description("模型用的 token")
                    .tag("skill", name).tag("type", "prompt").register(registry).increment(usage.promptTokens());
            Counter.builder("agent.extraction.llm.tokens").description("模型用的 token")
                    .tag("skill", name).tag("type", "completion").register(registry)
                    .increment(usage.completionTokens());
        };
    }

    /**
     * 任务服务。建出来之后立刻读进存储里已有的任务，上次没做完的文档重新排队；容器关闭时停下来。
     *
     * <p>另外登记四个随时在变的数（也在 {@code /actuator/metrics} 里）：
     * <pre>
     *   agent.extraction.queued    排队的文档数。一直很高说明处理不过来
     *   agent.extraction.running   正在处理的文档数
     *   agent.llm.inflight         正在进行的模型调用数
     *   agent.llm.waiting          在等名额的模型调用数。一直不是 0 说明并发上限设小了，或者模型服务太慢
     * </pre>
     */
    @Bean(destroyMethod = "close")
    public ExtractionTaskService extractionTaskService(
            ExtractService extraction, DocumentReadService documents, JobStore store, LlmGate gate,
            UploadLimits limits, ExtractionMeter meter, MeterRegistry registry,
            @Value("${agent.tasks.workers:2}") int workers,
            @Value("${agent.tasks.queue-capacity:200}") int queueCapacity,
            @Value("${agent.tasks.retention-days:30}") int retentionDays) {
        ExtractionTaskService service = checked(() -> {
            // 模型接口的配置有问题（没配、地址不在名单里）在这里就报出来，不用等到第一个任务失败。不会真的去连
            extraction.checkModel();
            // skill 目录不存在、某个 skill 的清单写错了，也在这里报出来
            extraction.skills();
            return new ExtractionTaskService(extraction, documents, store, gate, limits,
                    new ExtractionTaskService.Settings(workers, queueCapacity, retentionDays), meter);
        });
        Gauge.builder("agent.extraction.queued", service, ExtractionTaskService::queuedDocuments)
                .description("排队的文档数").register(registry);
        Gauge.builder("agent.extraction.running", service, ExtractionTaskService::runningDocuments)
                .description("正在处理的文档数").register(registry);
        Gauge.builder("agent.llm.inflight", gate, LlmGate::inFlight)
                .description("正在进行的模型调用数").register(registry);
        Gauge.builder("agent.llm.waiting", gate, LlmGate::waiting)
                .description("在等名额的模型调用数").register(registry);
        service.start();
        return service;
    }

    /**
     * 健康检查里"任务"这一项（{@code /actuator/health} 的 {@code components.tasks}）：
     * 存储的目录还在、还能写，就是正常的。细节里是排队和处理中的份数、模型调用的情况、存储所在的盘还剩多少。
     *
     * <p>Spring Boot 自带的磁盘检查看的是程序的当前目录，不一定是存储所在的盘，所以在配置里关掉了，由这一项代替。
     */
    @Bean
    public HealthIndicator tasksHealthIndicator(FileJobStore store, ExtractionTaskService tasks, LlmGate gate) {
        return () -> {
            Path root = store.root();
            boolean usable = Files.isDirectory(root) && Files.isWritable(root);
            return (usable ? Health.up() : Health.down())
                    .withDetail("queued", tasks.queuedDocuments())
                    .withDetail("running", tasks.runningDocuments())
                    .withDetail("llmInFlight", gate.inFlight())
                    .withDetail("llmWaiting", gate.waiting())
                    .withDetail("llmLimit", gate.limit())
                    .withDetail("storeUsable", usable)
                    .withDetail("storeFreeBytes", root.toFile().getUsableSpace())
                    .build();
        };
    }

    /**
     * 启动时的检查和提示。
     *
     * <p>检查：监听的不是本机地址、又没有配置令牌时，拒绝启动。那样的话网络上任何能连到这个端口的人
     * 都可以提交文档、取走结果，而结果里是个人信息。
     *
     * <p>提示：端口起来之后打一行日志，说明服务在哪、用的是哪个 skill 目录、任务存在哪、要不要令牌。
     * 应用的启动日志是关掉的（命令行方式下不需要），所以服务方式下自己打这一行。
     */
    @Bean
    public ApplicationListener<WebServerInitializedEvent> startupNotice(
            ExtractService extraction, FileJobStore store, ApiGuardFilter guard,
            @Value("${server.address:}") String address) {
        boolean local = isLoopback(address);
        if (!local && !guard.tokenRequired()) {
            throw new ConfigProblem("server.address 是 " + (address.isBlank() ? "空的（所有网卡）" : address.strip())
                    + "，别的机器可以连进来，但没有配置令牌（agent.api.token）。",
                    "给 agent.api.token 配一个猜不出来的值（环境变量 AGENT_API_TOKEN），调用时带上 "
                            + "Authorization: Bearer <令牌>；或者把 server.address 改回 127.0.0.1，只让本机调用。");
        }
        return event -> {
            if (event.getApplicationContext().getServerNamespace() != null) {
                return; // 管理端口单独开的时候也会有这个事件，不是我们要报的那个
            }
            String host = address.isBlank() ? "0.0.0.0" : address.strip();
            log.info("服务已启动：http://{}:{}（{}）。skill 目录 {}，任务存在 {}，{}", host,
                    event.getWebServer().getPort(), local ? "只有本机能连" : "别的机器可以连",
                    extraction.skillsDir(), store.root(),
                    guard.tokenRequired() ? "调用要带令牌" : "没有配置令牌，调用不用带");
        };
    }

    /** 监听的是不是本机地址（127.x、::1、localhost）。空的表示所有网卡，不算。 */
    static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(address.strip()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false; // 解析不了的地址：端口也起不来，到时候 Spring 会报
        }
    }

    /** 建一个对象；配置不对时（各处抛出来的都是 {@link IllegalArgumentException}）换成启动时能说清楚的那一种。 */
    private static <T> T checked(Supplier<T> build) {
        try {
            return build.get();
        } catch (IllegalArgumentException e) {
            throw new ConfigProblem(e.getMessage(), "改 application.yml 里对应的那一项，或者用环境变量、"
                    + "命令行参数（--配置项=值）盖住它，然后重新启动。", e);
        }
    }
}
