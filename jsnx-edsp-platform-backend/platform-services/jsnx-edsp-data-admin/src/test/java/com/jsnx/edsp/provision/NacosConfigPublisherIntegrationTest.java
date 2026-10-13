package com.jsnx.edsp.provision;

import com.alibaba.nacos.api.NacosFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;
import java.util.Properties;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "EDSP_NACOS_VERIFY", matches = "true")
class NacosConfigPublisherIntegrationTest {
    @Test void createsReadsAndReusesConfigWithoutOverwriting() throws Exception {
        Properties props = new Properties();
        props.setProperty("serverAddr", System.getenv().getOrDefault("NACOS_SERVER_ADDR", "127.0.0.1:8848"));
        props.setProperty("username", System.getenv("NACOS_CONFIG_USERNAME"));
        props.setProperty("password", System.getenv("NACOS_CONFIG_PASSWORD"));
        var client = NacosFactory.createConfigService(props);
        String name = "jsnx-edsp-data-provision-check-" + UUID.randomUUID();
        NacosConfigPublisher publisher = new NacosConfigPublisher(new MockEnvironment(), client);
        try {
            assertThat(publisher.publish(name, "server:\n  port: 8199\n").status()).isEqualTo("SUCCESS");
            assertThat(publisher.publish(name, "server:\n  port: 9999\n").status()).isEqualTo("REUSED");
            assertThat(client.getConfig(name + ".yaml", "DEFAULT_GROUP", 3000)).contains("8199").doesNotContain("9999");
        } finally {
            client.removeConfig(name + ".yaml", "DEFAULT_GROUP");
            publisher.close();
        }
    }
}
