package com.jsnx.edsp.provision;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import jakarta.annotation.PreDestroy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Properties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class NacosConfigPublisher {
    private static final int READ_BACK_ATTEMPTS = 5;
    private static final long READ_BACK_INTERVAL_MS = 200L;
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
        // CAS protects an operator-created config between the initial read and this write. Some
        // Nacos versions can return false while the create has already committed, so the read-back
        // content is authoritative before reporting a failure to the page.
        boolean accepted = client().publishConfigCas(dataId, group(), yaml,
                "d41d8cd98f00b204e9800998ecf8427e", "yaml");
        boolean stored = awaitPublishedContent(dataId, yaml);
        if (!stored) {
            if (!accepted) throw new IllegalStateException("Nacos 拒绝发布配置，请重试或检查权限");
            throw new IllegalStateException("Nacos 发布后读回不一致，请检查配置中心");
        }
        return new ProvisionModels.Nacos("SUCCESS", "Nacos 配置已发布", dataId, group(), namespace());
    }

    public synchronized ProvisionModels.Nacos publishManaged(String applicationName, String generatedYaml,
                                                               int serverPort) throws Exception {
        String dataId = applicationName + ".yaml";
        String current = client().getConfig(dataId, group(), 3000);
        if (current == null || current.isBlank()) return publish(applicationName, generatedYaml);
        String updated = withServerPort(current, serverPort);
        if (normalized(current).equals(normalized(updated))) {
            return new ProvisionModels.Nacos("REUSED", "Nacos 配置已存在，应用监听端口一致", dataId, group(), namespace());
        }
        String md5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                .digest(current.getBytes(StandardCharsets.UTF_8)));
        if (!client().publishConfigCas(dataId, group(), updated, md5, "yaml")) {
            throw new IllegalStateException("Nacos 配置已被并发修改，请刷新后重试");
        }
        if (!awaitPublishedContent(dataId, updated)) {
            throw new IllegalStateException("Nacos 监听端口更新后读回不一致，请检查配置中心");
        }
        return new ProvisionModels.Nacos("SUCCESS", "已更新 Nacos 应用监听端口，其他配置保持不变",
                dataId, group(), namespace());
    }

    static String withServerPort(String yaml, int serverPort) {
        if (serverPort < 1 || serverPort > 65535) throw new IllegalArgumentException("应用监听端口无效");
        String value = "\"${SERVER_PORT:" + serverPort + "}\"";
        Pattern dotted = Pattern.compile("(?m)^server\\.port\\s*:\\s*[^\\r\\n]*(?:\\r?\\n|$)");
        Matcher dottedMatcher = dotted.matcher(yaml);
        if (dottedMatcher.find()) {
            if (dottedMatcher.find()) throw new IllegalArgumentException("Nacos 配置存在重复 server.port，不能自动更新");
            return dotted.matcher(yaml).replaceFirst("server.port: " + Matcher.quoteReplacement(value) + "\n");
        }
        String[] lines = yaml.split("(?<=\\n)", -1);
        int serverLine = -1;
        int portLine = -1;
        int serverEnd = lines.length;
        for (int i = 0; i < lines.length; i++) {
            String plain = lines[i].replaceFirst("[\\r\\n]+$", "");
            if (plain.matches("^server\\s*:\\s*(?:#.*)?$")) {
                if (serverLine >= 0) throw new IllegalArgumentException("Nacos 配置存在重复 server 节点，不能自动更新");
                serverLine = i;
                continue;
            }
            if (serverLine >= 0 && i > serverLine && !plain.isBlank() && !Character.isWhitespace(plain.charAt(0))) {
                serverEnd = i;
                break;
            }
        }
        if (serverLine < 0) return "server:\n  port: " + value + "\n" + yaml;
        for (int i = serverLine + 1; i < serverEnd; i++) {
            String plain = lines[i].replaceFirst("[\\r\\n]+$", "");
            if (plain.matches("^\\s+port\\s*:.*$")) {
                if (portLine >= 0) throw new IllegalArgumentException("Nacos server 节点存在重复 port，不能自动更新");
                portLine = i;
            }
        }
        if (portLine >= 0) {
            String newline = lines[portLine].endsWith("\r\n") ? "\r\n" : "\n";
            String indent = lines[portLine].substring(0, lines[portLine].indexOf('p'));
            lines[portLine] = indent + "port: " + value + newline;
            return String.join("", lines);
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            result.append(lines[i]);
            if (i == serverLine) result.append("  port: ").append(value).append('\n');
        }
        return result.toString();
    }
    private String normalized(String content) {
        return content == null ? "" : content.replace("\r\n", "\n").stripTrailing();
    }
    private boolean awaitPublishedContent(String dataId, String expected) throws Exception {
        for (int attempt = 1; attempt <= READ_BACK_ATTEMPTS; attempt++) {
            String stored = client().getConfig(dataId, group(), 3000);
            if (normalized(expected).equals(normalized(stored))) return true;
            if (attempt < READ_BACK_ATTEMPTS) Thread.sleep(READ_BACK_INTERVAL_MS);
        }
        return false;
    }
    @PreDestroy
    public synchronized void close() throws Exception { if (client != null) client.shutDown(); }
}
