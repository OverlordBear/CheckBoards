package com.dt.checkboard.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一张点检表的契约。
 *
 * <p>监控平台只关心四件事：哪天、哪个应检单位、有没有记录、结果是否正常。
 * 点检人、班次、明细项都不参与完成判定。</p>
 */
public final class FormSpec {

    public String key = "";
    public String name = "";
    public String table = "";

    public String dateField = "";
    public String timeField = "";
    public String inspectorField = "";
    public String unitField = "";

    /**
     * 应检单位的匹配方式：
     * <ul>
     *   <li>{@code exact}：单位字段的值等于 units[].code（下拉/单选框）</li>
     *   <li>{@code contains}：单位字段是自由文本，值里含 units[].keywords 任一关键词即命中</li>
     * </ul>
     */
    public String unitMatch = "exact";

    /** 参与包含匹配的额外文本字段，例如天车的「线别」。 */
    public List<String> matchFields = new ArrayList<String>();

    /** 应检单位清单。叉车一个元素，焊机两台，产线四条线。 */
    public List<Unit> units = new ArrayList<Unit>();

    /** 结果字段，可以有两个（天车：起重机 + 钢丝绳）。 */
    public List<ResultField> results = new ArrayList<ResultField>();

    /** 停线/未开线标记字段，可为空。命中 offValues 时视作未开线，不再判结果未填或异常。 */
    public String offField = "";

    /** 视作未开线/停线的值列表（如 ["1", "未开线"]）。 */
    public List<String> offValues = new ArrayList<String>();

    /** 未开线状态在看板与明细中的展示文案。 */
    public String offLabel = "未开线";

    /** 全局正常值白名单；每个结果/点检项可以有自己的白名单。 */
    public List<String> normal = new ArrayList<String>();

    /** 原单链接模板，{id} 会被替换成记录主键，留空则不显示链接。 */
    public String detailUrl = "";

    /** 点检项清单，只用于明细页定位异常项，不参与完成判定。 */
    public List<Item> items = new ArrayList<Item>();

    /** 整单级的图片字段（如现场照片）。 */
    public List<ImageField> images = new ArrayList<ImageField>();

    /** 图片地址模板，{id} 替换成文件 id；留空则不展示图片。 */
    public String imageUrl = "";

    /** 还没填字段的点检项，仅用于 /config 提示，不参与查询。 */
    public List<String> pendingItems = new ArrayList<String>();

    /** 还没填字段的图片位，仅用于 /config 提示。 */
    public List<String> pendingImages = new ArrayList<String>();

    public boolean hasUnitField() {
        return unitField != null && !unitField.isEmpty();
    }

    public boolean hasResult() {
        return results != null && !results.isEmpty();
    }

    public boolean hasOffField() {
        return offField != null && !offField.isEmpty();
    }

    public boolean isOffValue(String raw) {
        return hasOffField() && raw != null && !raw.isEmpty() && offValues.contains(raw);
    }

    /** 只判「有没有记录」的记录表：没有结果字段，也不展开异常项。 */
    public boolean recordOnly() {
        return !hasResult() && (items == null || items.isEmpty());
    }

    public boolean containsMatch() {
        return "contains".equalsIgnoreCase(unitMatch);
    }

    /** 每天应检格数。 */
    public int required() {
        return units.size();
    }

    /** 单位字段 + 额外匹配字段，用于拼出包含匹配的文本。 */
    public List<String> matchColumns() {
        List<String> cols = new ArrayList<String>();
        if (hasUnitField()) {
            cols.add(unitField);
        }
        if (matchFields != null) {
            for (String f : matchFields) {
                if (f != null && !f.isEmpty() && !cols.contains(f)) {
                    cols.add(f);
                }
            }
        }
        return cols;
    }

    public Unit unit(String code) {
        if (code == null) {
            return null;
        }
        for (Unit u : units) {
            if (u.code.equals(code)) {
                return u;
            }
        }
        return null;
    }

    public ResultField resultField(String field) {
        if (field == null) {
            return null;
        }
        for (ResultField rf : results) {
            if (rf.field.equals(field)) {
                return rf;
            }
        }
        return null;
    }

    /**
     * 把一行记录的单位字段值映射到应检单位。
     *
     * <p>数组顺序与 {@link #matchColumns()} 一致：主字段优先，主字段认不出来才看附加字段。
     * 例如天车的「设备名称=2号天车、线别=1线」不是同一台，拼接后匹配会串台。</p>
     *
     * <p>没有单位字段的表（叉车、轮组、记录表）整表只有一格，任何记录都算这一格。</p>
     */
    public Unit matchUnit(String[] parts) {
        if (!hasUnitField()) {
            return units.isEmpty() ? null : units.get(0);
        }
        if (parts == null) {
            return null;
        }
        for (String v : parts) {
            Unit u = matchOne(v);
            if (u != null) {
                return u;
            }
        }
        return null;
    }

    private Unit matchOne(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) {
            return null;
        }
        if (!containsMatch()) {
            return unit(v);
        }
        for (Unit u : units) {
            for (String k : u.keywords) {
                if (k != null && !k.isEmpty() && v.contains(k)) {
                    return u;
                }
            }
        }
        for (Unit u : units) {
            if (!u.code.isEmpty() && v.contains(u.code)) {
                return u;
            }
        }
        return null;
    }

    public String labelOf(String code) {
        Unit u = unit(code);
        return u == null ? (code == null ? "" : code) : u.label;
    }

    public String valueLabel(String raw) {
        if (raw == null || results.isEmpty()) {
            return "";
        }
        String v = results.get(0).labels.get(raw);
        return v == null ? raw : v;
    }

    /** 第 index 个结果字段的正常值白名单。 */
    public List<String> normalOf(int index) {
        if (index < 0 || index >= results.size()) {
            return normal;
        }
        List<String> own = results.get(index).normal;
        return own == null || own.isEmpty() ? normal : own;
    }

    public String url(int id) {
        if (detailUrl == null || detailUrl.isEmpty()) {
            return "";
        }
        return detailUrl.replace("{id}", String.valueOf(id));
    }

    @Override
    public String toString() {
        return name + "(" + key + ")";
    }

    public static final class Unit {
        public String code = "";
        public String label = "";
        /** 包含匹配用的关键词，如「1」「一」「1号」「一号」。 */
        public List<String> keywords = new ArrayList<String>();

        public Unit() {
        }

        public Unit(String code, String label) {
            this.code = code;
            this.label = label;
        }

        @Override
        public String toString() {
            return label + "[" + code + "]";
        }
    }

    public static final class ResultField {
        public String field = "";
        public String label = "";
        /** 值 → 展示文案，仅用于明细。 */
        public Map<String, String> labels = new LinkedHashMap<String, String>();
        /** 该结果字段自己的正常值；为空回落到表单级 normal。 */
        public List<String> normal = new ArrayList<String>();
    }

    /**
     * 一个点检项。值不在 normal 白名单里即视为该项异常。
     */
    public static final class Item {
        public String field = "";
        public String label = "";
        /** 该项自己的图片字段，可为空。 */
        public String imageField = "";
        /** 该项自己的备注字段，可为空。 */
        public String remarkField = "";
        /** 备注列的展示名，默认「备注」；产线是「处理措施」。 */
        public String remarkLabel = "";
        /**
         * 该项自己的正常值白名单；为空时回落到表单级 normal。
         *
         * <p>有些表单不同点检项的选项 key 含义并不一致（例如焊机第 3 项把
         * 2=停机维修中、3=异常处理完，其余项相反），共用一个白名单会误判。</p>
         */
        public List<String> normal = new ArrayList<String>();
        /** 值表示不合格数量时，明细页显示成 ×N。 */
        public boolean count;
    }

    public static final class ImageField {
        public String field = "";
        public String label = "";
    }
}
