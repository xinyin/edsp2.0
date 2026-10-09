USE vendor_demo;

CREATE TABLE IF NOT EXISTS vendor_enterprise_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    enterprise_code VARCHAR(64) NOT NULL UNIQUE,
    credit_code VARCHAR(32) NOT NULL UNIQUE,
    enterprise_name VARCHAR(180) NOT NULL,
    legal_person VARCHAR(64), sex_code VARCHAR(8), risk_level VARCHAR(16),
    registration_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_enterprise_name (enterprise_name)
);

INSERT INTO vendor_enterprise_record(id,enterprise_code,credit_code,enterprise_name,legal_person,sex_code,risk_level,registration_status)
VALUES
(1,'001','91320583MA1MOCK01','昆山模拟科技有限公司','李明','1','A','ACTIVE'),
(2,'002','91110000802100433B','百度在线网络技术（北京）有限公司','崔珊珊','2','B','ACTIVE'),
(3,'003','91440300708461136T','腾讯科技（深圳）有限公司','马化腾','1','A','ACTIVE')
ON DUPLICATE KEY UPDATE enterprise_name=VALUES(enterprise_name),legal_person=VALUES(legal_person),
sex_code=VALUES(sex_code),risk_level=VALUES(risk_level),registration_status=VALUES(registration_status);

INSERT INTO vendor_data_source(id,source_code,source_name,description,base_path,status)
VALUES
(1,'ENTERPRISE','企业工商数据源','提供企业基本信息、工商照面等模拟数据','/vendor/enterprise','ACTIVE'),
(2,'CREDIT','个人征信数据源','提供个人征信报告等模拟数据','/vendor/credit','ACTIVE')
ON DUPLICATE KEY UPDATE source_name=VALUES(source_name),description=VALUES(description);

INSERT INTO vendor_api_definition(data_source_id,api_code,api_name,service_path,http_method,request_example,response_body,response_code,delay_ms,status,version)
VALUES
(1,'ENTERPRISE_BASIC','企业基本信息查询','/vendor/api/enterprise-basic','POST',
 JSON_OBJECT('credit_code','91320583MA1MOCK01','query_reason','授信准入'),
 JSON_OBJECT('result',JSON_OBJECT('code','0000','message','success'),
 'data',JSON_OBJECT('qymc','昆山模拟科技有限公司','credit_code','91320583MA1MOCK01','fddbr','李明','xingbie','1')),
 '0000',80,'ACTIVE',1),
(2,'CREDIT_REPORT_QUERY','征信报告查询','/vendor/api/credit-report','POST',
 JSON_OBJECT('query_key','320583199001010011','query_reason','授信审批'),
 JSON_OBJECT('result',JSON_OBJECT('code','0000','message','success'),
 'data',JSON_OBJECT('name','张三','sex_code','1','query_no','CR-MOCK-001')),
 '0000',120,'ACTIVE',1)
,(1,'ANTIFRAUD_SCORE','百融反欺诈评分','/vendor/api/antifraud-score','POST',
 JSON_OBJECT('credit_code','91320583MA1MOCK01','query_reason','授信准入'),
 JSON_OBJECT('result',JSON_OBJECT('code','0000','message','success'),
 'data',JSON_OBJECT('risk_score',12,'risk_level','LOW','hit','N','rule_version','BR-2026.09')),
 '0000',100,'ACTIVE',1)
ON DUPLICATE KEY UPDATE api_name=VALUES(api_name),response_body=VALUES(response_body),status='ACTIVE';
