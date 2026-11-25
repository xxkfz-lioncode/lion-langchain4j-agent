package com.lion.agent.tools;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * RAG 解析测试样例生成器: 在 docs/rag 下生成 docx/xlsx/pdf 三个二进制样例文件
 * (md/txt 是纯文本直接维护)。内容与 md 样例同一主题(部署手册), 便于解析后横向对比。
 * <p>
 * PDF 专门覆盖三类元素: 中文正文 / 线框表格(tabula 可识别) / 内嵌图片(PDFBox 可提取)。
 * xlsx 专门覆盖表格分块三档: 小表(整表原子一块) / 大表(>2000字按行拆块+重复表头) / 超行表(>1000行截断)。
 * 运行: mvn test-compile exec:java -Dexec.classpathScope=test
 *       -Dexec.mainClass=com.lion.agent.tools.RagSampleGenerator
 *       (可追加 -Dexec.arguments=xlsx 只生成 Excel; 不带参数生成全部)
 */
public class RagSampleGenerator {

    static final Path OUT = Path.of("docs/rag");

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT);
        String only = args.length > 0 ? args[0] : null;
        if (only == null || only.equals("docx")) {
            genDocx();
        }
        if (only == null || only.equals("xlsx")) {
            genXlsx();
        }
        if (only == null || only.equals("pdf")) {
            genPdf();
        }
        System.out.println("生成完成: " + OUT.toAbsolutePath());
    }

    // ==================== Word(.docx) ====================

    static void genDocx() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            heading(doc, "1", "系统部署手册（Word 样例）");
            heading(doc, "2", "1. 部署概述");
            para(doc, "本系统采用集群化部署架构，由应用服务器、数据库服务器与缓存服务器三部分组成。应用服务器无状态，可水平扩展；数据库为主从架构，主库写入、从库查询。");
            para(doc, "部署窗口建议安排在业务低峰期，整个部署流程预计 2 小时，其中停机时间不超过 10 分钟。");
            heading(doc, "2", "2. 服务器清单");
            table(doc, List.of(
                    List.of("节点角色", "主机名", "IP 地址", "配置"),
                    List.of("应用服务器", "app-01", "10.0.1.11", "8核16G"),
                    List.of("应用服务器", "app-02", "10.0.1.12", "8核16G"),
                    List.of("数据库服务器", "db-01", "10.0.1.21", "16核32G"),
                    List.of("缓存服务器", "cache-01", "10.0.1.31", "4核8G")));
            heading(doc, "3", "2.1 网络要求");
            para(doc, "应用服务器与数据库之间内网互通，防火墙放通 3306、6379、8080 端口。对外仅开放 443，由 Nginx 统一做 TLS 终结与反向代理。");
            heading(doc, "2", "3. 发布流程");
            para(doc, "发布走 CI/CD 流水线：代码合并到 release 分支后自动构建镜像，经测试环境验证后由运维在发布平台执行灰度发布，先切 10% 流量观察 30 分钟再全量。");
            try (var fos = Files.newOutputStream(OUT.resolve("RAG测试_Word文档.docx"))) {
                doc.write(fos);
            }
        }
        System.out.println("docx 完成");
    }

    static void heading(XWPFDocument doc, String styleId, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle(styleId);
        p.createRun().setText(text);
    }

    static void para(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.createRun().setText(text);
    }

    static void table(XWPFDocument doc, List<List<String>> rows) {
        XWPFTable t = doc.createTable(rows.size(), rows.get(0).size());
        for (int r = 0; r < rows.size(); r++) {
            XWPFTableRow row = t.getRow(r);
            for (int c = 0; c < rows.get(r).size(); c++) {
                row.getCell(c).setText(rows.get(r).get(c));
            }
        }
    }

    // ==================== Excel(.xlsx): 表格分块三档样例 ====================

    static void genXlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            // ---- 档位1: 小表(整表 ≤2000 字 → 原子一块, 与另外两档对照) ----
            XSSFSheet s1 = wb.createSheet("服务器清单");
            fill(s1, List.of(
                    List.of("主机名", "IP 地址", "CPU(核)", "内存(G)", "磁盘(G)", "用途"),
                    List.of("app-01", "10.0.1.11", "8", "16", "500", "应用集群节点1"),
                    List.of("app-02", "10.0.1.12", "8", "16", "500", "应用集群节点2"),
                    List.of("db-01", "10.0.1.21", "16", "32", "1000", "MySQL 主库"),
                    List.of("db-02", "10.0.1.22", "16", "32", "1000", "MySQL 从库"),
                    List.of("cache-01", "10.0.1.31", "4", "8", "200", "Redis"),
                    List.of("mq-01", "10.0.1.32", "4", "8", "200", "消息队列")));

            // ---- 档位2: 大表(整体 >2000 字 → StructureChunker 按行拆块, 每块重复表头) ----
            XSSFSheet s2 = wb.createSheet("机房资产清单");
            fill(s2, buildAssets(66));

            // ---- 档位3: 超行表(1200 行 → 解析层截断至前 1000 行, 再按 2000 字拆块) ----
            XSSFSheet s3 = wb.createSheet("运行监控日志");
            fill(s3, buildLogs(1200));

            try (var fos = Files.newOutputStream(OUT.resolve("RAG测试_Excel表.xlsx"))) {
                wb.write(fos);
            }
        }
        System.out.println("xlsx 完成: 服务器清单(7行小表) + 机房资产清单(66行大表) + 运行监控日志(1200行超行表)");
    }

    /** 机房资产大表: 66 行 × 7 列, 每行 55~70 字, 整表约 4000+ 字 → 预期拆成 3 块(每块带表头) */
    static List<List<String>> buildAssets(int count) {
        String[] types = {"机架服务器", "存储阵列", "核心交换机", "防火墙", "带外管理交换机"};
        String[] models = {"华为 2288H V5", "浪潮 AS5300", "华为 CE6820-48S6CQ-K", "H3C SecPath F100", "华为 CE6810-48S4Q-EI"};
        String[] areas = {"A", "B", "C"};
        String[] states = {"在用", "在用", "备用", "维修"};
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("资产编号", "设备类型", "型号", "机柜位置", "管理IP", "状态", "上架日期"));
        for (int i = 0; i < count; i++) {
            int t = i % types.length;
            rows.add(List.of(
                    String.format("IT-%04d", 3000 + i),
                    types[t],
                    models[t],
                    String.format("%s区%02d号机柜 U%02d", areas[i % 3], i / 3 + 1, i % 18 + 2),
                    String.format("10.10.%d.%d", 100 + i / 50, 10 + i),
                    states[i % 4],
                    String.format("202%d-%02d-%02d", 3 + i % 2, i % 12 + 1, i % 28 + 1)));
        }
        return rows;
    }

    /** 监控日志超行表: 1200 行 × 5 列(每 5 分钟一条采样), 触发解析层 1000 行截断 */
    static List<List<String>> buildLogs(int count) {
        String[] devices = {"app-01", "app-02", "db-01", "cache-01"};
        String[] metrics = {"CPU使用率(%)", "内存使用率(%)", "磁盘IO延迟(ms)", "网络流入(Mbps)"};
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm");
        Random rnd = new Random(42);
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("采集时间", "设备", "指标", "数值", "状态"));
        LocalDateTime t = LocalDateTime.of(2024, 6, 1, 0, 0);
        for (int i = 0; i < count; i++) {
            int v = 20 + rnd.nextInt(76);            // 20~95
            rows.add(List.of(
                    t.format(fmt),
                    devices[i % devices.length],
                    metrics[i % metrics.length],
                    String.valueOf(v),
                    v >= 85 ? "预警" : "正常"));
            t = t.plusMinutes(5);
        }
        return rows;
    }

    static void fill(XSSFSheet sheet, List<List<String>> rows) {
        for (int r = 0; r < rows.size(); r++) {
            XSSFRow row = sheet.createRow(r);
            for (int c = 0; c < rows.get(r).size(); c++) {
                row.createCell(c).setCellValue(rows.get(r).get(c));
            }
        }
        for (int c = 0; c < rows.get(0).size(); c++) {
            sheet.setColumnWidth(c, 14 * 256);
        }
    }

    // ==================== PDF(正文 + 线框表格 + 内嵌图片) ====================

    static void genPdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDFont font = loadChineseFont(doc);

            // ---- 第 1 页: 标题 + 正文段落 ----
            PDPage p1 = new PDPage(PDRectangle.A4);
            doc.addPage(p1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, p1)) {
                text(cs, font, 60, 780, 18, "数据中心部署手册（PDF 样例）");
                float y = 740;
                for (String line : new String[]{
                        "本手册面向运维团队，描述数据中心机房的设备上架、布线与上架后验收标准。",
                        "机房分为 A、B 两个区域，A 区承载核心业务，B 区承载测试与灾备。",
                        "上架前需核对设备序列号与采购单一致，并完成资产登记。",
                        "机柜供电采用双路冗余，单机柜功率上限为 6kW，超限需报机房负责人审批。",
                        "所有设备接入管理网段（10.10.0.0/16），业务口接入对应业务 VLAN。",
                        "验收通过后设备正式交付使用，验收记录归档保存三年。"}) {
                    text(cs, font, 60, y, 11, line);
                    y -= 22;
                }
            }

            // ---- 第 2 页: 线框表格(tabula SpreadsheetExtractionAlgorithm 可识别) ----
            PDPage p2 = new PDPage(PDRectangle.A4);
            doc.addPage(p2);
            try (PDPageContentStream cs = new PDPageContentStream(doc, p2)) {
                text(cs, font, 60, 780, 15, "机柜设备清单");
                String[][] rows = {
                        {"设备类型", "数量", "功率(kW)", "U 位"},
                        {"机架服务器", "8", "0.45", "2U"},
                        {"存储阵列", "2", "1.20", "4U"},
                        {"核心交换机", "2", "0.60", "1U"},
                        {"防火墙", "1", "0.35", "1U"},
                        {"带外管理交换机", "1", "0.15", "1U"}};
                drawGridTable(cs, font, 60, 740, rows, 26, new float[]{140, 70, 90, 70});
                text(cs, font, 60, 740 - rows.length * 26 - 40, 11,
                        "注：合计功率 5.55kW，未超出单机柜 6kW 上限。");
            }

            // ---- 第 3 页: 正文 + 内嵌图片(程序绘制"架构示意图", PDFBox 可提取) ----
            PDPage p3 = new PDPage(PDRectangle.A4);
            doc.addPage(p3);
            try (PDPageContentStream cs = new PDPageContentStream(doc, p3)) {
                text(cs, font, 60, 780, 15, "网络拓扑说明");
                text(cs, font, 60, 750, 11,
                        "全网采用星型拓扑，核心交换机双机堆叠，下联各业务接入层交换机。下图为本区域网络拓扑示意图：");
                PDImageXObject img = LosslessFactory.createFromImage(doc, topologyImage());
                cs.drawImage(img, 70, 330, 455, 300);
                text(cs, font, 60, 300, 11,
                        "上图中双核心交换机之间为堆叠线缆，出口防火墙旁挂于核心层，管理流量与业务流量物理隔离。");
            }

            try (var fos = Files.newOutputStream(OUT.resolve("RAG测试_PDF文档.pdf"))) {
                doc.save(fos);
            }
        }
        System.out.println("pdf 完成");
    }

    /** 中文字体: 黑体 → 微软雅黑(ttc) → 宋体(ttc), 都没有则报错 */
    static PDFont loadChineseFont(PDDocument doc) throws Exception {
        Path hei = Path.of("C:/Windows/Fonts/simhei.ttf");
        if (Files.exists(hei)) {
            return PDType0Font.load(doc, Files.newInputStream(hei), true);
        }
        for (String[] ttc : new String[][]{{"C:/Windows/Fonts/msyh.ttc", "MicrosoftYaHei"},
                {"C:/Windows/Fonts/simsun.ttc", "SimSun"}}) {
            Path p = Path.of(ttc[0]);
            if (Files.exists(p)) {
                try (var tc = new org.apache.fontbox.ttf.TrueTypeCollection(p.toFile())) {
                    return PDType0Font.load(doc, tc.getFontByName(ttc[1]), true);
                }
            }
        }
        throw new IllegalStateException("未找到系统中文字体(需要 simhei.ttf / msyh.ttc / simsun.ttc 之一)");
    }

    /** 单行文字 */
    static void text(PDPageContentStream cs, PDFont font, float x, float y, float size, String line) throws Exception {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(line);
        cs.endText();
    }

    /** 线框表格: 外框 + 行列分隔线 + 单元格文字(每个单元格单独定位绘制) */
    static void drawGridTable(PDPageContentStream cs, PDFont font, float x, float yTop,
                              String[][] rows, float rowH, float[] colW) throws Exception {
        float totalW = 0;
        for (float w : colW) totalW += w;
        float totalH = rows.length * rowH;
        float[] xs = new float[colW.length + 1];
        for (int i = 0; i < colW.length; i++) xs[i + 1] = xs[i] + colW[i];

        cs.setLineWidth(1f);
        // 横线
        for (int r = 0; r <= rows.length; r++) {
            cs.moveTo(x, yTop - r * rowH);
            cs.lineTo(x + totalW, yTop - r * rowH);
        }
        // 竖线
        for (int c = 0; c <= colW.length; c++) {
            cs.moveTo(x + xs[c], yTop);
            cs.lineTo(x + xs[c], yTop - totalH);
        }
        cs.stroke();

        // 单元格文字(PDF 坐标: newLineAtOffset 的 y 是文字基线, 留出上边距)
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length; c++) {
                text(cs, font, x + xs[c] + 6, yTop - r * rowH - rowH + 7, 10, rows[r][c]);
            }
        }
    }

    /** 程序绘制"网络拓扑示意图"(内嵌图片素材): 渐变背景 + 双核心 + 出口 + 接入层 */
    static BufferedImage topologyImage() {
        int w = 1200, h = 800;
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bi.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(232, 240, 252), 0, h, new Color(210, 224, 245)));
        g.fillRect(0, 0, w, h);
        g.setStroke(new BasicStroke(5f));

        Font label = new Font("SimHei", Font.BOLD, 34);
        g.setColor(new Color(30, 70, 140));

        // 出口防火墙(顶部)
        g.fillRect(500, 40, 200, 80);
        g.setColor(Color.WHITE);
        g.setFont(label);
        g.drawString("出口防火墙", 520, 95);
        // 双核心(中部)
        g.setColor(new Color(30, 70, 140));
        g.fillRect(200, 320, 260, 90);
        g.fillRect(740, 320, 260, 90);
        g.setColor(Color.WHITE);
        g.drawString("核心交换机 A", 210, 380);
        g.drawString("核心交换机 B", 750, 380);
        // 接入层(底部)
        g.setColor(new Color(90, 130, 190));
        for (int i = 0; i < 3; i++) {
            g.fillRect(120 + i * 340, 620, 240, 80);
            g.setColor(Color.WHITE);
            g.drawString("接入交换机 " + (char) ('A' + i), 150 + i * 340, 675);
            g.setColor(new Color(90, 130, 190));
        }
        // 连线
        g.setColor(new Color(120, 120, 120));
        g.setStroke(new BasicStroke(6f));
        g.drawLine(600, 120, 330, 320);   // 防火墙→核心A
        g.drawLine(600, 120, 870, 320);   // 防火墙→核心B
        g.drawLine(460, 365, 740, 365);   // 核心 A↔B(堆叠)
        for (int i = 0; i < 3; i++) {
            g.drawLine(330, 410, 240 + i * 340, 620);
            g.drawLine(870, 410, 240 + i * 340, 620);
        }
        g.dispose();
        return bi;
    }
}
