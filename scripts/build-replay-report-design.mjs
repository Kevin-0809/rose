import fs from "node:fs/promises";
import { SpreadsheetFile, Workbook } from "@oai/artifact-tool";

const outDir = "E:/codex/rose/outputs/replay-report-design";
await fs.mkdir(outDir, { recursive: true });

const wb = Workbook.create();
const green = "#0E566F";
const greenLight = "#DDEBF7";
const greenPale = "#E2F0D9";
const pink = "#C55A6A";
const pinkLight = "#FCE4D6";
const gray = "#F2F2F2";
const border = { preset: "all", style: "thin", color: "#B7C9D3" };

function styleHeader(range, fill) {
  range.format = { fill, font: { bold: true, color: "#FFFFFF" }, horizontalAlignment: "center", verticalAlignment: "center", wrapText: true, borders: border };
}
function styleBody(range) {
  range.format = { verticalAlignment: "center", borders: border };
}
function widths(sheet, widths) {
  widths.forEach((w, i) => sheet.getRangeByIndexes(0, i, 1, 1).format.columnWidth = w);
}

const summary = wb.worksheets.add("汇总信息");
summary.showGridLines = false;
summary.getRange("A1:M1").merge();
summary.getRange("A1").values = [["批次号：RPT20260831-091113-5363（本批次）"]];
summary.getRange("A1:M1").format = { fill: pinkLight, font: { bold: true, color: "#1F1F1F", size: 14 }, horizontalAlignment: "center", verticalAlignment: "center" };
summary.getRange("A2:M3").values = [
  ["批次", "领域", "覆盖528接口", "发送交易量", "交易状态分类统计", null, null, null, null, null, "成功率", "比对通过率", "问题总数"],
  [null, null, null, null, "528成功/CCBS失败", "528失败/CCBS成功", "二者均失败响应码一致", "二者均失败响应码不一致", "二者均成功", "响应码忽略", null, null, null],
];
for (const r of ["A2:A3", "B2:B3", "C2:C3", "D2:D3", "K2:K3", "L2:L3", "M2:M3"]) summary.getRange(r).merge();
summary.getRange("E2:J2").merge();
styleHeader(summary.getRange("A2:M3"), green);
summary.getRange("A4:M4").values = [["RPT20260831-091113-5363", "支付", 3, 128, 6, 4, 2, 1, 113, 2, 0.93, 0.91, 3]];
summary.getRange("A4:M4").format = { fill: "#FFFFFF", borders: border, verticalAlignment: "center" };
summary.getRange("C4:J4").format.numberFormat = "#,##0";
summary.getRange("K4:L4").format.numberFormat = "0.00%";
summary.getRange("A5:M5").values = [["", "合计", "=SUM(C4:C4)", "=SUM(D4:D4)", "=SUM(E4:E4)", "=SUM(F4:F4)", "=SUM(G4:G4)", "=SUM(H4:H4)", "=SUM(I4:I4)", "=SUM(J4:J4)", "=K4", "=L4", "=SUM(M4:M4)"]];
summary.getRange("A5:M5").format = { fill: greenPale, font: { bold: true }, borders: border };
summary.getRange("C5:J5").format.numberFormat = "#,##0";
summary.getRange("K5:L5").format.numberFormat = "0.00%";
summary.freezePanes.freezeRows(3);
widths(summary, [20, 14, 14, 14, 16, 16, 18, 18, 14, 14, 14, 14, 12]);

const detail = wb.worksheets.add("接口比对明细");
detail.showGridLines = false;
const dh = ["批次号", "交易码", "S码", "交易描述", "开发负责人", "行内负责人", "领域", "发送交易量", "528成功/CCBS失败", "528失败/CCBS成功", "二者均失败响应码一致", "二者均失败响应码不一致", "二者均成功", "响应码忽略", "交易成功率", "接口比对通过率", "528平均耗时", "CCBS平均耗时"];
detail.getRange("A1:R1").values = [dh];
styleHeader(detail.getRange("A1:R1"), green);
detail.getRange("A2:R5").values = [
  ["RPT20260831-091113-5363", "T001", "SVC001", "支付查询", "张三", "李四", "支付", 128, 6, 4, 2, 1, 113, 2, 0.93, 0.91, 21, 19],
  ["RPT20260831-091113-5363", "T002", "SVC002", "账户余额", "王五", "赵六", "支付", 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null],
  ["RPT20260831-091113-5363", "T003", "SVC003", "历史交易", "钱七", "支付", 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null],
  ["RPT20260831-091113-5363", "T004", "SVC004", "新交易", "周八", "支付", 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null],
];
styleBody(detail.getRange("A2:R5"));
detail.getRange("H2:N5").format.numberFormat = "#,##0";
detail.getRange("O2:P5").format.numberFormat = "0.00%";
detail.getRange("A6:R6").values = [["", "", "合计", "", "", "", "", "=SUM(H2:H5)", "=SUM(I2:I5)", "=SUM(J2:J5)", "=SUM(K2:K5)", "=SUM(L2:L5)", "=SUM(M2:M5)", "=SUM(N2:N5)", null, null, null, null]];
detail.getRange("A6:R6").format = { fill: greenPale, font: { bold: true }, borders: border };
detail.getRange("H6:N6").format.numberFormat = "#,##0";
detail.getRange("A2:R5").conditionalFormats.add("containsText", { text: "0", format: { fill: "#FFF2CC" } });
detail.freezePanes.freezeRows(1);
widths(detail, [24, 12, 14, 18, 14, 14, 12, 14, 16, 16, 18, 18, 14, 14, 14, 16, 14, 14]);

const coverage = wb.worksheets.add("回放交易覆盖情况");
coverage.showGridLines = false;
coverage.getRange("A1:J1").merge();
coverage.getRange("A1").values = [["回放交易覆盖情况｜目录全量基准｜批次 RPT20260831-091113-5363"]];
coverage.getRange("A1:J1").format = { fill: green, font: { bold: true, color: "#FFFFFF", size: 14 }, horizontalAlignment: "center" };
coverage.getRange("A3:H3").merge();
coverage.getRange("A3").values = [["按业务领域汇总"]];
coverage.getRange("A3:H3").format = { fill: greenLight, font: { bold: true, color: green } };
coverage.getRange("A4:H4").values = [["业务领域", "全量清单交易数", "本次已发送", "本次未发送", "不回放", "近期无交易", "待分析", "覆盖率"]];
styleHeader(coverage.getRange("A4:H4"), green);
coverage.getRange("A5:H7").values = [
  ["支付", 8, 6, 2, 1, 1, 0, "=C5/B5"],
  ["贷款", 4, 2, 2, 0, 1, 1, "=C6/B6"],
  ["合计", "=SUM(B5:B6)", "=SUM(C5:C6)", "=SUM(D5:D6)", "=SUM(E5:E6)", "=SUM(F5:F6)", "=SUM(G5:G6)", "=C7/B7"],
];
coverage.getRange("A5:H7").format = { fill: "#FFFFFF", borders: border };
coverage.getRange("A7:H7").format = { fill: greenPale, font: { bold: true }, borders: border };
coverage.getRange("B5:G7").format.numberFormat = "#,##0";
coverage.getRange("H5:H7").format.numberFormat = "0.00%";
coverage.getRange("A8:J8").merge();
coverage.getRange("A8").values = [["交易码明细（表头支持自动筛选）"]];
coverage.getRange("A8:J8").format = { fill: greenLight, font: { bold: true, color: green } };
const ch = ["交易码", "交易描述", "业务领域", "S码", "是否需要回放", "最近交易日期", "本次发送交易量", "覆盖状态", "未发送原因", "负责人信息"];
coverage.getRange("A9:J9").values = [ch];
styleHeader(coverage.getRange("A9:J9"), green);
coverage.getRange("A10:J15").values = [
  ["T001", "支付查询", "支付", "SVC001", "是", "20260830", 128, "已发送", "", "张三 / 李四"],
  ["T002", "账户余额", "支付", "SVC002", "否", "20260720", 0, "未发送", "不回放", "王五 / 赵六"],
  ["T003", "历史交易", "支付", "SVC003", "是", "20260717", 0, "未发送", "近期无交易", "钱七 / 孙九"],
  ["T004", "新交易", "支付", "SVC004", "是", "20260830", 0, "未发送", "待分析", "周八 / 吴十"],
  ["T005", "批量付款", "支付", "SVC005", "是", "20260829", 45, "已发送", "", "郑十一 / 郑十二"],
  ["T006", "贷款还款", "贷款", "SVC006", "是", "20260710", 0, "未发送", "近期无交易", "陈十三 / 何十四"],
];
styleBody(coverage.getRange("A10:J15"));
coverage.getRange("G10:G15").format.numberFormat = "#,##0";
coverage.getRange("H10:H15").conditionalFormats.add("containsText", { text: "已发送", format: { fill: "#E2F0D9", font: { color: "#006100", bold: true } } });
coverage.getRange("H10:H15").conditionalFormats.add("containsText", { text: "未发送", format: { fill: "#FFF2CC", font: { color: "#9C6500", bold: true } } });
coverage.getRange("I10:I15").conditionalFormats.add("containsText", { text: "待分析", format: { fill: "#F4CCCC", font: { color: "#9C0006", bold: true } } });
coverage.getRange("I10:I15").conditionalFormats.add("containsText", { text: "近期无交易", format: { fill: "#D9EAF7" } });
coverage.tables.add("A9:J15", true, "ReplayCoverageDetail");
coverage.freezePanes.freezeRows(9);
widths(coverage, [12, 18, 14, 14, 14, 16, 16, 14, 16, 20]);

for (const sheet of [summary, detail, coverage]) {
  const used = sheet.getUsedRange();
  used.format.font = { name: "Microsoft YaHei", size: 10 };
}

for (const name of ["汇总信息", "接口比对明细", "回放交易覆盖情况"]) {
  const png = await wb.render({ sheetName: name, autoCrop: "all", scale: 1, format: "png" });
  await fs.writeFile(`${outDir}/${name}.png`, new Uint8Array(await png.arrayBuffer()));
}
const inspect = await wb.inspect({ kind: "table", range: "回放交易覆盖情况!A3:J15", include: "values,formulas", tableMaxRows: 20, tableMaxCols: 12 });
await fs.writeFile(`${outDir}/inspect.txt`, inspect.ndjson, "utf8");
const xlsx = await SpreadsheetFile.exportXlsx(wb);
await xlsx.save(`${outDir}/回放报表最终效果样例.xlsx`);
