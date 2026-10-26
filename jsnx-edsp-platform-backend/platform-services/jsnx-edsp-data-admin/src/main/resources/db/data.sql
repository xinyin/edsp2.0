USE edsp_demo;

-- Upgrade existing Demo databases to the data-source default + interface override model.
DROP PROCEDURE IF EXISTS migrate_connection_inheritance;
DELIMITER $$
CREATE PROCEDURE migrate_connection_inheritance()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_source' AND column_name='endpoint_url') THEN
        ALTER TABLE data_source ADD COLUMN endpoint_url VARCHAR(500) AFTER protocol_type;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_source' AND column_name='default_timeout_ms') THEN
        ALTER TABLE data_source ADD COLUMN default_timeout_ms INT NOT NULL DEFAULT 3000 AFTER endpoint_url;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_source' AND column_name='server_port') THEN
        ALTER TABLE data_source ADD COLUMN server_port INT NOT NULL DEFAULT 8199 AFTER runtime_url;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='api_interface' AND column_name='upstream_path') THEN
        ALTER TABLE api_interface ADD COLUMN upstream_path VARCHAR(256) AFTER upstream_url;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='api_interface' AND column_name='config_scope') THEN
        ALTER TABLE api_interface ADD COLUMN config_scope VARCHAR(16) NOT NULL DEFAULT 'OVERRIDE' AFTER upstream_path;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='api_interface' AND column_name='dispatch_mode') THEN
        ALTER TABLE api_interface ADD COLUMN dispatch_mode VARCHAR(16) NOT NULL DEFAULT 'DIRECT' AFTER config_scope;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='api_interface' AND column_name='router_service_code') THEN
        ALTER TABLE api_interface ADD COLUMN router_service_code VARCHAR(128) AFTER dispatch_mode;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='api_interface' AND column_name='upstream_request_example') THEN
        ALTER TABLE api_interface ADD COLUMN upstream_request_example JSON NULL AFTER request_example;
        UPDATE api_interface SET upstream_request_example=request_example WHERE upstream_request_example IS NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='api_interface' AND column_name='upstream_status') THEN
        ALTER TABLE api_interface ADD COLUMN upstream_status VARCHAR(16) NOT NULL DEFAULT 'READY' AFTER retry_count;
        ALTER TABLE api_interface MODIFY COLUMN upstream_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT';
    END IF;
    ALTER TABLE api_interface MODIFY COLUMN timeout_ms INT NULL;
END$$
DELIMITER ;
CALL migrate_connection_inheritance();
DROP PROCEDURE migrate_connection_inheritance;

CREATE TABLE IF NOT EXISTS interface_validation_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    interface_id BIGINT NOT NULL,
    validation_type VARCHAR(32) NOT NULL,
    config_checksum VARCHAR(64) NOT NULL,
    validated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_interface_validation(interface_id, validation_type),
    CONSTRAINT fk_validation_interface FOREIGN KEY (interface_id) REFERENCES api_interface(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS api_security_policy (
    interface_id BIGINT PRIMARY KEY,
    enabled TINYINT(1) NOT NULL DEFAULT 0,
    encrypt_mode VARCHAR(32) NOT NULL DEFAULT 'NONE',
    signature_mode VARCHAR(32) NOT NULL DEFAULT 'NONE',
    request_fields VARCHAR(1000),
    secret_reference VARCHAR(256),
    encryption_key_reference VARCHAR(256),
    signing_key_reference VARCHAR(256),
    signature_field VARCHAR(256) NOT NULL DEFAULT 'signature',
    description VARCHAR(500),
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- These tables are idempotent so an existing demo database can be upgraded without dropping data.
CREATE TABLE IF NOT EXISTS provider_account (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    provider_id BIGINT NOT NULL,
    username VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(64) NOT NULL,
    mobile VARCHAR(32), email VARCHAR(128), role_code VARCHAR(32) NOT NULL DEFAULT 'PROVIDER_OPERATOR',
    permissions JSON NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    last_login_at DATETIME, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS release_approval (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    interface_id BIGINT NOT NULL,
    release_version INT NOT NULL,
    applicant VARCHAR(64) NOT NULL,
    release_note VARCHAR(500), status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    approval_node VARCHAR(64) NOT NULL DEFAULT '技术审批',
    submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_by VARCHAR(64), review_comment VARCHAR(500), reviewed_at DATETIME, checksum VARCHAR(64),
    INDEX idx_approval_status (status, submitted_at)
);
CREATE TABLE IF NOT EXISTS release_plan_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    interface_id BIGINT NOT NULL,
    release_version INT NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    plan_json JSON NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_plan_snapshot_version (interface_id, release_version)
);
CREATE TABLE IF NOT EXISTS api_call_node_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id VARCHAR(64) NOT NULL,
    node_key VARCHAR(64) NOT NULL,
    node_name VARCHAR(128) NOT NULL,
    node_category VARCHAR(32) NOT NULL,
    node_status VARCHAR(16) NOT NULL,
    node_detail VARCHAR(500), duration_ms INT NOT NULL DEFAULT 0,
    input_data JSON, output_data JSON, sort_order INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_trace_node (trace_id, node_key),
    INDEX idx_trace_order (trace_id, sort_order),
    INDEX idx_node_status_time (node_status, created_at)
);
DROP PROCEDURE IF EXISTS migrate_provider_management;
DELIMITER $$
CREATE PROCEDURE migrate_provider_management()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_provider' AND column_name='credit_code') THEN
        ALTER TABLE data_provider ADD COLUMN credit_code VARCHAR(32) AFTER provider_name;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_provider' AND column_name='provider_type') THEN
        ALTER TABLE data_provider ADD COLUMN provider_type VARCHAR(32) NOT NULL DEFAULT 'DATA_VENDOR' AFTER credit_code;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_provider' AND column_name='access_date') THEN
        ALTER TABLE data_provider ADD COLUMN access_date DATE AFTER contact_name;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_provider' AND column_name='message_encoding') THEN
        ALTER TABLE data_provider ADD COLUMN message_encoding VARCHAR(32) NOT NULL DEFAULT 'UTF-8' AFTER access_date;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_provider' AND column_name='charge_required') THEN
        ALTER TABLE data_provider ADD COLUMN charge_required TINYINT(1) NOT NULL DEFAULT 0 AFTER message_encoding;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_provider' AND column_name='encryption_required') THEN
        ALTER TABLE data_provider ADD COLUMN encryption_required TINYINT(1) NOT NULL DEFAULT 0 AFTER charge_required;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_provider' AND column_name='login_required') THEN
        ALTER TABLE data_provider ADD COLUMN login_required TINYINT(1) NOT NULL DEFAULT 0 AFTER encryption_required;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_provider' AND column_name='contact_phone') THEN
        ALTER TABLE data_provider ADD COLUMN contact_phone VARCHAR(32) AFTER contact_name;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_provider' AND column_name='contact_email') THEN
        ALTER TABLE data_provider ADD COLUMN contact_email VARCHAR(128) AFTER contact_phone;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_provider' AND column_name='service_phone') THEN
        ALTER TABLE data_provider ADD COLUMN service_phone VARCHAR(32) AFTER contact_email;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_provider' AND column_name='contract_no') THEN
        ALTER TABLE data_provider ADD COLUMN contract_no VARCHAR(64) AFTER service_phone;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_provider' AND column_name='contract_start_date') THEN
        ALTER TABLE data_provider ADD COLUMN contract_start_date DATE AFTER contract_no;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_provider' AND column_name='contract_end_date') THEN
        ALTER TABLE data_provider ADD COLUMN contract_end_date DATE AFTER contract_start_date;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_provider' AND column_name='security_level') THEN
        ALTER TABLE data_provider ADD COLUMN security_level VARCHAR(16) NOT NULL DEFAULT 'L2' AFTER contract_end_date;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_provider' AND column_name='settlement_mode') THEN
        ALTER TABLE data_provider ADD COLUMN settlement_mode VARCHAR(32) NOT NULL DEFAULT 'MONTHLY' AFTER security_level;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_provider' AND column_name='office_address') THEN
        ALTER TABLE data_provider ADD COLUMN office_address VARCHAR(256) AFTER settlement_mode;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_provider' AND column_name='description') THEN
        ALTER TABLE data_provider ADD COLUMN description VARCHAR(500) AFTER office_address;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_provider' AND column_name='access_mode') THEN
        ALTER TABLE data_provider ADD COLUMN access_mode VARCHAR(32) NOT NULL DEFAULT 'HTTP' AFTER description;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_provider' AND column_name='access_config') THEN
        ALTER TABLE data_provider ADD COLUMN access_config JSON NULL AFTER access_mode;
        UPDATE data_provider SET access_config=JSON_OBJECT() WHERE access_config IS NULL;
        ALTER TABLE data_provider MODIFY access_config JSON NOT NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_provider' AND column_name='application_name') THEN
        ALTER TABLE data_provider ADD COLUMN application_name VARCHAR(128) NULL AFTER access_config;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_provider' AND column_name='runtime_url') THEN
        ALTER TABLE data_provider ADD COLUMN runtime_url VARCHAR(500) NULL AFTER application_name;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_provider' AND column_name='server_port') THEN
        ALTER TABLE data_provider ADD COLUMN server_port INT NOT NULL DEFAULT 8199 AFTER runtime_url;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='data_provider' AND column_name='config_version') THEN
        ALTER TABLE data_provider ADD COLUMN config_version INT NOT NULL DEFAULT 1 AFTER server_port;
    END IF;
END$$
DELIMITER ;
CALL migrate_provider_management();
DROP PROCEDURE migrate_provider_management;

UPDATE data_provider p
JOIN (
    SELECT ds.provider_id,ds.application_name,ds.runtime_url,ds.server_port
    FROM data_source ds
    JOIN (
        SELECT provider_id,MIN(id) id FROM data_source GROUP BY provider_id
        HAVING COUNT(DISTINCT CONCAT(COALESCE(application_name,''),'|',COALESCE(runtime_url,''),'|',server_port))=1
    ) first_source ON first_source.id=ds.id
) legacy_route ON legacy_route.provider_id=p.id
SET p.application_name=COALESCE(p.application_name,legacy_route.application_name),
    p.server_port=CASE WHEN p.runtime_url IS NULL THEN legacy_route.server_port ELSE p.server_port END,
    p.runtime_url=COALESCE(p.runtime_url,legacy_route.runtime_url)
WHERE p.application_name IS NULL OR p.runtime_url IS NULL;

DROP PROCEDURE IF EXISTS migrate_usage_record;
DELIMITER $$
CREATE PROCEDURE migrate_usage_record()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'edsp_demo' AND table_name = 'usage_record' AND column_name = 'duration_ms') THEN
        ALTER TABLE usage_record ADD COLUMN duration_ms INT NOT NULL DEFAULT 0;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'edsp_demo' AND table_name = 'usage_record' AND column_name = 'upstream_duration_ms') THEN
        ALTER TABLE usage_record ADD COLUMN upstream_duration_ms INT NOT NULL DEFAULT 0;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'edsp_demo' AND table_name = 'usage_record' AND column_name = 'gateway_duration_ms') THEN
        ALTER TABLE usage_record ADD COLUMN gateway_duration_ms INT NOT NULL DEFAULT 0;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'edsp_demo' AND table_name = 'usage_record' AND column_name = 'request_status') THEN
        ALTER TABLE usage_record ADD COLUMN request_status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'edsp_demo' AND table_name = 'usage_record' AND column_name = 'node_name') THEN
        ALTER TABLE usage_record ADD COLUMN node_name VARCHAR(64) NOT NULL DEFAULT 'jsnx-edsp-data-source-demo-01';
    END IF;
END$$
DELIMITER ;
CALL migrate_usage_record();
DROP PROCEDURE migrate_usage_record;

INSERT INTO data_provider (id, provider_code, provider_name, credit_code, provider_type, contact_name,
access_date, message_encoding, charge_required, encryption_required, login_required,
contact_phone, contact_email, service_phone, contract_no, contract_start_date, contract_end_date,
security_level, settlement_mode, office_address, description, status)
VALUES (1, 'QXB', '企信资源提供商', '91320000MA27EDSP01', 'DATA_VENDOR', '张经理',
'2026-01-01', 'UTF-8', 1, 1, 1,
'13800001234', 'zhang.manager@vendor.example', '400-800-9600', 'JSNX-EDSP-2026-001',
'2026-01-01', '2027-12-31', 'L3', 'MONTHLY', '江苏省苏州市昆山市金融科技园',
'提供企业工商、风险和征信类外部数据服务。', 'ACTIVE')
ON DUPLICATE KEY UPDATE provider_name=VALUES(provider_name),credit_code=VALUES(credit_code),
provider_type=VALUES(provider_type),contact_name=VALUES(contact_name),contact_phone=VALUES(contact_phone),
access_date=VALUES(access_date),message_encoding=VALUES(message_encoding),
charge_required=VALUES(charge_required),encryption_required=VALUES(encryption_required),
login_required=VALUES(login_required),
contact_email=VALUES(contact_email),service_phone=VALUES(service_phone),contract_no=VALUES(contract_no),
contract_start_date=VALUES(contract_start_date),contract_end_date=VALUES(contract_end_date),
security_level=VALUES(security_level),settlement_mode=VALUES(settlement_mode),
office_address=VALUES(office_address),description=VALUES(description);

-- Clean demo databases need a provider-owned route immediately. Existing databases keep
-- their explicitly configured provider route and are only backfilled when it is absent.
UPDATE data_provider
SET access_config=CASE WHEN access_mode='HTTP' AND (access_config IS NULL OR JSON_LENGTH(access_config)=0)
                       THEN JSON_OBJECT('baseUrl','http://127.0.0.1:8090') ELSE access_config END,
    application_name=COALESCE(application_name,'jsnx-edsp-data-enterprise'),
    server_port=CASE WHEN runtime_url IS NULL THEN 8181 ELSE server_port END,
    runtime_url=COALESCE(runtime_url,'http://127.0.0.1:8181')
WHERE id=1;

INSERT INTO provider_account(provider_id,username,display_name,mobile,email,role_code,permissions,status,last_login_at)
VALUES
(1,'vendor-admin','资源提供商管理员','13800001234','vendor-admin@vendor.example','PROVIDER_ADMIN',
 JSON_ARRAY('DATASOURCE_VIEW','DATASOURCE_EDIT','API_VIEW','API_EDIT','API_TEST','CATALOG_PUBLISH'),'ACTIVE',NOW()),
(1,'vendor-auditor','资源提供商审计员','13800005678','vendor-auditor@vendor.example','PROVIDER_AUDITOR',
 JSON_ARRAY('DATASOURCE_VIEW','API_VIEW','AUDIT_VIEW'),'ACTIVE',NULL)
ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),role_code=VALUES(role_code),permissions=VALUES(permissions),status=VALUES(status);

INSERT INTO data_source (id, provider_id, source_code, source_name, application_name, runtime_url, server_port, protocol_type, endpoint_url, default_timeout_ms, status, instance_count, config_version)
VALUES
    (1, 1, 'ENTERPRISE', '企业工商数据源', 'jsnx-edsp-data-enterprise', 'http://127.0.0.1:8181', 8181, 'HTTP_JSON', 'http://127.0.0.1:8090', 3000, 'ACTIVE', 2, 3),
    (2, 1, 'CREDIT', '征信报告数据源', 'jsnx-edsp-data-credit', 'http://127.0.0.1:8182', 8182, 'HTTP_JSON', 'http://127.0.0.1:8090', 5000, 'ACTIVE', 2, 1)
ON DUPLICATE KEY UPDATE source_code = VALUES(source_code), source_name = VALUES(source_name),
    application_name = VALUES(application_name), runtime_url=VALUES(runtime_url), server_port=VALUES(server_port), endpoint_url=VALUES(endpoint_url),
    default_timeout_ms=VALUES(default_timeout_ms);

INSERT INTO data_source (id, provider_id, source_code, source_name, application_name, runtime_url, server_port, protocol_type, endpoint_url, default_timeout_ms, status, instance_count, config_version)
VALUES (3, 1, 'ANTIFRAUD', '百融反欺诈数据源', 'jsnx-edsp-data-antifraud', 'http://127.0.0.1:8183', 8183, 'HTTP_JSON', 'http://127.0.0.1:8090', 4000, 'ACTIVE', 2, 1)
ON DUPLICATE KEY UPDATE source_name=VALUES(source_name), application_name=VALUES(application_name),
runtime_url=VALUES(runtime_url), server_port=VALUES(server_port), endpoint_url=VALUES(endpoint_url), default_timeout_ms=VALUES(default_timeout_ms);

INSERT INTO api_interface (
    id, data_source_id, interface_code, interface_name, service_path, http_method, upstream_url,
    upstream_path, config_scope, dispatch_mode,
    request_schema, response_schema, request_example, upstream_response_example, standard_response_example,
    timeout_ms, retry_count, status, current_version, published_version
) VALUES (
    1, 1, 'ENTERPRISE_BASIC', '企业基本信息查询', '/api/v1/enterprise/basic', 'POST',
    'http://127.0.0.1:8090/vendor/api/enterprise-basic', '/vendor/api/enterprise-basic', 'INHERIT', 'DIRECT',
    JSON_OBJECT('type','object','required',JSON_ARRAY('creditCode'),'properties',JSON_OBJECT(
        'creditCode',JSON_OBJECT('type','string','title','统一社会信用代码'),
        'queryReason',JSON_OBJECT('type','string','title','查询原因'))),
    JSON_OBJECT('type','object','properties',JSON_OBJECT(
        'enterpriseName',JSON_OBJECT('type','string'), 'creditCode',JSON_OBJECT('type','string'),
        'legalPerson',JSON_OBJECT('type','string'), 'sex',JSON_OBJECT('type','string'))),
    JSON_OBJECT('creditCode','91320583MA1EXAMPLE','queryReason','授信准入'),
    JSON_OBJECT('result',JSON_OBJECT('code','0000','message','success'),'data',JSON_OBJECT(
        'qymc','昆山示例科技有限公司','credit_code','91320583MA1EXAMPLE','fddbr','张三','xingbie','1')),
    JSON_OBJECT('code','EXT-SUCCESS','message','查询成功','resultStatus','HIT','data',JSON_OBJECT(
        'enterpriseName','昆山示例科技有限公司','creditCode','91320583MA1EXAMPLE','legalPerson','张三','sex','MALE')),
    NULL, 0, 'PUBLISHED', 3, 2
) ON DUPLICATE KEY UPDATE interface_name = VALUES(interface_name),
    upstream_url = VALUES(upstream_url), upstream_path=VALUES(upstream_path), config_scope='INHERIT';

INSERT INTO field_mapping (interface_id, mapping_direction, source_path, target_path, transform_type, transform_config, default_value, required_flag, sort_order)
SELECT 1, 'REQUEST', 'creditCode', 'credit_code', 'DIRECT', NULL, NULL, 1, 10
WHERE NOT EXISTS (SELECT 1 FROM field_mapping WHERE interface_id=1 AND mapping_direction='REQUEST' AND source_path='creditCode');
INSERT INTO field_mapping (interface_id, mapping_direction, source_path, target_path, transform_type, transform_config, default_value, required_flag, sort_order)
SELECT 1, 'REQUEST', 'queryReason', 'query_reason', 'DIRECT', NULL, '行内业务查询', 0, 20
WHERE NOT EXISTS (SELECT 1 FROM field_mapping WHERE interface_id=1 AND mapping_direction='REQUEST' AND source_path='queryReason');
INSERT INTO field_mapping (interface_id, mapping_direction, source_path, target_path, transform_type, transform_config, default_value, required_flag, sort_order)
SELECT 1, 'RESPONSE', 'data.qymc', 'data.enterpriseName', 'DIRECT', NULL, NULL, 1, 10
WHERE NOT EXISTS (SELECT 1 FROM field_mapping WHERE interface_id=1 AND mapping_direction='RESPONSE' AND source_path='data.qymc');
INSERT INTO field_mapping (interface_id, mapping_direction, source_path, target_path, transform_type, transform_config, default_value, required_flag, sort_order)
SELECT 1, 'RESPONSE', 'data.credit_code', 'data.creditCode', 'DIRECT', NULL, NULL, 1, 20
WHERE NOT EXISTS (SELECT 1 FROM field_mapping WHERE interface_id=1 AND mapping_direction='RESPONSE' AND source_path='data.credit_code');
INSERT INTO field_mapping (interface_id, mapping_direction, source_path, target_path, transform_type, transform_config, default_value, required_flag, sort_order)
SELECT 1, 'RESPONSE', 'data.fddbr', 'data.legalPerson', 'DIRECT', NULL, NULL, 0, 30
WHERE NOT EXISTS (SELECT 1 FROM field_mapping WHERE interface_id=1 AND mapping_direction='RESPONSE' AND source_path='data.fddbr');
INSERT INTO field_mapping (interface_id, mapping_direction, source_path, target_path, transform_type, transform_config, default_value, required_flag, sort_order)
SELECT 1, 'RESPONSE', 'data.xingbie', 'data.sex', 'ENUM', JSON_OBJECT('1','MALE','2','FEMALE'), 'UNKNOWN', 0, 40
WHERE NOT EXISTS (SELECT 1 FROM field_mapping WHERE interface_id=1 AND mapping_direction='RESPONSE' AND source_path='data.xingbie');

INSERT INTO api_interface (
    id, data_source_id, interface_code, interface_name, service_path, http_method, upstream_url,
    upstream_path, config_scope, dispatch_mode, request_schema, response_schema, request_example,
    upstream_response_example, standard_response_example, timeout_ms, retry_count, status, current_version, published_version)
VALUES (2, 2, 'CREDIT_REPORT_QUERY', '个人征信报告查询', '/api/v1/credit/report', 'POST',
    'http://127.0.0.1:8090/vendor/api/credit-report', '/vendor/api/credit-report', 'INHERIT', 'DIRECT',
    JSON_OBJECT('type','object','required',JSON_ARRAY('queryKey'),'properties',JSON_OBJECT(
        'queryKey',JSON_OBJECT('type','string','title','查询关键字'),
        'queryReason',JSON_OBJECT('type','string','title','查询原因'))),
    JSON_OBJECT('type','object','properties',JSON_OBJECT(
        'customerName',JSON_OBJECT('type','string'), 'sex',JSON_OBJECT('type','string'),
        'queryNo',JSON_OBJECT('type','string'))),
    JSON_OBJECT('queryKey','320583199001010011','queryReason','授信审批'),
    JSON_OBJECT('result',JSON_OBJECT('code','0000','message','success'),'data',JSON_OBJECT(
        'name','张三','sex_code','1','query_no','CR-MOCK-001')),
    JSON_OBJECT('code','EXT-SUCCESS','message','查询成功','resultStatus','HIT','data',JSON_OBJECT(
        'customerName','张三','sex','MALE','queryNo','CR-MOCK-001')),
    NULL,0,'PUBLISHED',1,1)
ON DUPLICATE KEY UPDATE interface_name=VALUES(interface_name),upstream_url=VALUES(upstream_url),
upstream_path=VALUES(upstream_path),config_scope='INHERIT';

INSERT INTO api_interface (
    id, data_source_id, interface_code, interface_name, service_path, http_method, upstream_url,
    upstream_path, config_scope, dispatch_mode, request_schema, response_schema, request_example,
    upstream_response_example, standard_response_example, timeout_ms, retry_count, status, current_version, published_version)
VALUES (3, 3, 'ANTIFRAUD_SCORE', '百融反欺诈评分查询', '/api/v1/antifraud/score', 'POST',
    'http://127.0.0.1:8090/vendor/api/antifraud-score', '/vendor/api/antifraud-score', 'INHERIT', 'DIRECT',
    JSON_OBJECT('type','object','required',JSON_ARRAY('companyCode'),'properties',JSON_OBJECT(
        'companyCode',JSON_OBJECT('type','string','title','企业编码'),
        'queryReason',JSON_OBJECT('type','string','title','查询原因'))),
    JSON_OBJECT('type','object','properties',JSON_OBJECT(
        'riskScore',JSON_OBJECT('type','number'), 'riskLevel',JSON_OBJECT('type','string'),
        'hit',JSON_OBJECT('type','string'), 'ruleVersion',JSON_OBJECT('type','string'))),
    JSON_OBJECT('companyCode','91320583MA1MOCK01','queryReason','授信准入'),
    JSON_OBJECT('result',JSON_OBJECT('code','0000','message','success'),'data',JSON_OBJECT(
        'risk_score',12,'risk_level','A','hit','N','rule_version','BR-2026.09')),
    JSON_OBJECT('code','EXT-SUCCESS','message','查询成功','resultStatus','HIT','data',JSON_OBJECT(
        'riskScore',12,'riskLevel','A','hit','N','ruleVersion','BR-2026.09')),
    4000,0,'PUBLISHED',1,1)
ON DUPLICATE KEY UPDATE interface_name=VALUES(interface_name),upstream_url=VALUES(upstream_url),
upstream_path=VALUES(upstream_path),status='PUBLISHED',published_version=1;

INSERT INTO field_mapping(interface_id,mapping_direction,source_path,target_path,transform_type,transform_config,default_value,required_flag,sort_order)
SELECT 3,'REQUEST','companyCode','credit_code','DIRECT',NULL,NULL,1,10
WHERE NOT EXISTS (SELECT 1 FROM field_mapping WHERE interface_id=3 AND mapping_direction='REQUEST' AND source_path='companyCode');
INSERT INTO field_mapping(interface_id,mapping_direction,source_path,target_path,transform_type,transform_config,default_value,required_flag,sort_order)
SELECT 3,'REQUEST','queryReason','query_reason','DIRECT',NULL,'行内业务查询',0,20
WHERE NOT EXISTS (SELECT 1 FROM field_mapping WHERE interface_id=3 AND mapping_direction='REQUEST' AND source_path='queryReason');
INSERT INTO field_mapping(interface_id,mapping_direction,source_path,target_path,transform_type,transform_config,default_value,required_flag,sort_order)
SELECT 3,'RESPONSE','data.risk_score','data.riskScore','DIRECT',NULL,NULL,1,10
WHERE NOT EXISTS (SELECT 1 FROM field_mapping WHERE interface_id=3 AND mapping_direction='RESPONSE' AND source_path='data.risk_score');
INSERT INTO field_mapping(interface_id,mapping_direction,source_path,target_path,transform_type,transform_config,default_value,required_flag,sort_order)
SELECT 3,'RESPONSE','data.risk_level','data.riskLevel','DIRECT',NULL,NULL,1,20
WHERE NOT EXISTS (SELECT 1 FROM field_mapping WHERE interface_id=3 AND mapping_direction='RESPONSE' AND source_path='data.risk_level');
INSERT INTO field_mapping(interface_id,mapping_direction,source_path,target_path,transform_type,transform_config,default_value,required_flag,sort_order)
SELECT 3,'RESPONSE','data.hit','data.hit','DIRECT',NULL,NULL,0,30
WHERE NOT EXISTS (SELECT 1 FROM field_mapping WHERE interface_id=3 AND mapping_direction='RESPONSE' AND source_path='data.hit');
INSERT INTO field_mapping(interface_id,mapping_direction,source_path,target_path,transform_type,transform_config,default_value,required_flag,sort_order)
SELECT 3,'RESPONSE','data.rule_version','data.ruleVersion','DIRECT',NULL,NULL,0,40
WHERE NOT EXISTS (SELECT 1 FROM field_mapping WHERE interface_id=3 AND mapping_direction='RESPONSE' AND source_path='data.rule_version');
INSERT INTO response_code_mapping(interface_id,vendor_code,standard_code,result_status,retryable,billable)
VALUES (3,'0000','EXT-SUCCESS','HIT',0,1),(3,'1001','EXT-NO-DATA','MISS',0,0),(3,'9999','EXT-VENDOR-ERROR','ERROR',1,0)
ON DUPLICATE KEY UPDATE standard_code=VALUES(standard_code),result_status=VALUES(result_status),billable=VALUES(billable);
INSERT INTO billing_rule(interface_id,billing_mode,unit_price,currency,hit_billable,miss_billable,effective_date,status)
VALUES (3,'SUCCESS_COUNT',0.5000,'CNY',1,0,'2026-01-01','ACTIVE')
ON DUPLICATE KEY UPDATE unit_price=VALUES(unit_price),status='ACTIVE';

INSERT INTO api_security_policy(interface_id, enabled, encrypt_mode, signature_mode, request_fields,
encryption_key_reference, signing_key_reference, signature_field, description)
VALUES (1, 1, 'AES_256_GCM', 'HMAC_SHA256', 'credit_code',
        'config://edsp.security.keys.enterprise-encryption', 'config://edsp.security.keys.enterprise-signing', 'signature',
        '请求字段使用 AES-256-GCM 认证加密，并对规范化报文执行 HMAC-SHA256。')
ON DUPLICATE KEY UPDATE enabled=VALUES(enabled), encrypt_mode=VALUES(encrypt_mode),
signature_mode=VALUES(signature_mode), request_fields=VALUES(request_fields),
encryption_key_reference=VALUES(encryption_key_reference), signing_key_reference=VALUES(signing_key_reference),
signature_field=VALUES(signature_field), description=VALUES(description);

INSERT INTO response_code_mapping (interface_id, vendor_code, standard_code, result_status, retryable, billable)
VALUES
    (1, '0000', 'EXT-SUCCESS', 'HIT', 0, 1),
    (1, '1001', 'EXT-NO-DATA', 'MISS', 0, 0),
    (1, '9999', 'EXT-VENDOR-ERROR', 'ERROR', 1, 0)
ON DUPLICATE KEY UPDATE standard_code = VALUES(standard_code), result_status = VALUES(result_status);

INSERT INTO vendor_mock_config (interface_id, mock_name, response_body, http_status, delay_ms, enabled)
VALUES (1, '企业基本信息供应商模拟', JSON_OBJECT(
    'result', JSON_OBJECT('code','0000','message','success'),
    'data', JSON_OBJECT('qymc','昆山模拟科技有限公司','credit_code','91320583MA1MOCK01','fddbr','李明','xingbie','1')
), 200, 80, 1)
ON DUPLICATE KEY UPDATE mock_name = VALUES(mock_name), response_body = VALUES(response_body), enabled = 1;

-- Upgrade the draft created by the earlier Demo version so it can participate in the full mock call chain.
UPDATE api_interface SET
    upstream_url = 'http://127.0.0.1:8090/vendor/api/credit-report',
    request_example = JSON_OBJECT('queryKey','320583199001010011','queryReason','授信审批'),
    upstream_response_example = JSON_OBJECT('result',JSON_OBJECT('code','0000','message','success'),
        'data',JSON_OBJECT('name','张三','sex_code','1','query_no','CR-MOCK-001'))
WHERE interface_code = 'CREDIT_REPORT_QUERY';

INSERT INTO field_mapping (interface_id,mapping_direction,source_path,target_path,transform_type,transform_config,default_value,required_flag,sort_order)
SELECT id,'REQUEST','queryKey','query_key','DIRECT',NULL,NULL,1,10 FROM api_interface
WHERE interface_code='CREDIT_REPORT_QUERY' AND NOT EXISTS (
    SELECT 1 FROM field_mapping fm WHERE fm.interface_id=api_interface.id AND fm.mapping_direction='REQUEST');
INSERT INTO field_mapping (interface_id,mapping_direction,source_path,target_path,transform_type,transform_config,default_value,required_flag,sort_order)
SELECT id,'REQUEST','queryReason','query_reason','DIRECT',NULL,'行内业务查询',0,20 FROM api_interface
WHERE interface_code='CREDIT_REPORT_QUERY' AND NOT EXISTS (
    SELECT 1 FROM field_mapping fm WHERE fm.interface_id=api_interface.id AND fm.source_path='queryReason');
INSERT INTO field_mapping (interface_id,mapping_direction,source_path,target_path,transform_type,transform_config,default_value,required_flag,sort_order)
SELECT id,'RESPONSE','data.name','data.customerName','DIRECT',NULL,NULL,1,10 FROM api_interface
WHERE interface_code='CREDIT_REPORT_QUERY' AND NOT EXISTS (
    SELECT 1 FROM field_mapping fm WHERE fm.interface_id=api_interface.id AND fm.mapping_direction='RESPONSE');
INSERT INTO field_mapping (interface_id,mapping_direction,source_path,target_path,transform_type,transform_config,default_value,required_flag,sort_order)
SELECT id,'RESPONSE','data.sex_code','data.sex','ENUM',JSON_OBJECT('1','MALE','2','FEMALE'),'UNKNOWN',0,20 FROM api_interface
WHERE interface_code='CREDIT_REPORT_QUERY' AND NOT EXISTS (
    SELECT 1 FROM field_mapping fm WHERE fm.interface_id=api_interface.id AND fm.source_path='data.sex_code');
INSERT INTO field_mapping (interface_id,mapping_direction,source_path,target_path,transform_type,transform_config,default_value,required_flag,sort_order)
SELECT id,'RESPONSE','data.query_no','data.queryNo','DIRECT',NULL,NULL,0,30 FROM api_interface
WHERE interface_code='CREDIT_REPORT_QUERY' AND NOT EXISTS (
    SELECT 1 FROM field_mapping fm WHERE fm.interface_id=api_interface.id AND fm.source_path='data.query_no');

INSERT INTO response_code_mapping(interface_id,vendor_code,standard_code,result_status,retryable,billable)
VALUES (2,'0000','EXT-SUCCESS','HIT',0,1),(2,'1001','EXT-NO-DATA','MISS',0,0),(2,'9999','EXT-VENDOR-ERROR','ERROR',1,0)
ON DUPLICATE KEY UPDATE standard_code=VALUES(standard_code),result_status=VALUES(result_status),billable=VALUES(billable);

INSERT INTO vendor_mock_config (interface_id,mock_name,response_body,http_status,delay_ms,enabled)
SELECT id,'征信报告供应商模拟',JSON_OBJECT('result',JSON_OBJECT('code','0000','message','success'),
    'data',JSON_OBJECT('name','张三','sex_code','1','query_no','CR-MOCK-001')),200,120,1
FROM api_interface WHERE interface_code='CREDIT_REPORT_QUERY'
ON DUPLICATE KEY UPDATE mock_name=VALUES(mock_name),response_body=VALUES(response_body),enabled=1;

INSERT INTO api_client (id, client_code, client_name, institution_code, institution_name, owner_name, status)
VALUES
    (1, 'CREDIT_RISK', '信贷风控系统', '320583', '昆山农商银行', '李敏', 'ACTIVE'),
    (2, 'MOBILE_BANK', '手机银行系统', '320000', '省联社电子银行部', '王强', 'ACTIVE')
ON DUPLICATE KEY UPDATE client_name = VALUES(client_name), institution_name = VALUES(institution_name);

INSERT INTO api_authorization (client_id, interface_id, daily_quota, valid_from, valid_to, status, approved_by)
VALUES (1, 1, 50000, '2026-01-01', '2027-12-31', 'ACTIVE', '平台管理员')
ON DUPLICATE KEY UPDATE daily_quota = VALUES(daily_quota), status = VALUES(status);

INSERT INTO api_authorization (client_id, interface_id, daily_quota, valid_from, valid_to, status, approved_by)
VALUES (1, 3, 50000, '2026-01-01', '2027-12-31', 'ACTIVE', '平台管理员')
ON DUPLICATE KEY UPDATE daily_quota = VALUES(daily_quota), status = VALUES(status);

INSERT INTO api_authorization (client_id, interface_id, daily_quota, valid_from, valid_to, status, approved_by)
VALUES (1, 2, 50000, '2026-01-01', '2027-12-31', 'ACTIVE', '平台管理员')
ON DUPLICATE KEY UPDATE daily_quota = VALUES(daily_quota), status = VALUES(status);

INSERT INTO billing_rule (interface_id, billing_mode, unit_price, currency, hit_billable, miss_billable, effective_date, status)
VALUES (1, 'SUCCESS_COUNT', 0.3500, 'CNY', 1, 0, '2026-01-01', 'ACTIVE')
ON DUPLICATE KEY UPDATE unit_price = VALUES(unit_price), status = VALUES(status);

INSERT INTO billing_rule (interface_id, billing_mode, unit_price, currency, hit_billable, miss_billable, effective_date, status)
VALUES (2, 'SUCCESS_COUNT', 0.8000, 'CNY', 1, 0, '2026-01-01', 'ACTIVE')
ON DUPLICATE KEY UPDATE unit_price = VALUES(unit_price), status = VALUES(status);

INSERT INTO usage_record (trace_id, client_id, interface_id, result_status, standard_code, billable, unit_price, amount, called_at)
VALUES
    ('DEMO202607230001', 1, 1, 'HIT', 'EXT-SUCCESS', 1, 0.3500, 0.3500, '2026-07-23 10:15:00'),
    ('DEMO202607230002', 1, 1, 'MISS', 'EXT-NO-DATA', 0, 0.3500, 0.0000, '2026-07-23 10:16:00'),
    ('DEMO202607240001', 1, 1, 'HIT', 'EXT-SUCCESS', 1, 0.3500, 0.3500, '2026-07-24 09:20:00')
ON DUPLICATE KEY UPDATE result_status = VALUES(result_status);
