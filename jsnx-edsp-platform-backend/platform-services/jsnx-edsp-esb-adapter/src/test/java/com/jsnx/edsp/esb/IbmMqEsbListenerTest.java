package com.jsnx.edsp.esb;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IbmMqEsbListenerTest {
    private static final String XML = """
            <Service><Service_Header><service_sn>TRACE-1</service_sn><service_id>API-1</service_id>
            <requester_id>CALLER-1</requester_id><service_time>20261017100000</service_time>
            <version_id>01</version_id></Service_Header><Service_Body><request><key>A</key></request></Service_Body></Service>
            """;

    @Test
    void sendsXmlWithRequestCorrelationId() throws Exception {
        EsbMessageAdapter adapter = mock(EsbMessageAdapter.class);
        EsbXmlProtocol protocol = new EsbXmlProtocol("QM1", "150", "EDSP");
        EsbRequestEnvelope envelope = protocol.parse(XML);
        String response = protocol.success(envelope, Map.of("code", "OK", "message", "OK"));
        when(adapter.forward("", "CALLER-1", "server-secret", XML, "TRACE-1"))
                .thenReturn(new EsbForwardResult(envelope, Map.of(), response, Map.of()));
        IbmMqEsbListener listener = new IbmMqEsbListener(adapter, protocol, new ObjectMapper(),
                "{\"CALLER-1\":\"server-secret\"}");
        listener.setResponseQueue("RESPONSE.Q");
        Session session = mock(Session.class);
        Queue queue = mock(Queue.class);
        MessageProducer producer = mock(MessageProducer.class);
        TextMessage request = mock(TextMessage.class);
        TextMessage reply = mock(TextMessage.class);
        when(request.getText()).thenReturn(XML);
        when(request.getJMSCorrelationID()).thenReturn("CORR-1");
        when(session.createQueue("RESPONSE.Q")).thenReturn(queue);
        when(session.createTextMessage(response)).thenReturn(reply);
        when(session.createProducer(queue)).thenReturn(producer);

        listener.onMessage(request, session);

        verify(reply).setJMSCorrelationID("CORR-1");
        verify(producer).send(reply);
        verify(producer).setDeliveryMode(jakarta.jms.DeliveryMode.PERSISTENT);
    }

    @Test
    void missingCallerCredentialReturnsXmlFailureWithoutCallingGateway() throws Exception {
        EsbMessageAdapter adapter = mock(EsbMessageAdapter.class);
        IbmMqEsbListener listener = new IbmMqEsbListener(adapter,
                new EsbXmlProtocol("QM1", "150", "EDSP"), new ObjectMapper(),
                "{\"OTHER\":\"server-secret\"}");
        listener.setResponseQueue("RESPONSE.Q");
        Session session = mock(Session.class);
        Queue queue = mock(Queue.class);
        MessageProducer producer = mock(MessageProducer.class);
        TextMessage request = mock(TextMessage.class);
        TextMessage reply = mock(TextMessage.class);
        when(request.getText()).thenReturn(XML);
        when(request.getJMSMessageID()).thenReturn("ID:123");
        when(session.createQueue("RESPONSE.Q")).thenReturn(queue);
        when(session.createTextMessage(any(String.class))).thenReturn(reply);
        when(session.createProducer(queue)).thenReturn(producer);

        listener.onMessage(request, session);

        verifyNoInteractions(adapter);
        verify(session).createTextMessage(org.mockito.ArgumentMatchers.contains("ESB_ADAPTER_ERROR"));
        verify(reply).setJMSCorrelationID("ID:123");
        verify(producer).send(reply);
    }

    @Test
    void rejectsMissingCallerKeys() {
        assertThrows(IllegalArgumentException.class, () -> new IbmMqEsbListener(
                mock(EsbMessageAdapter.class), new EsbXmlProtocol("QM1", "150", "EDSP"),
                new ObjectMapper(), "{}"));
    }

    @Test
    void responseSendFailurePropagatesToTransactedContainer() throws Exception {
        EsbMessageAdapter adapter = mock(EsbMessageAdapter.class);
        IbmMqEsbListener listener = new IbmMqEsbListener(adapter,
                new EsbXmlProtocol("QM1", "150", "EDSP"), new ObjectMapper(),
                "{\"OTHER\":\"server-secret\"}");
        listener.setResponseQueue("RESPONSE.Q");
        Session session = mock(Session.class);
        Queue queue = mock(Queue.class);
        MessageProducer producer = mock(MessageProducer.class);
        TextMessage request = mock(TextMessage.class);
        TextMessage reply = mock(TextMessage.class);
        when(request.getText()).thenReturn(XML);
        when(request.getJMSMessageID()).thenReturn("ID:123");
        when(session.createQueue("RESPONSE.Q")).thenReturn(queue);
        when(session.createTextMessage(any(String.class))).thenReturn(reply);
        when(session.createProducer(queue)).thenReturn(producer);
        doThrow(new jakarta.jms.JMSException("MQ unavailable")).when(producer).send(reply);

        assertThrows(jakarta.jms.JMSException.class, () -> listener.onMessage(request, session));
    }
}
