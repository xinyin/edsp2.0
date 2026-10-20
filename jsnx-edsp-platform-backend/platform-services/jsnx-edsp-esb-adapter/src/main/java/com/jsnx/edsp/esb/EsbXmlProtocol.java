package com.jsnx.edsp.esb;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

@Component
public class EsbXmlProtocol {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private final String replyQmgr;
    private final String timeout;
    private final String serviceName;
    private final Clock clock;

    @Autowired
    public EsbXmlProtocol(
            @Value("${edsp.esb.reply-qmgr:QMG4GW06}") String replyQmgr,
            @Value("${edsp.esb.response-timeout:150}") String timeout,
            @Value("${edsp.esb.service-name:外部数据平台}") String serviceName) {
        this(replyQmgr, timeout, serviceName, Clock.systemDefaultZone());
    }

    EsbXmlProtocol(String replyQmgr, String timeout, String serviceName, Clock clock) {
        this.replyQmgr = replyQmgr;
        this.timeout = timeout;
        this.serviceName = serviceName;
        this.clock = clock;
    }

    public EsbRequestEnvelope parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(SAXParseException error) throws SAXParseException {
                    throw error;
                }

                @Override
                public void fatalError(SAXParseException error) throws SAXParseException {
                    throw error;
                }
            });
            Document document = builder.parse(new InputSource(new StringReader(xml)));
            Element root = document.getDocumentElement();
            if (root == null || !"Service".equals(root.getTagName())) {
                throw new IllegalArgumentException("ESB XML 根节点必须为 Service");
            }
            Element header = requiredChild(root, "Service_Header");
            Element body = requiredChild(root, "Service_Body");
            Element request = requiredChild(body, "request");
            String serviceSn = requiredText(header, "service_sn");
            String serviceId = requiredText(header, "service_id");
            String requesterId = requiredText(header, "requester_id");
            String serviceTime = requiredText(header, "service_time");
            String versionId = requiredText(header, "version_id");
            return new EsbRequestEnvelope(serviceSn, serviceId, requesterId, serviceTime, versionId,
                    readChildren(request));
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("ESB XML 解析失败：" + error.getMessage(), error);
        }
    }

    public EsbRequestEnvelope parseOrFallback(String xml, String traceId) {
        try {
            return parse(xml);
        } catch (RuntimeException ignored) {
            String effectiveTrace = traceId == null || traceId.isBlank()
                    ? java.util.UUID.randomUUID().toString().replace("-", "") : traceId;
            return new EsbRequestEnvelope(effectiveTrace, "UNKNOWN", "UNKNOWN", "", "01", Map.of());
        }
    }

    public String success(EsbRequestEnvelope request, Map<String, Object> standardResponse) {
        return write(request, standardResponse, false);
    }

    public String failure(EsbRequestEnvelope request, String code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", code == null || code.isBlank() ? "EXT-PLATFORM-ERROR" : code);
        response.put("message", message == null || message.isBlank() ? "平台处理失败" : message);
        response.put("resultStatus", "ERROR");
        response.put("traceId", request.serviceSn());
        response.put("data", null);
        return write(request, response, true);
    }

    private String write(EsbRequestEnvelope request, Map<String, Object> response, boolean forceError) {
        try {
            Instant started = clock.instant();
            boolean error = forceError || "ERROR".equalsIgnoreCase(text(response.get("resultStatus")));
            String standardCode = defaultText(response.get("code"), error ? "EXT-PLATFORM-ERROR" : "EXT-SUCCESS");
            String message = defaultText(response.get("message"), error ? "平台处理失败" : "处理成功");
            String statusCode = error ? "-1" : "100";
            StringWriter output = new StringWriter();
            XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("Service");
            writer.writeStartElement("Service_Header");
            element(writer, "reply_qmgr", replyQmgr);
            writer.writeStartElement("service_response");
            element(writer, "code", error ? "999999" : "000000");
            element(writer, "desc", message);
            element(writer, "status", error ? "FAILED" : "COMPLETE");
            writer.writeEndElement();
            element(writer, "msglog", "1");
            element(writer, "timeout", timeout);
            element(writer, "name", serviceName);
            element(writer, "start_time", String.valueOf(started.toEpochMilli()));
            element(writer, "start_timestamp", timestamp(started));
            element(writer, "service_sn", request.serviceSn());
            element(writer, "service_id", request.serviceId());
            element(writer, "requester_id", request.requesterId());
            element(writer, "service_time", request.serviceTime());
            element(writer, "version_id", request.versionId());
            element(writer, "trace_msg", "Reply to responseQ - IBM.SERVICE.RESPONSE.OUT.MSDSP: " + replyQmgr);
            element(writer, "end_timestamp", timestamp(clock.instant()));
            writer.writeEndElement();
            writer.writeStartElement("Service_Body");
            writer.writeStartElement("request");
            writeMap(writer, request.requestBody());
            writer.writeEndElement();
            writer.writeStartElement("response");
            element(writer, "msg", message);
            element(writer, "response_code", statusCode);
            writer.writeStartElement("data");
            writeValue(writer, "item", response.get("data"), false);
            writer.writeEndElement();
            for (Map.Entry<String, Object> entry : response.entrySet()) {
                if (!java.util.Set.of("code", "message", "resultStatus", "traceId", "data").contains(entry.getKey())) {
                    writeValue(writer, safeElementName(entry.getKey()), entry.getValue(), true);
                }
            }
            element(writer, "resp_code", standardCode);
            element(writer, "status", statusCode);
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.close();
            return output.toString();
        } catch (Exception error) {
            throw new IllegalStateException("ESB XML 响应生成失败：" + error.getMessage(), error);
        }
    }

    private Map<String, Object> readChildren(Element parent) {
        Map<String, Object> result = new LinkedHashMap<>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (!(node instanceof Element child)) continue;
            Object value = hasElementChild(child) ? readChildren(child) : child.getTextContent();
            Object existing = result.get(child.getTagName());
            if (existing == null) {
                result.put(child.getTagName(), value);
            } else if (existing instanceof List<?> list) {
                @SuppressWarnings("unchecked") List<Object> values = (List<Object>) list;
                values.add(value);
            } else {
                List<Object> values = new ArrayList<>();
                values.add(existing);
                values.add(value);
                result.put(child.getTagName(), values);
            }
        }
        return result;
    }

    private boolean hasElementChild(Element parent) {
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index) instanceof Element) return true;
        }
        return false;
    }

    private Element requiredChild(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node instanceof Element child && name.equals(child.getTagName())) return child;
        }
        throw new IllegalArgumentException("ESB XML 缺少节点：" + name);
    }

    private String requiredText(Element parent, String name) {
        String value = requiredChild(parent, name).getTextContent();
        if (value == null || value.isBlank()) throw new IllegalArgumentException("ESB XML 节点不能为空：" + name);
        return value.trim();
    }

    private void writeMap(XMLStreamWriter writer, Map<?, ?> values) throws Exception {
        if (values == null) return;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            writeValue(writer, safeElementName(String.valueOf(entry.getKey())), entry.getValue(), true);
        }
    }

    private void writeValue(XMLStreamWriter writer, String name, Object value, boolean writeWrapper) throws Exception {
        if (value instanceof Map<?, ?> map) {
            if (writeWrapper) writer.writeStartElement(name);
            writeMap(writer, map);
            if (writeWrapper) writer.writeEndElement();
        } else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) writeValue(writer, name, item, true);
        } else if (value != null) {
            if (writeWrapper) writer.writeStartElement(name);
            writer.writeCharacters(text(value));
            if (writeWrapper) writer.writeEndElement();
        }
    }

    private void element(XMLStreamWriter writer, String name, String value) throws Exception {
        writer.writeStartElement(name);
        if (value != null) writer.writeCharacters(value);
        writer.writeEndElement();
    }

    private String timestamp(Instant instant) {
        return TIMESTAMP.format(instant.atZone(clock.getZone()));
    }

    private String safeElementName(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (safe.isBlank()) return "field";
        if (!Character.isLetter(safe.charAt(0)) && safe.charAt(0) != '_') return "field_" + safe;
        return safe;
    }

    private String defaultText(Object value, String fallback) {
        String text = text(value);
        return text.isBlank() ? fallback : text;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
