"""Generate the EDSP 2.0 beginner-friendly development manual from the current code baseline."""
from pathlib import Path
from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor


ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).parent / "外部数据平台2.0开发手册V1.0.docx"
FLOW_IMAGE = Path(__file__).parent / "产品文档20261009" / "完整调用链路图.png"


def set_font(run, size=10.5, bold=False, name="Arial"):
    run.font.name = name
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.color.rgb = RGBColor(0, 0, 0)
    run._element.get_or_add_rPr().rFonts.set(qn("w:eastAsia"), "Heiti SC")


def shade(cell, fill):
    tc_pr = cell._tc.get_or_add_tcPr()
    shd = tc_pr.find(qn("w:shd"))
    if shd is None:
        shd = OxmlElement("w:shd")
        tc_pr.append(shd)
    shd.set(qn("w:fill"), fill)


def set_cell_margins(cell, top=70, start=80, bottom=70, end=80):
    tc_pr = cell._tc.get_or_add_tcPr()
    tc_mar = tc_pr.first_child_found_in("w:tcMar")
    if tc_mar is None:
        tc_mar = OxmlElement("w:tcMar")
        tc_pr.append(tc_mar)
    for side, value in (("top", top), ("start", start), ("bottom", bottom), ("end", end)):
        node = tc_mar.find(qn(f"w:{side}"))
        if node is None:
            node = OxmlElement(f"w:{side}")
            tc_mar.append(node)
        node.set(qn("w:w"), str(value))
        node.set(qn("w:type"), "dxa")


def keep_with_next(paragraph):
    paragraph.paragraph_format.keep_with_next = True


def add_page_number(section):
    footer = section.footer
    p = footer.paragraphs[0]
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r = p.add_run("第 ")
    set_font(r, 9)
    fld = OxmlElement("w:fldSimple")
    fld.set(qn("w:instr"), "PAGE")
    p._p.append(fld)
    r = p.add_run(" 页")
    set_font(r, 9)


def configure(doc):
    section = doc.sections[0]
    section.page_width = Inches(8.27)
    section.page_height = Inches(11.69)
    section.top_margin = Inches(0.68)
    section.bottom_margin = Inches(0.62)
    section.left_margin = Inches(0.72)
    section.right_margin = Inches(0.72)
    add_page_number(section)

    styles = doc.styles
    sizes = {"Normal": 10.5, "Title": 24, "Subtitle": 12, "Heading 1": 16, "Heading 2": 13, "Heading 3": 11}
    for name, size in sizes.items():
        style = styles[name]
        style.font.name = "Arial"
        style.font.size = Pt(size)
        style.font.color.rgb = RGBColor(0, 0, 0)
        style.element.get_or_add_rPr().rFonts.set(qn("w:eastAsia"), "Heiti SC")
        style.paragraph_format.space_after = Pt(6)
        style.paragraph_format.line_spacing = 1.15
        for border in style.element.xpath(".//w:pBdr"):
            border.getparent().remove(border)
    styles["Heading 1"].paragraph_format.page_break_before = True
    styles["Heading 1"].paragraph_format.keep_with_next = True
    styles["Heading 2"].paragraph_format.keep_with_next = True
    styles["Heading 3"].paragraph_format.keep_with_next = True
    styles["Title"].paragraph_format.space_after = Pt(18)


def add_title(doc):
    p = doc.add_paragraph(style="Title")
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.space_before = Pt(110)
    p.add_run("外部数据平台 2.0\n开发手册")
    p2 = doc.add_paragraph()
    p2.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r = p2.add_run("面向首次参与项目的开发、测试、发布与运维人员")
    set_font(r, 12)
    p3 = doc.add_paragraph()
    p3.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p3.paragraph_format.space_before = Pt(90)
    r = p3.add_run("版本：V1.0\n代码基线：2026-10-09\n运行基线：JDK 17 / Spring Boot 3.3.4 / Vue 3 / MySQL")
    set_font(r, 11)
    doc.add_page_break()


def p(doc, text="", bold_prefix=None):
    para = doc.add_paragraph()
    para.paragraph_format.space_after = Pt(5)
    para.paragraph_format.line_spacing = 1.15
    if bold_prefix and text.startswith(bold_prefix):
        r = para.add_run(bold_prefix)
        set_font(r, 10.5, True)
        r = para.add_run(text[len(bold_prefix):])
        set_font(r, 10.5)
    else:
        r = para.add_run(text)
        set_font(r, 10.5)
    return para


def bullet(doc, text, level=0):
    para = doc.add_paragraph(style="List Bullet" if level == 0 else "List Bullet 2")
    para.paragraph_format.space_after = Pt(2)
    for run in para.runs:
        set_font(run, 10)
    if not para.runs:
        set_font(para.add_run(text), 10)
    else:
        para.runs[0].text = text
    return para


STEP_COUNTER = 0


def numbered(doc, text):
    global STEP_COUNTER
    STEP_COUNTER += 1
    para = doc.add_paragraph()
    para.paragraph_format.space_after = Pt(2)
    set_font(para.add_run(f"{STEP_COUNTER}. "), 10, True)
    set_font(para.add_run(text), 10)
    return para


def code(doc, text):
    para = doc.add_paragraph()
    para.paragraph_format.left_indent = Inches(0.15)
    para.paragraph_format.right_indent = Inches(0.15)
    para.paragraph_format.space_before = Pt(3)
    para.paragraph_format.space_after = Pt(6)
    para.paragraph_format.keep_together = True
    ppr = para._p.get_or_add_pPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:fill"), "F3F3F3")
    ppr.append(shd)
    r = para.add_run(text)
    set_font(r, 8.5, False, "Menlo")
    r._element.get_or_add_rPr().rFonts.set(qn("w:eastAsia"), "PingFang SC")
    return para


def table(doc, headers, rows, widths=None, font_size=8.5):
    t = doc.add_table(rows=1, cols=len(headers))
    t.autofit = False
    t.style = "Table Grid"
    if widths is None:
        widths = [6.75 / len(headers)] * len(headers)
    hdr = t.rows[0]
    tr_pr = hdr._tr.get_or_add_trPr()
    tr_pr.append(OxmlElement("w:tblHeader"))
    tr_pr.append(OxmlElement("w:cantSplit"))
    for i, value in enumerate(headers):
        hdr.cells[i].width = Inches(widths[i])
        hdr.cells[i].text = str(value)
        shade(hdr.cells[i], "E7E7E7")
        set_cell_margins(hdr.cells[i])
        for para in hdr.cells[i].paragraphs:
            para.paragraph_format.space_after = Pt(0)
            for run in para.runs:
                set_font(run, font_size, True)
    for row in rows:
        cells = t.add_row().cells
        tr_pr = cells[0]._tc.getparent().get_or_add_trPr()
        tr_pr.append(OxmlElement("w:cantSplit"))
        for i, value in enumerate(row):
            cells[i].width = Inches(widths[i])
            cells[i].text = str(value)
            set_cell_margins(cells[i])
            for para in cells[i].paragraphs:
                para.paragraph_format.space_after = Pt(0)
                para.paragraph_format.line_spacing = 1.0
                for run in para.runs:
                    set_font(run, font_size)
    doc.add_paragraph().paragraph_format.space_after = Pt(1)
    return t


def heading(doc, text, level=1):
    global STEP_COUNTER
    STEP_COUNTER = 0
    return doc.add_heading(text, level=level)


def add_toc(doc):
    heading(doc, "目录", 1)
    p(doc, "打开 Word 后，在目录区域右键选择“更新域”，即可生成带页码的完整目录。")
    para = doc.add_paragraph()
    run = para.add_run()
    fld_char = OxmlElement("w:fldChar")
    fld_char.set(qn("w:fldCharType"), "begin")
    instr = OxmlElement("w:instrText")
    instr.set(qn("xml:space"), "preserve")
    instr.text = 'TOC \\o "1-3" \\h \\z \\u'
    separate = OxmlElement("w:fldChar")
    separate.set(qn("w:fldCharType"), "separate")
    text = OxmlElement("w:t")
    text.text = "目录将在 Word 中更新后显示"
    end = OxmlElement("w:fldChar")
    end.set(qn("w:fldCharType"), "end")
    run._r.extend([fld_char, instr, separate, text, end])


def build():
    doc = Document()
    configure(doc)
    add_title(doc)
    add_toc(doc)

    heading(doc, "1 文档目标与阅读方式")
    p(doc, "本手册以当前仓库实际代码为唯一基线，帮助第一次接触项目的开发人员理解工程边界，完成本地启动、配置化接口开发、独立数据应用开发、测试、上线、授权、回滚与排障。它不是产品宣传材料，也不把预留设计写成已完成功能。")
    table(doc, ["读者", "建议先读", "应掌握结果"], [
        ("首次入组开发", "第2～8章", "能导入工程、启动服务、定位请求链"),
        ("接口配置人员", "第9～12章", "能接入资源 API、开发标准服务并上线授权"),
        ("Java 开发人员", "第13～16章", "能新增独立数据应用和扩展安全算法"),
        ("测试人员", "第17～19章", "能完成分层验证和端到端验收"),
        ("发布运维人员", "第20～23章", "能部署、回滚、监控并判断故障边界"),
    ], [1.35, 2.0, 3.4])
    p(doc, "本手册中的“配置热更新”指发布后的执行计划和动态路由在运行中刷新，不表示任何 Java 代码修改都无需发布。")

    heading(doc, "2 十分钟理解整体架构")
    p(doc, "EDSP 2.0 是面向全行的外部数据接入与标准服务平台。调用方将 Service XML 请求发送到 ESB；Adapter 解析 service_id、service_sn 和业务体，转换为内部 JSON 后进入 Gateway；Gateway 完成认证、授权和动态路由；目标数据应用在自身 JVM 内执行字段转换、安全处理、上游调用和响应标准化；Adapter 最终将标准 JSON 封装为 Service XML 写回 ESB。Kafka 事件保持结构化 JSON。")
    table(doc, ["层次", "部署模块", "核心职责", "明确不做"], [
        ("接入层", "jsnx-edsp-esb-adapter", "Service XML 与内部 JSON 双向转换、模拟请求/响应队列", "不做业务字段映射"),
        ("统一入口", "jsnx-edsp-data-gateway", "认证、API 授权、入口日志、apiCode+Method+Path 动态路由", "不执行上游业务编排"),
        ("控制面", "jsnx-edsp-data-admin", "资源、接口、映射、发布、授权、计费规则和发布快照", "不承接生产请求转换"),
        ("数据面", "每个 jsnx-edsp-data-* 应用", "本地计划、请求转换、安全、上游调用、响应转换", "数据应用之间不互相依赖"),
        ("观测面", "jsnx-edsp-data-observability", "调用日志、节点日志、监控与计量明细", "异步失败不得阻断主链路"),
        ("可选能力", "jsnx-edsp-capability-runtime", "复杂或高变化能力的远程执行预留", "普通调用不经过该服务"),
    ], [0.8, 1.55, 2.7, 1.7], 8)
    if FLOW_IMAGE.exists():
        doc.add_picture(str(FLOW_IMAGE), width=Inches(6.65))
        cap = doc.add_paragraph("图 2-1  当前实现的完整请求、控制与日志链路")
        cap.alignment = WD_ALIGN_PARAGRAPH.CENTER
        for r in cap.runs:
            set_font(r, 9)

    heading(doc, "3 仓库结构与 IDE 导入")
    code(doc, "demo/\n├── jsnx-edsp-platform-backend/   # 后端 Maven 聚合工程\n├── jsnx-edsp-platform-frontend/  # 平台 Vue 3 前端\n├── jsnx-edsp-vendor-backend/     # 上游供应商模拟后端\n├── jsnx-edsp-vendor-frontend/    # 上游供应商模拟前端\n└── jsnx-edsp-deployment/         # 启停脚本、日志、PID、文档与样例")
    numbered(doc, "在 IntelliJ IDEA 中直接打开 jsnx-edsp-platform-backend/pom.xml；Project SDK 与 Maven Runner 均选择 JDK 17。")
    numbered(doc, "前端建议在 VS Code/WebStorm 单独打开 jsnx-edsp-platform-frontend，不要把 node_modules 加入 Java 工程。")
    numbered(doc, "供应商模拟工程与平台工程独立，避免把模拟代码误打入平台生产包。")
    numbered(doc, "部署工程只放运维资产，不承载业务 Java 源码。")
    p(doc, "当前 Git 根目录是 demo。所有代码和正式文档变更应在该仓库中提交；编辑器临时文件和 Word 锁文件不应提交。")

    heading(doc, "4 技术栈与本地前置条件")
    table(doc, ["类别", "当前基线", "开发要求"], [
        ("Java", "JDK 17", "不得使用 JDK 21 专属语法或 API"),
        ("后端", "Spring Boot 3.3.4 / Spring Cloud 2023.0.3", "使用 Jakarta API，保持父 POM 统一版本"),
        ("网关", "Spring Cloud Gateway", "响应式链路不得执行阻塞 JDBC 或长时间同步任务"),
        ("配置治理", "Nacos / Sentinel 依赖已保留，本地默认关闭", "生产启用前需联调命名空间、鉴权与降级策略"),
        ("数据库", "MySQL，库名 edsp_demo", "Schema 由 Admin 的 db/schema.sql 管理"),
        ("前端", "Vue 3 + TypeScript + Element Plus + Vite", "Node/npm 版本遵循 package-lock"),
        ("部署", "普通 JVM + F5/VIP，无容器/K8s", "每个服务独立端口、日志、PID 与发布单元"),
    ], [1.05, 2.5, 3.2])
    code(doc, "java -version\nmvn -version\nnode -v\nnpm -v\nmysql -h 127.0.0.1 -P 3306 -uroot")

    heading(doc, "5 后端模块责任矩阵")
    heading(doc, "5.1 公共组件", 2)
    table(doc, ["模块", "实际职责", "主要使用方", "部署形态"], [
        ("jsnx-edsp-contracts", "稳定的跨模块 DTO 与服务契约", "SDK、数据应用、能力服务", "普通 JAR"),
        ("jsnx-edsp-client-sdk", "数据应用本地计划加载、上游客户端、标准执行器和发布入口", "所有独立数据应用", "普通 JAR"),
        ("jsnx-edsp-plan-core", "PlanSnapshot、映射计划与安全计划装载", "client-sdk、可选能力服务", "普通 JAR"),
        ("jsnx-edsp-transform-core", "字段路径读取、写入和转换规则", "client-sdk", "普通 JAR"),
        ("jsnx-edsp-crypto-core", "AES/SM2/SM4 等加解密与密钥引用解析", "client-sdk", "普通 JAR"),
        ("jsnx-edsp-signature-core", "HMAC/RSA/国密签名能力", "client-sdk", "普通 JAR"),
        ("jsnx-edsp-response-core", "统一响应结构与返回码处理", "执行链", "普通 JAR"),
        ("jsnx-edsp-log-core", "Trace、脱敏、异步日志缓冲与投递", "Gateway、观测相关模块", "普通 JAR"),
        ("jsnx-edsp-platform-core", "Admin 领域模型、配置、发布、授权与数据库服务", "Admin、Observability", "普通 JAR"),
    ], [1.7, 2.65, 1.45, 0.95], 7.7)
    p(doc, "公共组件不是九个都让数据应用直接依赖。数据应用 POM 只直接声明 contracts 与 client-sdk，后者按实现需要传递依赖底层 core。微服务之间禁止通过 Maven 依赖彼此实现。")
    heading(doc, "5.2 独立部署服务", 2)
    table(doc, ["服务", "默认端口", "主要入口", "持久化/依赖"], [
        ("Gateway", "8080", "所有发布后的标准 API", "本地不可变路由快照；同步调用 Admin 鉴权"),
        ("Admin", "8081", "/api/** 与内部路由/计划接口", "MySQL"),
        ("Observability", "8083", "调用日志、监控、事件接收", "MySQL；本地 HTTP 模拟 Kafka"),
        ("ESB Adapter", "8084", "/esb/queue/request", "内存模拟队列；调用 Admin 与 Gateway"),
        ("Capability Runtime", "8091", "/internal/capability/**", "可选，不在普通链路"),
        ("Enterprise", "8181", "/internal/adapter/published", "Admin 计划、供应商接口"),
        ("Credit", "8182", "/internal/adapter/published", "Admin 计划、供应商接口"),
        ("Antifraud", "8183", "/internal/adapter/published", "Admin 计划、供应商接口"),
        ("LHZX", "8191", "/internal/adapter/published", "Admin 计划、供应商接口"),
    ], [1.55, 0.75, 1.85, 2.6], 8)

    heading(doc, "6 前端与供应商模拟工程")
    table(doc, ["工程", "职责", "开发注意事项"], [
        ("jsnx-edsp-platform-frontend", "平台管理界面：资源、标准服务、发布、授权、日志与监控", "API 集中在 src/api.ts；主页面当前集中在 App.vue，新增功能应逐步拆组件"),
        ("jsnx-edsp-vendor-backend", "可配置资源与 API，并按数据库真实记录返回命中/未命中", "仅用于本地联调，不应成为平台生产依赖"),
        ("jsnx-edsp-vendor-frontend", "供应商侧配置和数据维护界面", "端口 5190，与平台 5173 独立"),
    ], [1.75, 2.35, 2.65])

    heading(doc, "7 主请求执行链")
    numbered(doc, "调用系统向 ESB 发送 Service XML，至少携带 service_sn、service_id、requester_id、service_time、version_id 和 Service_Body/request。")
    numbered(doc, "ESB Adapter 将 XML 解析为内部 JSON；以 service_id 作为平台标准服务编码，通过 Admin 解析已发布的服务路径。")
    numbered(doc, "Adapter 向 Gateway 发送请求，并设置 X-Api-Code、X-App-Id、X-Api-Key 和 Trace 头。")
    numbered(doc, "Gateway 校验本地凭证，再向 Admin 检查调用系统、接口、有效期和日配额授权。")
    numbered(doc, "Gateway 用 X-Api-Code + HTTP Method + URL Path 在本地不可变路由表中精确匹配。")
    numbered(doc, "Gateway 转发到 runtime_url/internal/adapter/published，并附带 X-Route-Version 与 X-Data-Application。")
    numbered(doc, "数据应用选择与 Gateway 版本一致的 PlanSnapshot，完成请求映射和安全处理。")
    numbered(doc, "通用上游客户端按发布计划调用供应商；当前通用执行器以 POST JSON 为主要实现。")
    numbered(doc, "数据应用返回内部标准 JSON；ESB Adapter 将其封装为 Service XML，并写入 ESB 响应队列。Kafka 事件仍为结构化 JSON。")
    numbered(doc, "数据应用从可配置 vendorCodePath 提取供应商码值，映射统一状态，再执行响应字段转换。")
    numbered(doc, "统一 JSON 经 Gateway、Adapter 和响应队列返回调用系统；不是 XML 响应。")

    heading(doc, "8 路由和执行计划为何能不停机更新")
    table(doc, ["对象", "持久化来源", "运行时副本", "更新机制", "失败行为"], [
        ("发布路由", "published_route + data_source.runtime_url", "Gateway AtomicReference<Snapshot>", "上线主动刷新；每5秒修复拉取", "保留 last-good 快照"),
        ("执行计划", "release_plan_snapshot", "各数据应用按接口 AtomicReference<PlanSnapshot>", "发布通知；首次请求/版本不符时拉取", "版本不符拒绝，不静默跑旧契约"),
        ("开发草稿", "api_interface 及配置子表", "无生产执行副本", "页面保存", "不影响线上版本"),
    ], [1.05, 2.05, 1.75, 1.3, 1.1], 7.7)
    p(doc, "内存不是唯一数据源。Gateway 或数据应用进程重启后，会从 Admin/MySQL 中的已发布路由和快照恢复，因此不需要为了恢复配置而重新编译。内存副本用于高性能读和请求版本隔离。")
    code(doc, "// 数据应用的核心语义（简化）\nPlanSnapshot selected = localPlanRuntime.current(apiCode, expectedRouteVersion);\n// 新发布计划以 AtomicReference 原子替换；在途请求仍持有旧对象。")

    heading(doc, "9 先判断：配置开发还是 Java 开发")
    table(doc, ["需求变化", "改代码", "重启/部署", "正确处理"], [
        ("既有数据应用下新增接口", "否", "否", "页面建资源 API、标准服务并发布"),
        ("供应商 URL 或接口级超时变化", "否", "否", "修改草稿并发布新版本"),
        ("请求/响应字段和枚举映射", "否", "否", "配置映射并发布"),
        ("返回码字段路径或码值变化", "否", "否", "配置 vendorCodePath 与码值映射"),
        ("已注册算法的参数/密钥引用变化", "否", "通常否", "调整配置，密钥由受控环境注入"),
        ("新 Java 算法或新协议实现", "是", "受影响应用滚动发布", "实现 SPI/客户端并完成安全评审"),
        ("新增独立数据应用", "是，一次性骨架", "只启动新应用", "复制骨架、注册 runtime_url、发布接口"),
        ("SDK/core 缺陷修复", "是", "所有受影响应用分批滚动", "先兼容修复、灰度、再扩大"),
    ], [2.2, 0.65, 1.2, 2.7], 8)

    heading(doc, "10 本地构建、启动与停止")
    code(doc, "cd /Users/tommy/Documents/外部数据/demo/jsnx-edsp-deployment\n# 外部数据平台独立启停\n./bin/start-demo.sh\n./bin/stop-demo.sh\n# 供应商模拟平台独立启停\n./bin/start-vendor.sh\n./bin/stop-vendor.sh\n# 两套平台均已启动后执行跨系统联调\n./bin/run-full-demo.sh")
    p(doc, "start-demo.sh 只启动外部数据平台，start-vendor.sh 只启动供应商模拟平台，两套平台的启动、停止和失败处理互不联动。start-platform.sh 会检查 JDK 17、MySQL、Maven、Node、固定端口，并在无后端运行时构建。检测到平台后端已运行时会跳过数据库初始化和 Maven 构建，避免覆盖正在加载的 JAR。")
    table(doc, ["脚本", "用途", "是否重置数据"], [
        ("start-demo.sh / stop-demo.sh", "仅外部数据平台启停", "启动时空库建表，不主动删数据"),
        ("start-vendor.sh / stop-vendor.sh", "仅供应商模拟平台启停", "启动时空库建表，不主动删数据"),
        ("reload-integration-stack.sh", "重建并重启 Admin、数据应用、Adapter、Gateway 等联调栈", "否"),
        ("restart-admin.sh", "仅重启 Admin", "否"),
        ("reset-test-data.sh", "清理测试业务数据", "是，慎用"),
        ("verify-demo.sh", "两套平台已独立启动后的跨系统验收", "可能产生验证记录"),
    ], [2.2, 3.15, 1.4], 8.2)
    code(doc, "# 单独构建一个数据应用及其依赖\ncd ../jsnx-edsp-platform-backend\nexport JAVA_HOME=$(/usr/libexec/java_home -v 17)\nmvn -pl data-applications/jsnx-edsp-data-source-lhzx -am clean package -DskipTests")

    heading(doc, "11 配置化新增接口：准备阶段")
    p(doc, "开始前准备供应商接口文档、真实但脱敏的请求/响应样例、成功/未命中/失败码值、超时要求、认证与签名规则、所属数据源以及行内标准接口契约。没有这些输入时，不要先在页面随意创建字段。")
    table(doc, ["阶段", "产物", "完成标准"], [
        ("资源提供商", "稳定 provider_code 和管理资料", "供应商可识别，联系人与合同边界明确"),
        ("数据源", "source_code、endpoint_url、timeout、application_name、runtime_url", "目标数据应用已部署并健康"),
        ("资源 API", "供应商方法、路径、请求/响应样例", "保存为 READY，可真实联调"),
        ("标准服务", "interface_code、对外路径、标准契约、映射、安全与码值", "配置校验通过"),
        ("发布", "release_plan_snapshot 与 published_route", "Gateway 和数据应用获得新版本"),
        ("授权", "client + authorization", "有效期和配额均满足"),
    ], [1.1, 3.25, 2.4], 8)

    heading(doc, "12 配置化接口开发：逐步操作")
    heading(doc, "12.1 接入供应商能力", 2)
    numbered(doc, "在资源方管理建立资源提供商，编码创建后保持稳定。")
    numbered(doc, "建立数据源，填写供应商基础地址 endpoint_url、默认超时、数据应用名和 runtime_url。")
    numbered(doc, "在上游能力接入创建资源 API 草稿，填写供应商路径、请求方法、请求样例与响应样例。")
    numbered(doc, "保存上游配置。只有已保存/READY 的资源 API 才会出现在标准服务开发中；草稿不展示。")
    numbered(doc, "使用“验证上游接口”直接验证供应商契约。这里尚未应用平台标准映射，入参必须是供应商原始字段。")
    heading(doc, "12.2 建立平台标准服务", 2)
    numbered(doc, "选择已保存资源 API 创建标准服务，填写唯一 interface_code、对外 service_path 和方法。")
    numbered(doc, "定义平台交付给行内调用方的标准请求和标准响应样例。")
    numbered(doc, "配置请求映射：行内标准字段是来源，供应商请求字段是目标。")
    numbered(doc, "配置响应映射：供应商返回字段是来源，行内标准响应字段是目标。")
    numbered(doc, "配置 vendorCodePath，例如 result.code、status 或 header.retCode；不同接口可以不同。")
    numbered(doc, "配置供应商原始码值到 standardCode、resultStatus、billable 的映射。")
    numbered(doc, "按需配置签名/加密算法和密钥引用，不在数据库中保存明文密钥。")
    numbered(doc, "保存标准服务草稿并执行配置校验。草稿链路测试是补充验证，不与上线形成强依赖。")
    heading(doc, "12.3 透传模式", 2)
    p(doc, "请求方向没有任何映射行时，执行器保留原请求对象；响应方向没有映射行时，保留供应商业务对象。无需创建一对一映射行。只要某方向启用了映射，该方向就按配置结果生成对象，未配置字段不会自动透传。")
    heading(doc, "12.4 复杂 JSON", 2)
    p(doc, "对象嵌套可使用点路径，例如 company.legalPerson.name。数组处理必须以当前路径解析器的实际能力和测试为准，不要把完整 JSONPath 表达式支持作为默认假设。数组、动态键、多层异构结构或 XML 特殊命名空间应增加合同测试；超出通用转换能力时使用自定义扩展或专用客户端。")

    heading(doc, "13 字段映射、码值与安全配置")
    table(doc, ["配置", "输入方向", "示例", "常见错误"], [
        ("直接映射", "请求：标准→供应商；响应：供应商→标准", "companyCode → query_key", "把响应方向写反"),
        ("枚举转换", "来源原值→目标标准值", "供应商 1 → 标准 M", "左右值颠倒或未保存抽屉"),
        ("默认值", "来源缺失时补充", "query_reason=业务查询", "把默认值误当字段映射"),
        ("返回码路径", "从供应商完整响应读取", "result.code / status", "硬编码认为所有接口都是 result.code"),
        ("结果状态", "供应商码值→HIT/MISS/ERROR", "0000→HIT，1001→MISS", "把 HTTP 200 等同于业务命中"),
        ("参与计量", "码值映射结果", "仅 HIT 计量", "将异步明细当作最终财务账"),
    ], [1.0, 1.6, 1.75, 2.4], 8)
    p(doc, "通用安全算法包括 AES-256-GCM、HMAC-SHA256、SM4-GCM、SM2、HMAC-SM3、SM2-SM3 和 RSA-SHA256。页面算法列表来自后台注册表；新增 Java 算法必须实现 SecurityAlgorithm SPI、注册扩展 JAR、补充测试并滚动发布受影响数据应用。")
    code(doc, "# 密钥引用示例（页面只保存引用）\nconfig://edsp.security.keys.lhzx-encryption\nconfig://edsp.security.keys.lhzx-signing\n\n# 本地通过环境变量注入；生产应接行内密钥系统/受控 Nacos\nexport EDSP_LHZX_ENCRYPTION_KEY='<base64-secret>'\nexport EDSP_LHZX_SIGNING_KEY='<base64-secret>'")

    heading(doc, "14 新增独立数据应用")
    p(doc, "只有在需要独立故障边界、独立容量、独立发布节奏或特殊协议实现时，才新增数据应用。一个数据应用可承载同一隔离域下多个资源 API，不要求每个接口一个 JVM，也不要求每个业务新增 Router 应用。")
    numbered(doc, "复制最接近的骨架，例如 data-applications/jsnx-edsp-data-source-lhzx，并重命名目录、artifactId、启动类包名与 spring.application.name。")
    numbered(doc, "POM 直接依赖 jsnx-edsp-contracts、jsnx-edsp-client-sdk、spring-boot-starter-web、actuator 和所需治理依赖。禁止依赖另一个数据应用。")
    numbered(doc, "在父 pom.xml 的 modules 中登记新模块；端口只用于本地，生产通过 SERVER_PORT 注入。")
    numbered(doc, "配置 edsp.admin-url、edsp.observability-url 与受控密钥引用。")
    numbered(doc, "保留 SDK 自动提供的 POST /internal/adapter/published 入口。Gateway 不需要新增固定 RouteDefinition。")
    numbered(doc, "若无特殊逻辑，不编写业务 Controller；通过 Admin 页面配置发布计划即可。")
    numbered(doc, "若存在特殊算法/协议，通过 SPI 或应用内专用实现扩展，并保证通用接口仍可调用。")
    numbered(doc, "构建、启动并确认 /actuator/health；将 F5/VIP 地址写入 data_source.runtime_url。")
    numbered(doc, "配置并发布接口，检查 Admin 的 published_route 与 Gateway 路由快照，再授权测试。")
    code(doc, "# application.yml 最小示例\nserver.port: ${SERVER_PORT:8201}\nspring:\n  application.name: jsnx-edsp-data-source-credit-v2\n  config.import: ${SPRING_CONFIG_IMPORT:optional:nacos:jsnx-edsp-data-source-credit-v2.yaml}\nedsp:\n  admin-url: ${ADMIN_URL:http://127.0.0.1:8081}\n  observability-url: ${OBSERVABILITY_URL:http://127.0.0.1:8083}")
    code(doc, "# 构建新应用及依赖\nmvn -pl data-applications/jsnx-edsp-data-source-credit-v2 -am clean package\n\n# 启动后只登记该应用，不重启其他数据应用\njava -jar target/jsnx-edsp-data-source-credit-v2-2.0.0-SNAPSHOT.jar")

    heading(doc, "15 自定义 Java 能力开发")
    heading(doc, "15.1 何时需要自定义", 2)
    bullet(doc, "供应商使用平台未注册的加密、签名或验签算法。")
    bullet(doc, "上游协议不是当前通用 POST JSON，例如专用 SOAP、文件交换或特殊双向认证。")
    bullet(doc, "字段转换包含复杂数组聚合、跨字段计算或需外部字典。")
    bullet(doc, "上游交互需要多步会话、Token 刷新或强状态机。")
    heading(doc, "15.2 设计原则", 2)
    p(doc, "优先实现稳定 SPI，由数据应用选择实现；不要把供应商专属逻辑塞入 Gateway。自定义实现必须有算法标识、参数 Schema、密钥引用规则、线程安全声明、超时与异常契约。新增实现不能破坏已有标识的语义。")
    table(doc, ["扩展类型", "放置位置", "发布影响"], [
        ("通用新算法", "独立扩展 JAR 或对应 core 的 SPI 实现", "使用该算法的数据应用滚动发布"),
        ("单供应商专用算法", "对应独立数据应用", "仅该应用发布"),
        ("高变化复杂公共能力", "评估 capability-runtime 远程服务", "增加网络依赖，需独立 SLA"),
        ("入口认证/路由", "Gateway 专用扩展", "Gateway 集群滚动发布"),
    ], [1.45, 2.8, 2.5])

    heading(doc, "16 关键代码导航")
    table(doc, ["要理解的行为", "代码入口"], [
        ("Gateway 动态路由快照", "platform-services/jsnx-edsp-data-gateway/.../PublishedRouteRegistry.java"),
        ("Gateway 目标 URI 改写", ".../DynamicDataApplicationRoutingFilter.java"),
        ("调用方认证与授权", ".../ExternalApiAuthFilter.java"),
        ("Gateway 异步日志", ".../GatewayLogGlobalFilter.java 与 jsnx-edsp-log-core"),
        ("发布、下线、快照、刷新", "common-components/jsnx-edsp-platform-core/.../InterfaceService.java"),
        ("Admin HTTP API", "platform-services/jsnx-edsp-data-admin/.../ApiController.java"),
        ("数据应用本地计划", "common-components/jsnx-edsp-client-sdk/.../LocalPlanRuntime.java"),
        ("统一执行器", ".../StandardDataApplicationExecutor.java"),
        ("数据应用发布入口", ".../PublishedDataApplicationController.java"),
        ("数据库结构", "platform-services/jsnx-edsp-data-admin/src/main/resources/db/schema.sql"),
        ("前端 API", "jsnx-edsp-platform-frontend/src/api.ts"),
        ("前端主工作台", "jsnx-edsp-platform-frontend/src/App.vue 与 resource-workbench.css"),
    ], [2.05, 4.7], 8)

    heading(doc, "17 测试策略")
    table(doc, ["层级", "必须覆盖", "通过标准"], [
        ("单元测试", "字段路径、枚举、码值、安全算法、路由匹配", "边界、空值和异常分支均覆盖"),
        ("契约测试", "标准请求/响应与供应商请求/响应样例", "发布快照生成结果稳定"),
        ("应用测试", "expectedVersion、应用归属、上游超时与错误", "旧版本不误执行；错误可追踪"),
        ("Gateway 测试", "认证、授权、三元组路由、Admin 不可用", "拒绝码准确；last-good 路由仍可读"),
        ("集成测试", "Admin+Gateway+数据应用+供应商", "真实 HTTP 调用和落库正确"),
        ("ESB 全链路", "Service XML→Adapter→Gateway→应用→供应商→Adapter→Service XML", "service_sn 一致且每节点可定位"),
        ("容量与故障", "峰值、并发、慢上游、Kafka/日志服务故障", "主链路 SLA 和隔离策略满足"),
    ], [1.1, 3.25, 2.4], 8)
    code(doc, "# 后端完整测试\ncd jsnx-edsp-platform-backend\nmvn test\n\n# 单模块及依赖测试\nmvn -pl common-components/jsnx-edsp-platform-core -am test\n\n# 前端静态构建\ncd ../jsnx-edsp-platform-frontend\nnpm ci\nnpm run build")

    heading(doc, "18 三类联调与 Postman 测试")
    table(doc, ["测试类型", "链路", "适用时间", "是否需要发布/授权"], [
        ("上游接口验证", "数据应用/管理端→供应商", "资源 API 保存后", "不需要平台发布授权"),
        ("草稿链路测试", "标准请求→草稿计划→供应商→标准响应", "标准服务开发中", "不要求上线；是可选补充"),
        ("线上入口验收", "XML→ESB→Gateway→线上计划", "发布与授权后", "必须已上线且已授权"),
    ], [1.25, 2.35, 1.5, 1.65], 8)
    p(doc, "Postman 直接调用 Gateway 时，URL 必须等于已发布 service_path，HTTP 方法必须一致，X-Api-Code 必须等于已发布接口编码。任意一项不一致都会 404。")
    code(doc, "POST http://127.0.0.1:8080/api/v1/lhzx-query-01/01\nContent-Type: application/json\nX-App-Id: POSTMAN_TEST\nX-Api-Key: demo-secret-key\nX-Api-Code: LHZX_QUERY_01_SERVICE_01\nX-Trace-Id: POSTMAN-LHZX-001\n\n{\n  \"query_key\": \"DEMO-001\",\n  \"query_reason\": \"业务查询\"\n}")
    p(doc, "若从 Adapter 测试，则 POST /esb/queue/request、Content-Type 为 application/xml，并使用 XML body。当前本地请求-响应队列等待 10 秒，Admin/Gateway 下游超时均被限制在此时间内。")

    heading(doc, "19 发布、版本、下线与授权")
    p(doc, "开发草稿和线上版本是两个状态轴。保存草稿不应自动增加线上版本；发布时生成不可变 release_plan_snapshot，并更新 published_route。接口下线删除生产路由，但保留开发草稿和历史快照。下线后仍可继续编辑，再发布新版本或恢复旧快照。")
    table(doc, ["动作", "草稿", "线上路由", "版本/历史", "是否影响调用"], [
        ("保存", "更新", "不变", "不创建线上版本", "否"),
        ("配置校验", "只读校验", "不变", "记录校验结果", "否"),
        ("草稿测试", "使用当前草稿", "不变", "不要求成为发布前置", "否"),
        ("直接上线", "保留", "新增/切换", "生成新快照和发布记录", "新请求使用新版本"),
        ("下线", "保留可编辑", "删除", "保留历史", "停止线上入口"),
        ("恢复历史", "不等于覆盖草稿", "按选择快照恢复", "新增生命周期记录", "新请求切回选中契约"),
    ], [0.9, 1.2, 1.15, 2.15, 1.35], 8)
    p(doc, "接口上线后，在 API 访问授权中先维护调用系统 api_client，再按机构/系统、有效期和日配额批量授权。Gateway 的固定 demo-secret-key 仅代表本地凭证校验，生产必须替换为行内 IAM/密钥管理机制。")

    heading(doc, "20 生产部署与扩容")
    p(doc, "本项目不依赖 Docker 或 Kubernetes。每个服务以普通 JVM 部署到行内服务器，使用 F5/VIP 暴露稳定地址。新增实例由发布平台/运维脚本启动，再加入 VIP 池；页面中的 instance_count 只是元数据，不会自动创建进程。")
    table(doc, ["服务", "建议最小部署", "负载方式", "状态与注意事项"], [
        ("Gateway", "至少2实例", "入口 F5/VIP", "各实例本地路由快照；同步授权依赖 Admin"),
        ("Admin", "至少2实例", "管理/内部 VIP", "共享 MySQL；发布操作需保证幂等"),
        ("ESB Adapter", "按队列分区与并发", "ESB 消费组", "本地模拟队列必须替换为真实 ESB"),
        ("数据应用", "每个隔离域至少2实例", "每应用独立 F5/VIP", "runtime_url 指向 VIP，不写单机地址"),
        ("Observability", "至少2实例", "事件消费组", "日志失败可丢，但不能阻断 Gateway"),
        ("MySQL", "行内高可用方案", "数据库代理/VIP", "需备份、恢复演练与变更审计"),
    ], [1.25, 1.45, 1.5, 2.55], 8)
    p(doc, "日调用量 1000 万约为平均 116 次/秒，只能用于粗略规模判断。容量设计必须以峰值 QPS、P99、并发连接、报文大小、上游超时比例和日志吞吐进行压测。")

    heading(doc, "21 滚动发布与回滚")
    numbered(doc, "配置变更：保存草稿、校验、可选测试、发布；观察 Gateway 路由版本和数据应用计划版本；无需重启。")
    numbered(doc, "数据应用代码变更：从 VIP 摘除一个实例，停止、替换 JAR、启动、健康检查、链路验证，再加入 VIP；逐实例执行。")
    numbered(doc, "Gateway 代码变更：确保至少一个实例服务，按同样方式逐实例滚动。")
    numbered(doc, "配置回滚：选择已验证的历史发布快照恢复，不通过手工 SQL 拼接。")
    numbered(doc, "代码回滚：保留上一版本 JAR 与配置，摘流后回退；数据库变更必须向前兼容并提供回退脚本。")
    p(doc, "SDK/core 缺陷修复会影响所有使用该依赖的数据应用。应保持契约向后兼容，先在一个低风险应用灰度，再分批升级；不要一次性同时重启 50 个应用。对高变化能力，应评估远程 capability 服务，但要接受额外网络故障点。")

    heading(doc, "22 日志、监控与故障隔离")
    p(doc, "Gateway 日志使用有界队列，Kafka/观测服务不可用时允许丢失日志，不能拖垮主请求。这个策略满足“可丢日志、不可挂 Gateway”，但日志不完整时不能用于最终财务结算。数据应用事件执行器仍需在生产前确认有界化。")
    table(doc, ["观测对象", "关键字段/指标", "定位方法"], [
        ("入口", "traceId、apiCode、clientId、path、HTTP code", "先确认认证、授权、三元组路由"),
        ("数据应用", "application、routeVersion、planVersion", "判断归属或版本是否不一致"),
        ("转换", "脱敏前后结构、规则标识", "按节点入参与出参对比"),
        ("上游", "URL、HTTP 状态、业务码、耗时", "区分网络失败与业务未命中"),
        ("响应", "standardCode、resultStatus、billable", "检查码值路径和映射"),
        ("异步日志", "队列深度、丢弃数、发送失败", "不能只看业务成功率"),
    ], [1.05, 3.05, 2.65], 8)

    heading(doc, "23 常见故障排查")
    table(doc, ["现象", "优先检查", "处理"], [
        ("Gateway 404", "apiCode、Method、Path 与 published_route", "三项必须同时匹配；刷新路由并检查重复"),
        ("Gateway 403", "api_client、api_authorization、有效期、日配额", "补授权，不要绕过过滤器"),
        ("Gateway 503 未登记地址", "data_source.runtime_url", "登记目标应用 F5/VIP 并确认健康"),
        ("授权中心暂不可用", "Admin 健康与 Gateway→Admin 网络", "恢复 Admin；当前鉴权不是完全自治"),
        ("执行计划版本不一致", "published_route.route_version 与发布快照", "刷新对应应用；禁止强行跑旧计划"),
        ("供应商缺少必填字段", "请求方向映射、透传、标准样例", "确认来源/目标方向；检查默认值"),
        ("上游 HTTP 400", "节点中的实际上游请求", "按供应商原始契约修正映射或样例"),
        ("返回始终 ERROR", "vendorCodePath 和 response_code_mapping", "按真实响应路径读取业务码"),
        ("ESB 等待超时", "Adapter、Gateway、数据应用、供应商日志", "按 traceId 找最末成功节点"),
        ("页面 404 /api/resource-apis", "Gateway admin 路由与 Admin 版本", "确认 Gateway/Admin 同一代码版本"),
        ("端口占用", "lsof -nP -iTCP:<port> -sTCP:LISTEN", "停止正确 PID；不要执行 java PID ..."),
        ("Observability 健康超时", "服务日志与 Logback 类依赖", "修复当前 ThrowableProxy 类加载问题后再验收"),
    ], [1.8, 2.5, 2.45], 7.7)

    heading(doc, "24 数据库核心表导航")
    table(doc, ["表", "用途", "关键关系/说明"], [
        ("data_provider", "资源提供商主数据", "一对多 data_source"),
        ("provider_account", "资源方账号与权限资料", "当前不是完整生产 IAM"),
        ("data_source", "数据源、供应商地址、超时、应用和 runtime_url", "路由到独立应用的基础"),
        ("resource_api", "供应商原始 API 契约", "READY 后供标准服务引用"),
        ("api_interface", "平台标准服务草稿与当前状态", "interface_code 唯一"),
        ("field_mapping", "请求/响应字段转换", "按接口和方向区分"),
        ("api_security_policy", "加密、签名和密钥引用", "禁止明文密钥"),
        ("response_code_mapping", "供应商码值到标准状态", "结合可配置 vendorCodePath"),
        ("release_plan_snapshot", "不可变发布执行计划", "重启恢复与历史回滚来源"),
        ("published_route", "当前线上动态路由", "接口、方法、Path、版本和数据源"),
        ("release_record", "发布历史", "区分每次上线版本"),
        ("interface_lifecycle_record", "上线、下线、恢复等生命周期", "审计入口"),
        ("api_client", "调用系统", "授权外键来源"),
        ("api_authorization", "API 授权、有效期与配额", "Gateway 实时校验"),
        ("billing_rule / usage_record", "计量规则与调用明细", "异步日志不等于最终结算"),
        ("api_call_node_log", "链路节点入出参与状态", "必须脱敏"),
    ], [1.7, 2.6, 2.45], 7.6)

    heading(doc, "25 当前商用差距与上线前必做")
    table(doc, ["事项", "当前状态", "商用前要求"], [
        ("身份认证", "admin/admin123 与共享 demo-secret-key", "接入行内 IAM、密钥轮换、最小权限和审计"),
        ("控制面依赖", "Gateway 每次授权同步调用 Admin；Adapter 可能查 Admin", "授权本地快照/缓存与失效机制，明确降级策略"),
        ("ESB", "内存模拟请求响应队列", "对接真实 ESB 队列、重试、去重、死信与关联 ID"),
        ("日志传输", "HTTP 模拟 Kafka；允许丢失", "真实 Kafka、有界缓冲、监控丢弃、敏感信息治理"),
        ("观测", "部分节点耗时为模拟值；存在 Logback 类问题", "真实埋点、修复依赖、压测日志链"),
        ("上游协议", "通用执行器主要为 POST JSON", "方法/协议能力与页面配置保持一致"),
        ("数据库", "schema.sql 启动式结构", "Flyway/Liquibase、变更评审、备份与回滚演练"),
        ("容量", "本地功能验证", "峰值压测、故障注入、线程池/连接池与超时预算"),
        ("安全", "支持算法和引用机制", "国密合规验证、KMS/HSM、脱敏、双人复核"),
    ], [1.1, 2.7, 2.95], 7.7)

    heading(doc, "26 开发与发布检查清单")
    heading(doc, "26.1 提交代码前", 2)
    for item in [
        "已判断配置开发还是 Java 开发，没有把供应商逻辑放进 Gateway。",
        "JDK 17 编译通过，相关单元测试和合同测试通过。",
        "没有提交密钥、真实敏感报文、PID、日志、node_modules 或 Word 临时文件。",
        "数据库变更向前兼容，并有升级与回退说明。",
        "异常均带 traceId，日志字段已脱敏，异步失败不阻塞主链路。",
        "README、接口样例和本手册相关章节已同步。",
    ]:
        bullet(doc, "□ " + item)
    heading(doc, "26.2 接口上线前", 2)
    for item in [
        "资源 API 已保存，供应商成功/未命中/失败场景至少各验证一次。",
        "请求与响应映射方向正确；透传方向没有多余一对一映射。",
        "vendorCodePath 按实际响应配置，码值映射覆盖未知值。",
        "安全算法、密钥引用、超时和脱敏策略已复核。",
        "配置校验通过；草稿链路测试按风险执行但不作为硬编码上线依赖。",
        "上线后 published_route、Gateway 路由版本、数据应用计划版本一致。",
        "调用系统已授权，Postman/ESB 线上入口验收通过。",
    ]:
        bullet(doc, "□ " + item)
    heading(doc, "26.3 生产发布前", 2)
    for item in [
        "变更窗口、负责人、回滚触发条件和上一版本制品已确认。",
        "普通 JVM 与 F5/VIP 滚动步骤已演练，不依赖容器平台。",
        "峰值容量、P99、慢上游、Admin/Kafka/观测故障均已验证。",
        "监控、告警、值班、日志保留与数据安全策略已生效。",
    ]:
        bullet(doc, "□ " + item)

    heading(doc, "27 新手第一周建议路径")
    table(doc, ["天", "目标", "练习"], [
        ("第1天", "启动并理解链路", "启动全栈，分别访问 5173/5190，查看各服务日志"),
        ("第2天", "掌握资源与标准服务", "创建一个资源 API 和透传标准服务，不发布"),
        ("第3天", "掌握映射与码值", "增加请求、响应、枚举和自定义返回码路径"),
        ("第4天", "掌握发布授权", "上线、批量授权、Postman 与 ESB 测试、下线恢复"),
        ("第5天", "掌握代码边界", "跟踪 Gateway→LocalPlanRuntime→上游→CompleteResponse"),
        ("第6天", "掌握数据应用", "复制骨架到临时分支，新增端口并运行合同测试"),
        ("第7天", "掌握排障", "模拟错误授权、路由缺失、上游400、超时和日志服务不可用"),
    ], [0.7, 1.55, 4.5], 8.5)

    heading(doc, "附录 A 常用路径与命令")
    code(doc, "# 平台后端\n/Users/tommy/Documents/外部数据/demo/jsnx-edsp-platform-backend\n# 平台前端\n/Users/tommy/Documents/外部数据/demo/jsnx-edsp-platform-frontend\n# 部署与日志\n/Users/tommy/Documents/外部数据/demo/jsnx-edsp-deployment\n# 运行日志\n/Users/tommy/Documents/外部数据/demo/jsnx-edsp-deployment/var/logs\n# PID\n/Users/tommy/Documents/外部数据/demo/jsnx-edsp-deployment/var/run")
    code(doc, "# 查看端口进程\nlsof -nP -iTCP:8080 -sTCP:LISTEN\n# 查看健康\ncurl -s http://127.0.0.1:8080/actuator/health\n# 查看数据库路由\nmysql -h 127.0.0.1 -P 3306 -uroot edsp_demo \\\n  -e \"SELECT interface_code,http_method,service_path,route_version,data_source_id FROM published_route;\"\n# Git 检查\ngit status --short\ngit diff --check")

    heading(doc, "附录 B 术语")
    table(doc, ["术语", "定义"], [
        ("资源提供商", "向银行提供外部数据能力的机构，旧称数据服务商。"),
        ("数据源", "供应商下具备独立地址、超时、隔离应用和运行地址的一组能力。"),
        ("资源 API", "供应商原始接口契约，是标准服务开发的基准。"),
        ("标准服务", "平台面向行内系统发布的稳定 API 契约。"),
        ("PlanSnapshot", "某一发布版本不可变的上游地址、映射、安全、码值和超时计划。"),
        ("runtime_url", "Gateway 转发到独立数据应用的运行地址，生产应填写 VIP。"),
        ("endpoint_url", "数据应用请求供应商的上游基础地址。"),
        ("HIT/MISS/ERROR", "命中数据、正常未命中、业务或技术失败三类标准结果。"),
        ("配置热更新", "不重启 JVM，通过发布快照和原子切换让新请求使用新计划。"),
    ], [1.45, 5.3], 8.5)

    doc.core_properties.title = "外部数据平台 2.0 开发手册"
    doc.core_properties.subject = "模块职责、新接口开发、独立数据应用、测试、发布、授权、部署和排障"
    doc.core_properties.author = "外部数据平台项目组"
    doc.core_properties.keywords = "EDSP, JDK17, Spring Cloud Gateway, 数据应用, 开发手册"
    OUT.parent.mkdir(parents=True, exist_ok=True)
    doc.save(OUT)
    print(OUT)


if __name__ == "__main__":
    build()
