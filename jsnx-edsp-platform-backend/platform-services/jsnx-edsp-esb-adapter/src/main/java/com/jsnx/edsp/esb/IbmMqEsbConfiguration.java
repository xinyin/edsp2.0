package com.jsnx.edsp.esb;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.connection.UserCredentialsConnectionFactoryAdapter;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

@Configuration
@ConditionalOnProperty(name = "edsp.esb.mode", havingValue = "IBM_MQ")
class IbmMqEsbConfiguration {
    @Bean
    ConnectionFactory esbMqConnectionFactory(
            @Value("${edsp.esb.ibm-mq.host:}") String host,
            @Value("${edsp.esb.ibm-mq.port:1414}") int port,
            @Value("${edsp.esb.ibm-mq.queue-manager:}") String queueManager,
            @Value("${edsp.esb.ibm-mq.channel:}") String channel,
            @Value("${edsp.esb.ibm-mq.username:}") String username,
            @Value("${edsp.esb.ibm-mq.password:}") String password,
            @Value("${edsp.esb.ibm-mq.tls-cipher-suite:}") String cipherSuite,
            @Value("${edsp.esb.ibm-mq.tls-peer-name:}") String peerName) throws JMSException {
        required("host", host);
        required("queue-manager", queueManager);
        required("channel", channel);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("IBM MQ port 无效");
        if (username.isBlank() != password.isBlank()) {
            throw new IllegalArgumentException("IBM MQ username 和 password 必须同时配置");
        }
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        factory.setHostName(host);
        factory.setPort(port);
        factory.setQueueManager(queueManager);
        factory.setChannel(channel);
        if (!cipherSuite.isBlank()) factory.setSSLCipherSuite(cipherSuite);
        if (!peerName.isBlank()) factory.setSSLPeerName(peerName);
        if (username.isBlank()) return factory;
        UserCredentialsConnectionFactoryAdapter authenticated = new UserCredentialsConnectionFactoryAdapter();
        authenticated.setTargetConnectionFactory(factory);
        authenticated.setUsername(username);
        authenticated.setPassword(password);
        return authenticated;
    }

    @Bean
    DefaultMessageListenerContainer esbMqListener(ConnectionFactory esbMqConnectionFactory,
            IbmMqEsbListener listener,
            @Value("${edsp.esb.ibm-mq.request-queue:}") String requestQueue,
            @Value("${edsp.esb.ibm-mq.response-queue:}") String responseQueue,
            @Value("${edsp.esb.ibm-mq.concurrency:1-4}") String concurrency) {
        required("request-queue", requestQueue);
        required("response-queue", responseQueue);
        if (requestQueue.equals(responseQueue)) throw new IllegalArgumentException("IBM MQ 请求和响应队列不能相同");
        listener.setResponseQueue(responseQueue);
        DefaultMessageListenerContainer container = new DefaultMessageListenerContainer();
        container.setConnectionFactory(esbMqConnectionFactory);
        container.setDestinationName(requestQueue);
        container.setSessionTransacted(true);
        container.setMessageListener(listener);
        container.setConcurrency(concurrency);
        container.setRecoveryInterval(5000);
        return container;
    }

    private static void required(String name, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("IBM MQ 配置缺失：" + name);
    }
}
