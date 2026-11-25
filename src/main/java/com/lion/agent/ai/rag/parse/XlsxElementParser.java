package com.lion.agent.ai.rag.parse;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel(.xlsx/.xls) 解析器: POI WorkbookFactory(同时兼容两种格式), 每个 sheet 产出
 * 一个"sheet名标题 + 表格(Markdown)"元素对。
 * <p>
 * 大表处理: 单 sheet 超过 {@link #MAX_ROWS} 行才截断(尾部加提示行; 业务表通常几百~几千行,
 * 全量入库, 万行级才触发保险丝), 列数超过 {@link #MAX_COLS} 取前 N 列;
 * 超长表格的分块(重复表头)由 {@link StructureChunker} 统一完成 —— 解析层只忠实产出元素。
 * <p>
 * 单元格取值用 DataFormatter(取"显示值": 日期/数字格式与 Excel 界面一致, 避免读出 4.5万+ 的日期序列号)。
 */
@Component
public class XlsxElementParser implements ElementParser {

    /**
     * 单 sheet 最大行数(保险丝, 万行级才触发; 业务表几百~几千行全量入库不截断)。
     * 真正的超大表(几万行)会拖慢 POI 解析并产出数百片段, 超过才截断并留提示。
     */
    private static final int MAX_ROWS = 10000;
    /** 单表最大列数 */
    private static final int MAX_COLS = 50;

    @Override
    public List<DocElement> parse(ParseContext ctx) throws Exception {
        List<DocElement> elements = new ArrayList<>();
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(ctx.getData()))) {
            for (Sheet sheet : wb) {
                int lastRow = sheet.getLastRowNum();
                if (lastRow < 0 || sheet.getRow(0) == null) {
                    continue; // 空 sheet 跳过
                }
                elements.add(DocElement.title(sheet.getSheetName(), 1, null));
                elements.add(DocElement.table(sheetToMarkdown(sheet, fmt, lastRow), null));
            }
        }
        if (elements.isEmpty()) {
            throw new IllegalStateException("Excel 文档没有可用的工作表");
        }
        return elements;
    }

    private String sheetToMarkdown(Sheet sheet, DataFormatter fmt, int lastRow) {
        int rows = Math.min(lastRow + 1, MAX_ROWS);
        // 列数取前 MAX_ROWS 行的最大列(超 MAX_COLS 截断)
        int cols = 0;
        for (int r = 0; r < rows; r++) {
            Row row = sheet.getRow(r);
            if (row != null) {
                cols = Math.max(cols, row.getLastCellNum());
            }
        }
        cols = Math.min(Math.max(cols, 1), MAX_COLS);

        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            sb.append('|');
            for (int c = 0; c < cols; c++) {
                String v = cellValue(sheet, r, c, fmt);
                sb.append(' ').append(v).append(' ').append('|');
            }
            sb.append('\n');
            if (r == 0) {
                sb.append('|');
                sb.append(" --- |".repeat(cols));
                sb.append('\n');
            }
        }
        if (lastRow + 1 > MAX_ROWS) {
            sb.append("> 注: 该工作表共 ").append(lastRow + 1).append(" 行, 已截断至前 ").append(MAX_ROWS).append(" 行");
        }
        return sb.toString().strip();
    }

    /** 单元格显示值: 换行折叠, 竖线转义, 空单元格为空串 */
    private String cellValue(Sheet sheet, int r, int c, DataFormatter fmt) {
        Row row = sheet.getRow(r);
        if (row == null) {
            return "";
        }
        try {
            String v = fmt.formatCellValue(row.getCell(c));
            return v == null ? "" : v.replace("\r", " ").replace("\n", " ")
                    .replace("|", "\\|").strip();
        } catch (Exception e) {
            return ""; // 个别畸形单元格不阻断整表解析
        }
    }
}
