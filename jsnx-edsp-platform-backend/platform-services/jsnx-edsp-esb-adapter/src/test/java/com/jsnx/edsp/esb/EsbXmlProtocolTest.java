package com.jsnx.edsp.esb;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.xml.sax.InputSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsbXmlProtocolTest {
    private static final String REQUEST = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Service>
              <Service_Header>
                <service_sn>4090346917679210226</service_sn>
                <service_id>LHZX_QUERY_01_SERVICE_01</service_id>
                <requester_id>4090</requester_id>
                <service_time>20261012140467</service_time>
                <version_id>01</version_id>
              </Service_Header>
              <Service_Body>
                <request>
                  <company_name>江苏&amp;银行</company_name>
                  <contacts><name>张三</name><phone>13800000001</phone></contacts>
                  <tags>A</tags><tags>B</tags>
                </request>
              </Service_Body>
            </Service>
            """;

    private final EsbXmlProtocol protocol = new EsbXmlProtocol("QMG4GW06", "150", "外部数据平台",
            Clock.fixed(Instant.parse("2026-10-12T08:34:23.123Z"), ZoneId.of("Asia/Shanghai")));

    @Test
    void parsesProductionEnvelopeAndRepeatedNodes() {
        EsbRequestEnvelope request = protocol.parse(REQUEST);

        assertEquals("4090346917679210226", request.serviceSn());
        assertEquals("LHZX_QUERY_01_SERVICE_01", request.serviceId());
        assertEquals("4090", request.requesterId());
        assertEquals("江苏&银行", request.requestBody().get("company_name"));
        assertEquals(Map.of("name", "张三", "phone", "13800000001"), request.requestBody().get("contacts"));
        assertEquals(List.of("A", "B"), request.requestBody().get("tags"));
    }

    @Test
    void serializesSuccessWithEchoedRequestNestedDataAndEscaping() throws Exception {
        EsbRequestEnvelope request = protocol.parse(REQUEST);
        String response = protocol.success(request, Map.of(
                "code", "EXT-SUCCESS",
                "message", "查询成功 <已核验>",
                "resultStatus", "HIT",
                "traceId", request.serviceSn(),
                "data", Map.of(
                        "company", Map.of("name", "江苏&银行"),
                        "shareholders", List.of(Map.of("name", "甲"), Map.of("name", "乙")))));

        Document document = parseXml(response);
        assertEquals("Service", document.getDocumentElement().getTagName());
        assertEquals("000000", text(document, "code"));
        assertEquals("COMPLETE", text(document, "status"));
        assertEquals("4090346917679210226", text(document, "service_sn"));
        assertEquals("江苏&银行", text(document, "company_name"));
        assertEquals("EXT-SUCCESS", text(document, "resp_code"));
        assertEquals("100", text(document, "response_code"));
        assertEquals("查询成功 <已核验>", text(document, "desc"));
        assertEquals(2, document.getElementsByTagName("shareholders").getLength());
        assertTrue(response.contains("江苏&amp;银行"));
    }

    @Test
    void serializesWholeSupplierBodyUnderDataWithoutDroppingNestedFields() throws Exception {
        Map<String, Object> supplier = Map.of(
                "page", 1,
                "data", List.of(Map.of("id", 1, "email", "a@example.com"),
                        Map.of("id", 2, "email", "b@example.com")),
                "support", Map.of("url", "https://example.com", "text", "Help & support"),
                "_meta", Map.of("variant", "v1_b"));
        String response = protocol.success(protocol.parse(REQUEST), Map.of(
                "code", "0000", "message", "查询成功", "resultStatus", "HIT", "data", supplier));

        Document document = parseXml(response);
        Element responseData = (Element) document.getElementsByTagName("response").item(0);
        Element data = (Element) responseData.getElementsByTagName("data").item(0);
        assertEquals("1", ((Element) data.getElementsByTagName("page").item(0)).getTextContent());
        assertEquals(2, data.getElementsByTagName("id").getLength());
        assertEquals("b@example.com", data.getElementsByTagName("email").item(1).getTextContent());
        assertEquals("Help & support", data.getElementsByTagName("text").item(0).getTextContent());
        assertEquals("v1_b", data.getElementsByTagName("variant").item(0).getTextContent());
        assertEquals("0000", text(document, "resp_code"));
    }

    @Test
    void serializesConfiguredRootResponseNodeBesideData() throws Exception {
        String response = protocol.success(protocol.parse(REQUEST), Map.of(
                "code", "0000", "message", "查询成功", "resultStatus", "HIT",
                "data", Map.of(), "payload", Map.of("supplierResult", Map.of("items", List.of(
                        Map.of("id", 1), Map.of("id", 2))))));
        Document document = parseXml(response);
        Element payload = (Element) document.getElementsByTagName("payload").item(0);
        assertEquals(2, payload.getElementsByTagName("id").getLength());
        assertEquals("0000", text(document, "resp_code"));
    }

    @Test
    void serializesFailureInTheSameProductionEnvelope() throws Exception {
        String response = protocol.failure(protocol.parse(REQUEST), "ESB_FORWARD_503", "数据源不可用");
        Document document = parseXml(response);

        assertEquals("999999", text(document, "code"));
        assertEquals("FAILED", text(document, "status"));
        assertEquals("ESB_FORWARD_503", text(document, "resp_code"));
        assertEquals("-1", text(document, "response_code"));
        assertEquals("数据源不可用", text(document, "msg"));
    }

    @Test
    void rejectsDoctypeAndInvalidContract() {
        assertThrows(IllegalArgumentException.class,
                () -> protocol.parse("<!DOCTYPE Service [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><Service>&xxe;</Service>"));
        assertThrows(IllegalArgumentException.class,
                () -> protocol.parse("<edspRequest><apiCode>OLD</apiCode></edspRequest>"));
    }

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private String text(Document document, String name) {
        return ((Element) document.getElementsByTagName(name).item(0)).getTextContent();
    }
}
