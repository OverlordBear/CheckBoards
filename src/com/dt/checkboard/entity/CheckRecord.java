package com.dt.checkboard.entity;

/**
 * 一条点检记录，只用于明细展示。
 */
public class CheckRecord {

    public int id;
    public String time = "";
    public String inspectorId = "";
    public String inspector = "";
    public String unit = "";
    /** 各匹配字段的原始值，顺序与 FormSpec.matchColumns() 一致。 */
    public String[] unitParts = new String[0];
    /** 单个结果字段时的值；多结果表见 resultValues。 */
    public String result = "";
    public String createdDate = "";

    /** 与 FormSpec.results 一一对应的结果值。 */
    public String[] resultValues = new String[0];

    public boolean abnormal;
    public boolean emptyResult;
    public boolean late;

    /** 与 FormSpec.items 一一对应的原始值。 */
    public String[] itemValues = new String[0];

    /** 与 FormSpec.items 一一对应的图片原始值。 */
    public String[] itemImages = new String[0];

    /** 与 FormSpec.items 一一对应的备注原文。 */
    public String[] itemRemarks = new String[0];

    /** 与 FormSpec.images 一一对应的原始值。 */
    public String[] imageValues = new String[0];
}
