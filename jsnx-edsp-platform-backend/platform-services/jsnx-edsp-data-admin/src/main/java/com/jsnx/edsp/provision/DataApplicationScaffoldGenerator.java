package com.jsnx.edsp.provision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsnx.edsp.model.ApiModels.ProviderSummary;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@Component
public class DataApplicationScaffoldGenerator {
    private final Environment environment;
    private final ObjectMapper mapper;
    private static final List<String> FILES = List.of("pom.xml", "README.md",
            "src/main/resources/application.yml", "config/nacos/jsnx-edsp-data-template.yaml",
            "src/main/java/com/jsnx/edsp/data/template/TemplateDataApplication.java",
            "src/main/java/com/jsnx/edsp/data/template/TemplateAdapterController.java",
            "src/main/java/com/jsnx/edsp/data/template/DataApplicationProperties.java",
            "src/test/java/com/jsnx/edsp/data/template/DataApplicationPropertiesTest.java");

    public DataApplicationScaffoldGenerator(Environment environment, ObjectMapper mapper) {
        this.environment = environment;
        this.mapper = mapper;
    }

    public Path outputRoot() {
        return Path.of(environment.getProperty("edsp.code-generation.output-root", "/data/edsp/generated-applications"))
                .toAbsolutePath().normalize();
    }

    public void validate(String applicationName, String providerCode) {
        if (applicationName == null || !applicationName.matches("jsnx-edsp-data-[a-z][a-z0-9-]{0,79}"))
            throw new IllegalArgumentException("处理应用标识须以 jsnx-edsp-data- 开头，仅包含小写字母、数字和连字符");
        if (providerCode == null || !providerCode.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("资源提供商编码不能包含空格或特殊字符");
        String segment = applicationName.substring("jsnx-edsp-data-".length()).replace("-", "");
        if (javax.lang.model.SourceVersion.isKeyword(segment))
            throw new IllegalArgumentException("处理应用标识不能使用 Java 保留字作为包名");
    }

    public synchronized ProvisionModels.Generation generate(ProviderSummary provider, int serverPort) throws IOException {
        validate(provider.applicationName(), provider.providerCode());
        validateServerPort(serverPort);
        Path root = outputRoot();
        Files.createDirectories(root);
        root = root.toRealPath();
        Path target = root.resolve(provider.applicationName());
        String suffix = provider.applicationName().substring("jsnx-edsp-data-".length()).replace("-", "");
        String packageName = "com.jsnx.edsp.data." + suffix;
        String className = Arrays.stream(provider.applicationName().substring("jsnx-edsp-data-".length()).split("-"))
                .filter(part -> !part.isEmpty())
                .map(part -> Character.toUpperCase(part.charAt(0)) + part.substring(1)).reduce("", String::concat);
        String applicationClass = className.endsWith("Data") ? className + "Application" : className + "DataApplication";
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(target) || !Files.isDirectory(target))
                throw new IOException("生成目录已存在且不是安全的工程目录");
            Path manifest = target.resolve(".edsp-generation.json");
            if (Files.isSymbolicLink(manifest) || !Files.isRegularFile(manifest))
                throw new IOException("目录已存在但没有平台生成标识，禁止覆盖");
            var identity = mapper.readTree(Files.readString(manifest));
            if (!provider.applicationName().equals(identity.path("applicationName").asText())
                    || !provider.providerCode().equals(identity.path("providerCode").asText()))
                throw new IOException("已有工程归属与当前资源提供商不一致，禁止覆盖");
            return new ProvisionModels.Generation("REUSED", "已有工程已保留，未覆盖任何代码", target.toString(), packageName,
                    mapper.convertValue(identity.path("files"), mapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        }
        Path staging = Files.createTempDirectory(root, ".scaffold-");
        boolean moved = false;
        try {
            List<String> generated = new ArrayList<>();
            for (String original : FILES) {
                String name = original.replace("com/jsnx/edsp/data/template", packageName.replace('.', '/'))
                        .replace("TemplateDataApplication", applicationClass)
                        .replace("TemplateAdapterController", className + "AdapterController")
                        .replace("jsnx-edsp-data-template.yaml", provider.applicationName() + ".yaml");
                String content;
                try (var stream = new ClassPathResource("scaffold/" + original).getInputStream()) {
                    content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
                content = content.replace("jsnx-edsp-data-application-template", provider.applicationName())
                        .replace("jsnx-edsp-data-template", provider.applicationName())
                        .replace("com.jsnx.edsp.data.template", packageName)
                        .replace("TemplateDataApplication", applicationClass)
                        .replace("TemplateAdapterController", className + "AdapterController");
                if (original.endsWith(".yaml")) {
                    content = content.replace("provider-code: TEMPLATE", "provider-code: " + yamlQuote(provider.providerCode()))
                            .replace("display-name: 独立数据应用样例", "display-name: " + yamlQuote(provider.providerName()))
                            .replace("${SERVER_PORT:8199}", "${SERVER_PORT:" + serverPort + "}");
                }
                if (original.equals("README.md")) content = readme(provider, packageName, serverPort);
                Path file = staging.resolve(name);
                Files.createDirectories(file.getParent());
                Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                generated.add(name);
            }
            Files.writeString(staging.resolve(".edsp-generation.json"), mapper.writeValueAsString(Map.of(
                    "applicationName", provider.applicationName(), "providerCode", provider.providerCode(),
                    "providerId", provider.id(), "templateVersion", "2", "files", generated)));
            // Same filesystem, no fallback to partially visible copy.
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            moved = true;
            return new ProvisionModels.Generation("SUCCESS", "Java 工程已生成", target.toString(), packageName, generated);
        } finally {
            if (!moved) try (var paths = Files.walk(staging)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    public ProvisionModels.Generation inspect(ProviderSummary provider) {
        if (provider.applicationName() == null || provider.applicationName().isBlank()) {
            return new ProvisionModels.Generation("SKIPPED", "尚未配置处理应用标识", null, null, List.of());
        }
        try {
            validate(provider.applicationName(), provider.providerCode());
            Path root = outputRoot();
            if (Files.isDirectory(root)) root = root.toRealPath();
            Path target = root.resolve(provider.applicationName()).normalize();
            if (!target.startsWith(root) || Files.isSymbolicLink(target) || !Files.isDirectory(target)) {
                return new ProvisionModels.Generation("SKIPPED", "尚未生成 Java 工程", null, null, List.of());
            }
            Path manifest = target.resolve(".edsp-generation.json");
            if (Files.isSymbolicLink(manifest) || !Files.isRegularFile(manifest)) {
                return new ProvisionModels.Generation("FAILED", "工程目录存在，但缺少平台生成清单", target.toString(), null, List.of());
            }
            var identity = mapper.readTree(Files.readString(manifest));
            if (!provider.applicationName().equals(identity.path("applicationName").asText())
                    || !provider.providerCode().equals(identity.path("providerCode").asText())) {
                return new ProvisionModels.Generation("FAILED", "工程生成清单与当前资源提供商不匹配", target.toString(), null, List.of());
            }
            String suffix = provider.applicationName().substring("jsnx-edsp-data-".length()).replace("-", "");
            List<String> files = mapper.convertValue(identity.path("files"),
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return new ProvisionModels.Generation("REUSED", "已查询到平台生成的 Java 工程", target.toString(),
                    "com.jsnx.edsp.data." + suffix, files);
        } catch (Exception ex) {
            return new ProvisionModels.Generation("FAILED", "工程状态读取失败：" + ex.getMessage(), null, null, List.of());
        }
    }

    private String yamlQuote(String value) throws IOException { return mapper.writeValueAsString(value); }

    public String renderNacosYaml(ProviderSummary provider, int serverPort) throws IOException {
        validate(provider.applicationName(), provider.providerCode());
        validateServerPort(serverPort);
        try (var stream = new ClassPathResource("scaffold/config/nacos/jsnx-edsp-data-template.yaml").getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("jsnx-edsp-data-template", provider.applicationName())
                    .replace("provider-code: TEMPLATE", "provider-code: " + yamlQuote(provider.providerCode()))
                    .replace("display-name: 独立数据应用样例", "display-name: " + yamlQuote(provider.providerName()))
                    .replace("${SERVER_PORT:8199}", "${SERVER_PORT:" + serverPort + "}");
        }
    }

    private void validateServerPort(int serverPort) {
        if (serverPort < 1 || serverPort > 65535) {
            throw new IllegalArgumentException("应用监听端口必须在 1 到 65535 之间");
        }
    }

    private String readme(ProviderSummary provider, String packageName, int serverPort) {
        return """
                # %s

                平台生成的 JDK 17 资源提供商独立处理应用。提供商编码：`%s`，Java 包：`%s`。

                ## 接入正式工程

                1. 将本目录复制到后端工程 `data-applications/%s`，不要复制 target 目录。
                2. 在后端父 pom.xml 的 modules 中加入 `data-applications/%s`。
                3. 配置 Nacos Data ID `%s.yaml`（平台已尝试同步，结果以页面为准）。
                4. 当前 Nacos 默认监听端口为 `%d`；生产可通过 SERVER_PORT 环境变量覆盖。
                5. 使用 JDK 17 执行 `mvn -pl data-applications/%s -am clean verify`。
                6. 将代码提交 Git，按行内流程打包、部署，检查 `/actuator/health` 和 `/internal/adapter/info`。
                7. 页面登记的运行地址是 %s；部署必须与此地址或 VIP 后端一致。

                ## 开发与配置

                普通接口通过平台配置、保存和发布执行计划，配置更新无需重启数据应用。
                特殊供应商协议、SDK 或算法在本模块内扩展，保持内部 adapter 接口契约不变。
                源码及 YAML 不保存密码和密钥，使用部署环境变量或受控密钥引用。
                平台重复保存仅复用本生成目录，不覆盖开发人员修改过的文件；Nacos 仅同步平台管理的监听端口。
                生成工程不代表已部署，必须完成开发、测试、打包和上线流程。
                """.formatted(provider.applicationName(), provider.providerCode(), packageName, provider.applicationName(),
                provider.applicationName(), provider.applicationName(), serverPort, provider.applicationName(), provider.runtimeUrl());
    }
}
