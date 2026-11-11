package com.cb.agent;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 分层规则：检查包之间的依赖方向。
 *
 * <p>整个项目是一个模块，编译器不会拦住跨层的引用，所以用测试来守：
 * <pre>
 *   api  →  app  →  core、capability  ←  infra
 * </pre>
 * 哪一条被破坏了，对应的测试就会失败，并列出是哪个类引用了哪个类。
 * 以后真要拆成多个模块时，只要这些规则一直是绿的，拆起来就只是搬目录。
 */
class LayerRulesTest {

    private static final String ROOT = "com.cb.agent";
    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    void core不依赖其他任何包也不依赖Spring() {
        // core 是通用 agent 的核心接口，它不知道"文档"是什么，也不该绑在某个框架上
        noClasses().that().resideInAPackage(ROOT + ".core..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".capability..", ROOT + ".app..", ROOT + ".api..", ROOT + ".infra..", ROOT + ".config..",
                        "org.springframework..")
                .because("core 是所有能力共用的接口，必须能脱离其他部分单独使用")
                .check(classes);
    }

    @Test
    void capability只依赖core不依赖Spring() {
        noClasses().that().resideInAPackage(ROOT + ".capability..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".app..", ROOT + ".api..", ROOT + ".infra..", ROOT + ".config..",
                        "org.springframework..")
                .because("能力要能脱离容器单独测试，也不能反过来依赖调用它的那几层")
                .check(classes);
    }

    @Test
    void 文档读取不依赖别的能力() {
        // 抽取、问答、报告都建在文档读取之上；反过来不行
        noClasses().that().resideInAPackage(ROOT + ".capability.document..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".capability.extract..", ROOT + ".capability.qa..", ROOT + ".capability.report..")
                .because("文档读取是所有能力共用的底座")
                .check(classes);
    }

    @Test
    void app不依赖api() {
        noClasses().that().resideInAPackage(ROOT + ".app..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".api..")
                .because("应用层不该知道请求是从命令行来的还是从 HTTP 来的")
                .check(classes);
    }

    @Test
    void api不直接调用读取器和基础设施() {
        // 接入层可以用文档模型（要把它打印出来），但读文件这件事要通过应用层去做
        noClasses().that().resideInAPackage(ROOT + ".api..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".infra..", ROOT + ".capability.document.reader..", ROOT + ".capability.document.table..")
                .because("接入层只负责接收请求和输出结果，业务逻辑放在应用层")
                .check(classes);
    }
}
