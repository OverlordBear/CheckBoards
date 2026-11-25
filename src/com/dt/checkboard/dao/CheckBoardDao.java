package com.dt.checkboard.dao;

import com.dt.checkboard.config.FormSpec;
import com.dt.checkboard.entity.CheckRecord;
import com.dt.checkboard.entity.DayRow;
import com.dt.common.util.LogUtil;
import weaver.conn.RecordSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 点检数据访问层。
 *
 * <p>概览只取当天需要的几列，聚合放在 Service 里做：一张表一天只有几十行，
 * 但「多个结果字段」和「文本单位包含匹配」在 SQL 里拼起来会非常难读。</p>
 */
public class CheckBoardDao {

    /**
     * 某个表单当天的原始记录行，只含判定需要的列。
     */
    public List<DayRow> rows(FormSpec f, String date) {
        List<DayRow> list = new ArrayList<DayRow>();
        List<String> matchCols = f.matchColumns();

        StringBuilder cols = new StringBuilder(320);
        for (int i = 0; i < matchCols.size(); i++) {
            if (i > 0) {
                cols.append(", ");
            }
            cols.append("r.").append(matchCols.get(i)).append(" AS u").append(i);
        }
        if (cols.length() > 0) {
            cols.append(", ");
        }
        cols.append("r.").append(f.timeField).append(" AS rtime");
        for (int i = 0; i < f.results.size(); i++) {
            cols.append(", r.").append(f.results.get(i).field).append(" AS r").append(i);
        }
        cols.append(", r.modedatacreatedate AS rcreate")
                .append(", r.").append(f.inspectorField).append(" AS rins")
                .append(", h.lastname AS rname");

        String sql = "SELECT " + cols
                + " FROM " + f.table + " r"
                + " LEFT JOIN hrmresource h ON h.id = r." + f.inspectorField
                + " WHERE r." + f.dateField + " = ?";
        try {
            RecordSet rs = new RecordSet();
            rs.executeQuery(sql, date);
            while (rs.next()) {
                DayRow row = new DayRow();
                row.unitParts = new String[matchCols.size()];
                StringBuilder unit = new StringBuilder();
                for (int i = 0; i < matchCols.size(); i++) {
                    String v = str(rs.getString("u" + i)).trim();
                    row.unitParts[i] = v;
                    if (!v.isEmpty()) {
                        if (unit.length() > 0) {
                            unit.append(' ');
                        }
                        unit.append(v);
                    }
                }
                row.unit = unit.toString();
                row.time = str(rs.getString("rtime")).trim();
                row.results = new String[f.results.size()];
                for (int i = 0; i < f.results.size(); i++) {
                    row.results[i] = str(rs.getString("r" + i)).trim();
                }
                row.createdDate = str(rs.getString("rcreate")).trim();
                row.inspectorId = str(rs.getString("rins")).trim();
                row.inspector = str(rs.getString("rname")).trim();
                list.add(row);
            }
        } catch (Exception e) {
            LogUtil.error("查询 " + f.name + " 失败, sql=" + sql, e);
            throw new IllegalStateException("查询 " + f.name + " 失败: " + e.getMessage(), e);
        }
        return list;
    }

    /**
     * 明细，按主键倒序，最多 limit 条。
     *
     * <p>{@code unit} 为空表示整张表当天全部记录；包含匹配的表在内存里按关键词过滤，
     * 因为原始值是「2号线」「三号」这类文本，SQL 等值匹配对不上。</p>
     */
    public List<CheckRecord> detail(FormSpec f, String date, String unit, int limit) {
        List<CheckRecord> list = new ArrayList<CheckRecord>();
        List<Object> params = new ArrayList<Object>();
        boolean contains = f.containsMatch() && f.hasUnitField() && unit != null && !unit.isEmpty();

        StringBuilder cols = new StringBuilder(640);
        cols.append("r.id AS rid, r.").append(f.timeField).append(" AS rtime")
                .append(", r.").append(f.inspectorField).append(" AS rins")
                .append(", h.lastname AS rname")
                .append(", ").append(f.hasUnitField() ? "r." + f.unitField : "''").append(" AS runit");
        List<String> matchCols = f.matchColumns();
        for (int i = 0; i < matchCols.size(); i++) {
            cols.append(", r.").append(matchCols.get(i)).append(" AS u").append(i);
        }
        for (int i = 0; i < f.results.size(); i++) {
            cols.append(", r.").append(f.results.get(i).field).append(" AS r").append(i);
        }
        cols.append(", r.modedatacreatedate AS rcreate");
        for (int i = 0; i < f.items.size(); i++) {
            FormSpec.Item item = f.items.get(i);
            cols.append(", r.").append(item.field).append(" AS it").append(i);
            if (!item.imageField.isEmpty()) {
                cols.append(", r.").append(item.imageField).append(" AS iti").append(i);
            }
            if (!item.remarkField.isEmpty()) {
                cols.append(", r.").append(item.remarkField).append(" AS itr").append(i);
            }
        }
        for (int i = 0; i < f.images.size(); i++) {
            cols.append(", r.").append(f.images.get(i).field).append(" AS im").append(i);
        }

        StringBuilder sql = new StringBuilder(768);
        sql.append("SELECT ").append(cols)
                .append(" FROM ").append(f.table).append(" r")
                .append(" LEFT JOIN hrmresource h ON h.id = r.").append(f.inspectorField)
                .append(" WHERE r.").append(f.dateField).append(" = ?");
        params.add(date);
        if (f.hasUnitField() && unit != null && !unit.isEmpty() && !contains) {
            sql.append(" AND r.").append(f.unitField).append(" = ?");
            params.add(unit);
        }
        sql.append(" ORDER BY r.id DESC");
        if (limit > 0 && !contains) {
            sql.append(" LIMIT ").append(limit);
        }

        String finalSql = sql.toString();
        try {
            RecordSet rs = new RecordSet();
            rs.executeQuery(finalSql, params.toArray());
            while (rs.next()) {
                CheckRecord r = new CheckRecord();
                r.id = rs.getInt("rid");
                r.time = str(rs.getString("rtime")).trim();
                r.inspectorId = str(rs.getString("rins")).trim();
                r.inspector = str(rs.getString("rname")).trim();
                r.unit = str(rs.getString("runit")).trim();
                r.unitParts = new String[matchCols.size()];
                for (int i = 0; i < matchCols.size(); i++) {
                    r.unitParts[i] = str(rs.getString("u" + i)).trim();
                }
                r.createdDate = str(rs.getString("rcreate")).trim();
                r.resultValues = new String[f.results.size()];
                r.abnormal = false;
                r.emptyResult = false;
                for (int i = 0; i < f.results.size(); i++) {
                    String v = str(rs.getString("r" + i)).trim();
                    r.resultValues[i] = v;
                    if (v.isEmpty()) {
                        r.emptyResult = true;
                    } else if (!f.normalOf(i).contains(v)) {
                        r.abnormal = true;
                    }
                }
                r.result = r.resultValues.length > 0 ? r.resultValues[0] : "";
                r.late = !r.createdDate.isEmpty() && r.createdDate.compareTo(date) > 0;

                r.itemValues = new String[f.items.size()];
                r.itemImages = new String[f.items.size()];
                r.itemRemarks = new String[f.items.size()];
                for (int i = 0; i < f.items.size(); i++) {
                    r.itemValues[i] = str(rs.getString("it" + i)).trim();
                    r.itemImages[i] = f.items.get(i).imageField.isEmpty()
                            ? "" : str(rs.getString("iti" + i)).trim();
                    r.itemRemarks[i] = f.items.get(i).remarkField.isEmpty()
                            ? "" : str(rs.getString("itr" + i)).trim();
                }
                r.imageValues = new String[f.images.size()];
                for (int i = 0; i < f.images.size(); i++) {
                    r.imageValues[i] = str(rs.getString("im" + i)).trim();
                }
                if (contains) {
                    FormSpec.Unit u = f.matchUnit(r.unitParts);
                    if (u == null || !u.code.equals(unit)) {
                        continue;
                    }
                }
                list.add(r);
                if (contains && limit > 0 && list.size() >= limit) {
                    break;
                }
            }
        } catch (Exception e) {
            LogUtil.error("查询 " + f.name + " 明细失败, sql=" + finalSql, e);
            throw new IllegalStateException("查询 " + f.name + " 明细失败: " + e.getMessage(), e);
        }
        return list;
    }

    /**
     * 检查配置里用到的列是否都存在，返回空串表示通过。
     *
     * <p>点检项、图片、备注列也一起校验：之前只查日期/时间/人员/结果/单位，
     * 项字段写错要等用户点开明细才报 SQL 错。</p>
     */
    public String checkColumns(FormSpec f) {
        StringBuilder sql = new StringBuilder(400);
        sql.append("SELECT r.").append(f.dateField)
                .append(", r.").append(f.timeField)
                .append(", r.").append(f.inspectorField);
        for (String col : f.matchColumns()) {
            sql.append(", r.").append(col);
        }
        for (FormSpec.ResultField rf : f.results) {
            sql.append(", r.").append(rf.field);
        }
        for (FormSpec.Item it : f.items) {
            sql.append(", r.").append(it.field);
            if (!it.imageField.isEmpty()) {
                sql.append(", r.").append(it.imageField);
            }
            if (!it.remarkField.isEmpty()) {
                sql.append(", r.").append(it.remarkField);
            }
        }
        for (FormSpec.ImageField img : f.images) {
            sql.append(", r.").append(img.field);
        }
        sql.append(" FROM ").append(f.table).append(" r WHERE 1 = 0");
        try {
            RecordSet rs = new RecordSet();
            rs.executeQuery(sql.toString());
            rs.next();
            return "";
        } catch (Exception e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    /**
     * 按「单位字段组合」统计最近一段时间的出现次数，供 /config 检查配置与实际填写是否一致。
     *
     * <p>天车、机械压力、辊压线设备是文本单位，得把多个匹配字段放在一起看，
     * 单独统计某个字段会把「设备名称=桥式起重机T + 线别=2线」拆散。</p>
     */
    public List<String[]> unitValueCounts(FormSpec f, String fromDate, int limit) {
        List<String[]> list = new ArrayList<String[]>();
        List<String> matchCols = f.matchColumns();
        if (matchCols.isEmpty()) {
            return list;
        }
        StringBuilder cols = new StringBuilder(200);
        for (int i = 0; i < matchCols.size(); i++) {
            if (i > 0) {
                cols.append(", ");
            }
            cols.append(matchCols.get(i));
        }
        String sql = "SELECT " + cols + " FROM " + f.table
                + " WHERE " + f.dateField + " >= ?";
        Map<String, int[]> counts = new LinkedHashMap<String, int[]>();
        try {
            RecordSet rs = new RecordSet();
            rs.executeQuery(sql, fromDate);
            while (rs.next()) {
                StringBuilder key = new StringBuilder();
                for (int i = 0; i < matchCols.size(); i++) {
                    String v = str(rs.getString(matchCols.get(i))).trim();
                    if (!v.isEmpty()) {
                        if (key.length() > 0) {
                            key.append(' ');
                        }
                        key.append(v);
                    }
                }
                String k = key.toString();
                int[] c = counts.get(k);
                if (c == null) {
                    counts.put(k, new int[]{1});
                } else {
                    c[0]++;
                }
            }
        } catch (Exception e) {
            LogUtil.error("统计 " + f.key + " 单位组合失败, sql=" + sql, e);
            return list;
        }
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            if (list.size() >= limit) {
                break;
            }
            list.add(new String[]{e.getKey(), String.valueOf(e.getValue()[0])});
        }
        return list;
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    /**
     * 抽样某个字段最近一段时间的值域，用于发现配置与实际表单不一致。
     *
     * @return 每个元素为 {值, 出现次数}
     */
    public List<String[]> distinct(FormSpec f, String field, String fromDate, int limit) {
        List<String[]> list = new ArrayList<String[]>();
        if (field == null || field.isEmpty()) {
            return list;
        }
        String sql = "SELECT " + field + " AS v, COUNT(*) AS c FROM " + f.table
                + " WHERE " + f.dateField + " >= ? GROUP BY " + field + " ORDER BY c DESC";
        try {
            RecordSet rs = new RecordSet();
            rs.executeQuery(sql, fromDate);
            while (rs.next() && list.size() < limit) {
                list.add(new String[]{
                        str(rs.getString("v")).trim(),
                        String.valueOf(rs.getInt("c"))
                });
            }
        } catch (Exception e) {
            LogUtil.error("统计 " + f.key + "." + field + " 值域失败, sql=" + sql, e);
        }
        return list;
    }
}
