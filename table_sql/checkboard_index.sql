-- 点检看板：为 11 张点检表的日期字段补索引
-- 背景：这些建模表默认只有主键，看板按日期过滤会全表扫描；表越大越慢，每天还会被多次查询。
-- 说明：索引名重复（MySQL 1061 Duplicate key name）说明已经建过，忽略即可。
-- 注意：泛微建模重建表可能丢索引，重建后需要重跑本脚本。

CREATE INDEX idx_cb_1166_date ON edc_uf_table1166 (field007);   -- 叉车日常点检
CREATE INDEX idx_cb_1168_date ON edc_uf_table1168 (field002);   -- 焊机点检
CREATE INDEX idx_cb_1165_date ON edc_uf_table1165 (field004);   -- 产线自检
CREATE INDEX idx_cb_1176_date ON edc_uf_table1176 (field041);   -- 天车点检
CREATE INDEX idx_cb_1170_date ON edc_uf_table1170 (field007);   -- 机械压力设备点检
CREATE INDEX idx_cb_1169_date ON edc_uf_table1169 (field004);   -- 辊压线视觉设备点检
CREATE INDEX idx_cb_1172_date ON edc_uf_table1172 (field023);   -- 辊压线设备点检
CREATE INDEX idx_cb_1180_date ON edc_uf_table1180 (field027);   -- 辊压轮组点检
CREATE INDEX idx_cb_1175_date ON edc_uf_table1175 (field002);   -- 关键过程参数监控记录
CREATE INDEX idx_cb_1174_date ON edc_uf_table1174 (field002);   -- 异常品质不良反馈及对策
CREATE INDEX idx_cb_1173_date ON edc_uf_table1173 (field002);   -- 车间交接班记录
