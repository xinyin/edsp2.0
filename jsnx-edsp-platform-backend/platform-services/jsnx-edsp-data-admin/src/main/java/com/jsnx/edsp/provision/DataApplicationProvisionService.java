package com.jsnx.edsp.provision;

import com.jsnx.edsp.model.ApiModels.SaveDataSourceRuntimeRequest;
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

    public synchronized ProvisionModels.Result save(long id, SaveDataSourceRuntimeRequest request) {
        var before = interfaces.dataSources().stream().filter(s -> s.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("数据源不存在：" + id));
        generator.validate(request.applicationName().trim(), before.sourceCode());
        if (interfaces.dataSources().stream().anyMatch(s -> s.id() != id
                && request.applicationName().trim().equals(s.applicationName())))
            throw new IllegalArgumentException("处理应用标识已被其他数据源使用");
        // InterfaceService has its own transaction: external I/O runs only after database commit.
        var source = interfaces.saveDataSourceRuntime(id, request);
        ProvisionModels.Generation generation;
        ProvisionModels.Nacos nacos;
        if (!settings().enabled()) {
            generation = new ProvisionModels.Generation("SKIPPED", "服务端未开启工程生成", null, null, List.of());
            nacos = new ProvisionModels.Nacos("SKIPPED", "未生成工程，未同步 Nacos", source.applicationName() + ".yaml", publisher.group(), publisher.namespace());
        } else {
            try {
                generation = generator.generate(source, request.serverPort());
            } catch (Exception ex) {
                generation = new ProvisionModels.Generation("FAILED", "工程生成失败，请检查目录权限或已有工程归属", null, null, List.of());
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Scaffold generation failed for {}", source.applicationName(), ex);
            }
            if ("FAILED".equals(generation.status())) {
                nacos = new ProvisionModels.Nacos("SKIPPED", "工程生成失败，未同步 Nacos", source.applicationName() + ".yaml", publisher.group(), publisher.namespace());
            } else {
                try {
                    String yaml = generator.renderNacosYaml(source, request.serverPort());
                    nacos = publisher.publishManaged(source.applicationName(), yaml, request.serverPort());
                } catch (Exception ex) {
                    nacos = new ProvisionModels.Nacos("FAILED", "Nacos 同步失败；工程已保留，可重试保存或检查配置中心权限", source.applicationName() + ".yaml", publisher.group(), publisher.namespace());
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Nacos publication failed for {}", source.applicationName(), ex);
                }
            }
        }
        return new ProvisionModels.Result(source, new ProvisionModels.Step("SUCCESS", "运行路由已保存到 MySQL"),
                generation, nacos, new ProvisionModels.Step("REQUESTED", "已触发 Gateway 刷新；未连通实例由定时刷新兜底"));
    }
}
