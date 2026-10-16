package com.jsnx.edsp.provision;

import com.jsnx.edsp.model.ApiModels.SaveProviderRuntimeRequest;
import com.jsnx.edsp.service.InterfaceService;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class DataApplicationProvisionService {
    private final InterfaceService interfaces;
    private final DataApplicationScaffoldGenerator generator;
    private final NacosConfigPublisher publisher;
    private final Environment environment;
    public DataApplicationProvisionService(InterfaceService interfaces, DataApplicationScaffoldGenerator generator,
                                           NacosConfigPublisher publisher, Environment environment) {
        this.interfaces = interfaces;
        this.generator = generator;
        this.publisher = publisher;
        this.environment = environment;
    }
    public ProvisionModels.Settings settings() {
        return new ProvisionModels.Settings(environment.getProperty("edsp.code-generation.enabled", Boolean.class, true),
                generator.outputRoot().toString(), publisher.group(), publisher.namespace());
    }

    public ProvisionModels.Result status(long id) {
        var provider = interfaces.provider(id);
        var route = provider.applicationName() == null || provider.applicationName().isBlank()
                || provider.runtimeUrl() == null || provider.runtimeUrl().isBlank()
                ? new ProvisionModels.Step("SKIPPED", "尚未保存完整的应用路由配置")
                : new ProvisionModels.Step("SUCCESS", "应用路由配置已保存到 MySQL");
        var generation = generator.inspect(provider);
        var nacos = provider.applicationName() == null || provider.applicationName().isBlank()
                ? new ProvisionModels.Nacos("SKIPPED", "尚未配置处理应用标识", null, publisher.group(), publisher.namespace())
                : publisher.inspect(provider.applicationName());
        return new ProvisionModels.Result(provider, route, generation, nacos,
                new ProvisionModels.Step("SKIPPED", "本次仅查询状态，未触发 Gateway 路由刷新"));
    }

    public synchronized ProvisionModels.Result save(long id, SaveProviderRuntimeRequest request) {
        var before = interfaces.provider(id);
        generator.validate(request.applicationName().trim(), before.providerCode());
        if (interfaces.providers().stream().anyMatch(s -> s.id() != id
                && request.applicationName().trim().equals(s.applicationName())))
            throw new IllegalArgumentException("处理应用标识已被其他资源提供商使用");
        // InterfaceService has its own transaction: external I/O runs only after database commit.
        var provider = interfaces.saveProviderRuntime(id, request);
        ProvisionModels.Generation generation;
        ProvisionModels.Nacos nacos;
        if (!settings().enabled()) {
            generation = new ProvisionModels.Generation("SKIPPED", "服务端未开启工程生成", null, null, List.of());
            nacos = new ProvisionModels.Nacos("SKIPPED", "未生成工程，未同步 Nacos", provider.applicationName() + ".yaml", publisher.group(), publisher.namespace());
        } else {
            try {
                generation = generator.generate(provider, request.serverPort());
            } catch (Exception ex) {
                generation = new ProvisionModels.Generation("FAILED", "工程生成失败，请检查目录权限或已有工程归属", null, null, List.of());
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Scaffold generation failed for {}", provider.applicationName(), ex);
            }
            if ("FAILED".equals(generation.status())) {
                nacos = new ProvisionModels.Nacos("SKIPPED", "工程生成失败，未同步 Nacos", provider.applicationName() + ".yaml", publisher.group(), publisher.namespace());
            } else {
                try {
                    String yaml = generator.renderNacosYaml(provider, request.serverPort());
                    nacos = publisher.publishManaged(provider.applicationName(), yaml, request.serverPort());
                } catch (Exception ex) {
                    nacos = new ProvisionModels.Nacos("FAILED", "Nacos 同步失败；工程已保留，可重试保存或检查配置中心权限", provider.applicationName() + ".yaml", publisher.group(), publisher.namespace());
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Nacos publication failed for {}", provider.applicationName(), ex);
                }
            }
        }
        return new ProvisionModels.Result(provider, new ProvisionModels.Step("SUCCESS", "提供商应用路由已保存到 MySQL"),
                generation, nacos, new ProvisionModels.Step("REQUESTED", "已触发 Gateway 刷新；未连通实例由定时刷新兜底"));
    }
}
