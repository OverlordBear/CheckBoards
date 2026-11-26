package com.dt.checkboard.entity;

/**
 * 概览用的一行原始记录。
 *
 * <p>只取判定需要的列：单位、时间、结果、创建日期、点检人。
 * 一张表一天通常只有几十行，聚合放在内存里做，比在 SQL 里拼多重 SUM(CASE)
 * 更容易支持「多个结果字段」和「文本单位包含匹配」。</p>
 */
public final class DayRow {

    /** 单位字段原始值（拼接后），仅用于展示和日志。 */
    public String unit = "";
    /** 各匹配字段的原始值，顺序与 FormSpec.matchColumns() 一致，主字段在前。 */
    public String[] unitParts = new String[0];
    /** 点检时间原始值。 */
    public String time = "";
    /** 各个结果字段的原始值，顺序与 FormSpec.results 一致。 */
    public String[] results = new String[0];
    /** 是否未开线/停产。 */
    public boolean off;
    /** modedatacreatedate，用于判断补录。 */
    public String createdDate = "";
    public String inspectorId = "";
    public String inspector = "";
}
