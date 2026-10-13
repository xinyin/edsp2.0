package com.jsnx.edsp.provision;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import jakarta.annotation.PreDestroy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Properties;

@Component
public class NacosConfigPublisher {
    private final Environment environment;
    private ConfigService client;
    @Autowired
    public NacosConfigPublisher(Environment environment) { this.environment = environment; }
    NacosConfigPublisher(Environment environment, ConfigService client) { this.environment = environment; this.client = client; }

    private synchronized ConfigService client() throws Exception {
        if (client == null) {
            Properties props = new Properties();
            props.setProperty("serverAddr", environment.getProperty("spring.cloud.nacos.config.server-addr",
                    environment.getProperty("spring.cloud.nacos.server-addr", "127.0.0.1:8848")));
            props.setProperty("namespace", namespace());
            props.setProperty("username", environment.getProperty("spring.cloud.nacos.config.username", ""));
            props.setProperty("password", environment.getProperty("spring.cloud.nacos.config.password", ""));
            client = NacosFactory.createConfigService(props);
        }
        return client;
    }
    public String group() { return environment.getProperty("spring.cloud.nacos.config.group", "DEFAULT_GROUP"); }
    public String namespace() { return environment.getProperty("spring.cloud.nacos.config.namespace", ""); }

    public synchronized ProvisionModels.Nacos publish(String applicationName, String yaml) throws Exception {
        String dataId = applicationName + ".yaml";
        String current = client().getConfig(dataId, group(), 3000);
        if (current != null && !current.isBlank())
            return new ProvisionModels.Nacos("REUSED", "Nacos 配置已存在，保留现有配置；变更请在配置中心维护", dataId, group(), namespace());
        // CAS prevents replacing a configuration created concurrently by an operator.
        if (!client().publishConfigCas(dataId, group(), yaml, "d41d8cd98f00b204e9800998ecf8427e", "yaml"))
            throw new IllegalStateException("Nacos 拒绝发布配置，请重试或检查权限");
        String stored = client().getConfig(dataId, group(), 3000);
        if (!normalized(yaml).equals(normalized(stored)))
            throw new IllegalStateException("Nacos 发布后读回不一致，请检查配置中心");
        return new ProvisionModels.Nacos("SUCCESS", "Nacos 配置已发布", dataId, group(), namespace());
    }
    private String normalized(String content) {
        return content == null ? "" : content.replace("\r\n", "\n").stripTrailing();
    }
    @PreDestroy
    public synchronized void close() throws Exception { if (client != null) client.shutDown(); }
}
