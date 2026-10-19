package com.jsnx.edsp.esb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jms.connection.UserCredentialsConnectionFactoryAdapter;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

@Configuration
@ConditionalOnProperty(name = "edsp.esb.mode", havingValue = "IBM_MQ")
class IbmMqEsbConfiguration {
    private static final String PREFIX = "edsp.esb.ibm-mq.";
    private final Environment environment;

    IbmMqEsbConfiguration(Environment environment) {
        this.environment = environment;
    }

    record Endpoint(String name, String host, int port, String queueManager, String channel,
            String requestQueue, String responseQueue, String username, String password,
            String cipherSuite, String peerName, String concurrency) {}

    record Topology(Endpoint g1, Endpoint g2) {}

    @Bean
    Topology esbMqTopology() {
        Endpoint g1 = endpoint("g1");
        Endpoint g2 = endpoint("g2");
        if (g1.queueManager().equals(g2.queueManager())) {
            throw new IllegalArgumentException("双活 MQ 必须配置两个不同的队列管理器");
        }
        return new Topology(g1, g2);
    }

    @Bean("esbMqG1Listener")
    DefaultMessageListenerContainer esbMqG1Listener(Topology topology, EsbMessageAdapter adapter,
            EsbXmlProtocol protocol, ObjectMapper json,
            @Value("${edsp.esb.ibm-mq.caller-keys-json:{}}") String callerKeysJson) throws Exception {
        return listenerContainer(topology.g1(), adapter, protocol, json, callerKeysJson);
    }

    @Bean("esbMqG2Listener")
    DefaultMessageListenerContainer esbMqG2Listener(Topology topology, EsbMessageAdapter adapter,
            EsbXmlProtocol protocol, ObjectMapper json,
            @Value("${edsp.esb.ibm-mq.caller-keys-json:{}}") String callerKeysJson) throws Exception {
        return listenerContainer(topology.g2(), adapter, protocol, json, callerKeysJson);
    }

    private Endpoint endpoint(String name) {
        String prefix = PREFIX + name + ".";
        String host = required(name, "host", value(prefix + "host", ""));
        int port = environment.getProperty(prefix + "port", Integer.class, 0);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("IBM MQ " + name + " port 无效");
        String queueManager = required(name, "queue-manager", value(prefix + "queue-manager", ""));
        String channel = required(name, "channel", value(prefix + "channel", ""));
        String requestQueue = required(name, "request-queue", value(prefix + "request-queue", ""));
        String responseQueue = required(name, "response-queue", value(prefix + "response-queue", ""));
        if (requestQueue.equals(responseQueue)) {
            throw new IllegalArgumentException("IBM MQ " + name + " 请求和响应队列不能相同");
        }
        String username = value(prefix + "username", "");
        String password = value(prefix + "password", "");
        if (username.isBlank() != password.isBlank()) {
            throw new IllegalArgumentException("IBM MQ " + name + " username 和 password 必须同时配置");
        }
        return new Endpoint(name, host, port, queueManager, channel, requestQueue, responseQueue,
                username, password, value(prefix + "tls-cipher-suite", ""),
                value(prefix + "tls-peer-name", ""), value(prefix + "concurrency", "1-4"));
    }

    private String value(String key, String defaultValue) {
        return environment.getProperty(key, defaultValue).trim();
    }

    private static String required(String group, String name, String value) {
        if (value.isBlank()) throw new IllegalArgumentException("IBM MQ " + group + " 配置缺失：" + name);
        return value;
    }

    private DefaultMessageListenerContainer listenerContainer(Endpoint endpoint, EsbMessageAdapter adapter,
            EsbXmlProtocol protocol, ObjectMapper json, String callerKeysJson) throws Exception {
        IbmMqEsbListener listener = new IbmMqEsbListener(adapter, protocol, json, callerKeysJson);
        listener.setResponseQueue(endpoint.responseQueue());
        DefaultMessageListenerContainer container = new DefaultMessageListenerContainer();
        container.setConnectionFactory(connectionFactory(endpoint));
        container.setDestinationName(endpoint.requestQueue());
        container.setSessionTransacted(true);
        container.setMessageListener(listener);
        container.setConcurrency(endpoint.concurrency());
        container.setRecoveryInterval(5000);
        return container;
    }

    private ConnectionFactory connectionFactory(Endpoint endpoint) throws JMSException {
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        factory.setHostName(endpoint.host());
        factory.setPort(endpoint.port());
        factory.setQueueManager(endpoint.queueManager());
        factory.setChannel(endpoint.channel());
        if (!endpoint.cipherSuite().isBlank()) factory.setSSLCipherSuite(endpoint.cipherSuite());
        if (!endpoint.peerName().isBlank()) factory.setSSLPeerName(endpoint.peerName());
        if (endpoint.username().isBlank()) return factory;
        UserCredentialsConnectionFactoryAdapter authenticated = new UserCredentialsConnectionFactoryAdapter();
        authenticated.setTargetConnectionFactory(factory);
        authenticated.setUsername(endpoint.username());
        authenticated.setPassword(endpoint.password());
        return authenticated;
    }
}
