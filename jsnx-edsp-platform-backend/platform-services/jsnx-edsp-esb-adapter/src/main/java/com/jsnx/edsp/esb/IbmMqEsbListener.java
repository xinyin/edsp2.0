package com.jsnx.edsp.esb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.listener.SessionAwareMessageListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConditionalOnProperty(name = "edsp.esb.mode", havingValue = "IBM_MQ")
class IbmMqEsbListener implements SessionAwareMessageListener<Message> {
    private static final Logger log = LoggerFactory.getLogger(IbmMqEsbListener.class);
    private static final TypeReference<Map<String, String>> CALLER_KEYS = new TypeReference<>() {};
    private final EsbMessageAdapter adapter;
    private final EsbXmlProtocol xmlProtocol;
    private final Map<String, String> callerKeys;
    private String responseQueue;

    IbmMqEsbListener(EsbMessageAdapter adapter, EsbXmlProtocol xmlProtocol, ObjectMapper json,
            @Value("${edsp.esb.ibm-mq.caller-keys-json:{}}") String callerKeysJson) throws Exception {
        this.adapter = adapter;
        this.xmlProtocol = xmlProtocol;
        this.callerKeys = Map.copyOf(json.readValue(callerKeysJson, CALLER_KEYS));
        if (callerKeys.isEmpty() || callerKeys.values().stream().anyMatch(v -> v == null || v.isBlank())) {
            throw new IllegalArgumentException("IBM MQ 模式必须配置有效的调用系统凭证映射");
        }
    }

    void setResponseQueue(String responseQueue) { this.responseQueue = responseQueue; }

    @Override
    public void onMessage(Message message, Session session) throws JMSException {
        String xml = message instanceof TextMessage textMessage ? textMessage.getText() : "";
        String correlationId = message.getJMSCorrelationID();
        if (correlationId == null || correlationId.isBlank()) correlationId = message.getJMSMessageID();
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("ESB 消息缺少 JMS Message ID");
        }
        String responseXml;
        try {
            if (!(message instanceof TextMessage)) {
                throw new IllegalArgumentException("ESB 请求消息必须是 XML TextMessage");
            }
            EsbRequestEnvelope envelope = xmlProtocol.parse(xml);
            String apiKey = callerKeys.get(envelope.requesterId());
            if (apiKey == null) throw new IllegalArgumentException("调用系统未配置 MQ 接入凭证");
            responseXml = adapter.forward("", envelope.requesterId(), apiKey, xml, envelope.serviceSn()).xmlResponse();
        } catch (RuntimeException error) {
            EsbRequestEnvelope fallback = xmlProtocol.parseOrFallback(xml, correlationId);
            String code = error instanceof EsbForwardException forward
                    ? "ESB_FORWARD_" + forward.status().value() : "ESB_ADAPTER_ERROR";
            responseXml = xmlProtocol.failure(fallback, code, error.getMessage());
            log.warn("ESB MQ 请求处理失败，correlationId={}，error={}", correlationId, error.getMessage());
        }
        // The fixed response queue and transacted session prevent an untrusted request from redirecting replies.
        Queue destination = session.createQueue(responseQueue);
        TextMessage reply = session.createTextMessage(responseXml);
        reply.setJMSCorrelationID(correlationId);
        try (MessageProducer producer = session.createProducer(destination)) {
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);
            producer.send(reply);
        }
    }
}
