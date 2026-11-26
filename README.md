# 点检看板

泛微 E9（weaver-ecology）上的二次开发模块：按天读取 11 张建模点检表，折算成 **25 个应检格**，回答三个问题：

1. 今天哪些该检的设备/机台没人点检？
2. 哪些格子有异常、结果没填、或者属于补录？
3. 今天有哪些人真正填了表？

页面是一个单文件 HTML，通过 `/api/checkboard/page` 直接输出；数据接口是同一路径下的 JAX-RS 资源。

---

## 一、判定口径

一张表先折算成「应检单位清单」，每个单位每天算一格。

| 状态 | 判据 | 说明 |
|---|---|---|
| **done（已检）** | 该单位当天至少有 1 条记录 | 不看条数、班次、点检人 |
| **bad（异常）** | 任一结果字段非空，且不在正常值白名单里 | 多结果表任一异常即整格异常 |
| **empty（结果未填）** | 任一结果字段为空 | 单独标记，不算好也不算坏 |
| **off（未开线）** | 命中 `offField` 指定的 `offValues` | 仍计入已检，不再判结果未填或异常 |
| **late（补录）** | `modedatacreatedate > 点检日期` | 只标记，仍算完成 |
| **orphan（未登记编号）** | 单位字段的值匹配不上任何应检单位 | 只提示，不占格 |

- 有多个结果字段的表（天车、机械压力），**任一结果异常，该格就算异常**。明细里会分别显示「起重机：全部正常 · 钢丝绳：有不正常项」。
- 记录表（关键过程参数、异常品质、交接班）没有结果字段，只判「当天有没有记录」，卡片显示「已记录 / 未记录」。
- `total.bad` 是**异常格数**，不是异常记录条数。
- `total.missing` = 应检格 − 已检格；查询失败的表也计入应检，避免分母变小导致完成率虚高。

---

## 二、11 张表

| 表单 | 表 | 日期 | 时间 | 点检人 | 单位 | 结果字段 | 项 | 格 |
|---|---|---|---|---|---|---|---|---|
| 叉车日常点检 | `edc_uf_table1166` | `field007` | `field071` | `field006`（设备责任人） | 整表 1 格 | `field009` | 14 | 1 |
| 焊机点检 | `edc_uf_table1168` | `field002` | `field003` | `field001` | `field023`（0=1号，1=2号） | `field013` | 8 | 2 |
| 产线自检 | `edc_uf_table1165` | `field004` | `field010` | `field069` | `field008`（0~3 = 1~4 线） | `field029` | 10 | 4 |
| 天车点检 | `edc_uf_table1176` | `field041` | `field042` | `field001` | `field004` 文本 + `field006` 线别 | `field005` + `field028` | 14 | 3 |
| 机械压力点检 | `edc_uf_table1170` | `field007` | `field008` | `field001` | `field002` 文本 | `field009` + `field010` | 26 | 4 |
| 视觉设备点检 | `edc_uf_table1169` | `field004` | `field041` | `field001` | `field003`（0=开机，1=换线，2=交班） | `field002` | 11 | 3 |
| 辊压线设备点检 | `edc_uf_table1172` | `field023` | `field024` | `field001` | `field004` 文本 + `field002` | `field006` | 15 | 4 |
| 辊压轮组点检 | `edc_uf_table1180` | `field027` | `field028` | `field001` | 整表 1 格 | `field003` | 6 | 1 |
| 关键过程参数 | `edc_uf_table1175` | `field002` | `field003` | `field001` | 整表 1 格 | — | — | 1 |
| 异常品质反馈 | `edc_uf_table1174` | `field002` | `field003` | `field001` | 整表 1 格 | — | — | 1 |
| 车间交接班 | `edc_uf_table1173` | `field002` | `modedatacreatetime` | `modedatacreater` | 整表 1 格 | — | — | 1 |

合计 **25 格/天**。

几个必要的说明：

- **天车、机械压力的结果字段有两个**，配置里用 `results` 数组表达，不再只支持一个 `resultField`。
- **交接班主表没有「人员」列**：人员字段在子表 `edc_uf_table1173_dt1` 里，主表取 `modedatacreater`（提交人）作为点检人，时间取 `modedatacreatetime`。
- **叉车的人员字段已删除**：点检人改取原有的 `field006`（设备责任人）。
- **产线 10 项**按「不合格数量 + 处理措施」接：`field040`、`field048`~`field056`，对应措施 `field041`、`field057`~`field065`。`field013`~`field022` 是缺陷描述文本，不参与判定。
- 叉车的 14 项备注/图片字段按控件顺序一一配对（`field010/field042/field045` … `field037/field069/field070`）。

---

## 三、单位怎么匹配

### 精确匹配（`unitMatch: "exact"`）

下拉/单选框，单位字段的值直接等于 `units[].code`。焊机、产线、视觉用这种方式。

### 文本包含匹配（`unitMatch: "contains"`）

设备名称、机台号是自由文本，现场可能写 `1`、`1号`、`一号`、`一号线`，因此每个单位带一组关键词：

```json
{
  "unitField": "field002",
  "unitMatch": "contains",
  "units": [
    { "code": "1", "label": "1号压力机", "keywords": ["1", "一", "1号", "一号", "1线", "一线"] }
  ]
}
```

匹配规则：

1. **主字段优先**：先拿 `unitField` 的值匹配，匹配不上才看 `matchFields` 里的附加字段。
2. 这样「设备名称=2号天车、线别=1线」不会被拼接后串成 1 号天车。
3. 一个都匹配不上 → 计入 `orphan`，在卡片上显示「未登记编号 N 条」。

天车的配置是 `unitField: field004`（设备名称）+ `matchFields: ["field006"]`（线别）。原因是现有正式数据里设备名称统一写成「桥式起重机T」，只有线别能区分 1/2/3 号；设备名称按 1/2/3 写以后会自动优先按设备名称匹配。

---

## 四、接口

前缀：`/api/checkboard`（部署在 `/ec` 下就是 `/ec/api/checkboard`）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/summary?date=yyyy-MM-dd&force=1` | 看板主体：每张表的格子、汇总、人员清单 |
| GET | `/detail?form=&date=&unit=&limit=` | 明细；`unit` 留空 = 当天整表全部记录 |
| GET | `/config` | 配置自检：列是否存在、单位值域、未识别值 |
| GET | `/image?id=` | 图片代理：docid → `DocImageFile` 最新版本 → imagefileid |
| GET | `/imagedebug?id=` | 图片解析诊断：列出该 docid 的所有版本与最终选中的 imagefileid |
| GET | `/page` | 单文件看板页面 |

### 日期参数

- 留空取服务器当天。
- 必须是 `yyyy-MM-dd`，且日期本身存在；`2026-99-99`、`2026-02-30`、`2026-2-3` 一律返回 **HTTP 400**：

```json
{ "success": false, "error": "date 参数不合法：2026-99-99，应为 yyyy-MM-dd（例如 2026-11-09）" }
```

### summary

关键字段：

```json
{
  "success": true,
  "date": "2026-11-24",
  "total": { "required": 25, "done": 17, "missing": 8, "bad": 2, "late": 0, "records": 31 },
  "forms": [
    {
      "key": "crane", "name": "天车点检", "required": 3, "done": 3,
      "records": 4, "bad": 0, "empty": 0, "late": 0, "orphan": 0,
      "recordOnly": false,
      "cells": [
        { "code": "1", "label": "1号天车", "done": true, "count": 1, "bad": false, "last": "08:29" }
      ]
    }
  ],
  "inspectors": [
    { "id": "29240", "name": "张三", "count": 3, "forms": ["叉车日常点检", "天车点检"] }
  ],
  "inspectorMissing": 5,
  "configErrors": [],
  "reloadError": ""
}
```

- `inspectors` 是**当天所有表**的填写人汇总，按记录数倒序；`forms` 用于鼠标悬停时显示这人填了哪几张表。
- `inspectorMissing` 是没填点检人的记录条数，不计入人员清单。
- 概览缓存 5 秒；`force=1` 绕过缓存。

### detail

- `unit` 为空时返回当天整表记录（「查看全部数据」用的就是这个）。
- `limit` 默认 500，上限 2000。
- 单结果表仍返回 `result` / `resultLabel`；所有表都返回 `results` 数组：

```json
"results": [
  { "label": "起重机", "value": "0", "valueLabel": "全部正常", "abnormal": false },
  { "label": "钢丝绳", "value": "1", "valueLabel": "有不正常项", "abnormal": true }
]
```

`abnormalItems` 只列异常项；`itemTotal` 是该项表的点检项总数，用来判断「总结果异常但明细没填」。

---

## 五、页面

- 左侧是汇总卡 + 11 张表单卡片，右侧是**当天已填写人员**。
- 点任意已检格子 → 该单位当天明细。
- 卡片右下角「**查看全部数据**」→ 该表当天所有记录，包含没有匹配上单位的记录，用来核对遗漏。
- 明细里的图片可以直接点开放大；`imageUrl` 统一走 `/api/checkboard/image?id={id}`（`{id}` 是表单里的 docid，服务端再解析成 imagefileid）。
- 切日期、连开两个明细都有请求序号保护，旧响应不会覆盖新内容。
- 卡片脚注：记录条数、补录格数、未登记编号条数、结果未填格数。

### 图片解析

表单图片字段存的是 `docid`，不是 `imagefileid`，解析只有一条路：

`docid` → `DocImageFile.docid` → 该 docid 的最新版本（`versionid` 最大，相同则 `id` 最大）→ `imagefileid` → `ImageFile`。

- 不按 `imagefileid` 直读，也不看 `DocImageFile.id`；同号的 `ImageFile` 可能是别的附件。
- `imagefileid` 可能为负数（E9 表单附件会出现 `-5` 这类值）；0 表示没有文件，按 404 处理。
- 最新版本读不出来、或不是 `image/*` 时直接 404，不回退到旧版本，更不会把 PDF/Excel 当图片返回。
- `/imagedebug?id=<docid>` 会列出该 docid 的所有版本和最终选中的 `imagefileid`，排障用。

---

## 六、配置

配置文件采用「总控 + 环境分离」结构，仅支持 `.yaml` 格式：

- `checkboard.yaml`：总控配置，通过 `active` 选择启用的环境（`test` / `prod`），由 `profiles` 映射到对应配置文件。
- `checkboard-prod.yaml`：正式服 11 张点检表配置（`title: 点检看板`）。
- `checkboard-test.yaml`：测试服 11 张点检表配置（`title: 点检看板（测试服）`），与正式服隔离，可独立修改测试。

查找顺序：

1. `{weaver.root}/classbean/com/dt/checkboard/checkboard.yaml`
2. classpath 下 `/com/dt/checkboard/checkboard.yaml`

总控文件与当前激活的环境配置文件均每 5 秒检查一次路径与 mtime，**切换 `active` 或改环境配置都不需要重启**。新配置校验不通过时保留上一版，错误通过页面横幅和 `/config` 暴露。

### 表单级字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `key` / `name` / `table` | 是 | 唯一标识、展示名、建模表名 |
| `dateField` / `timeField` / `inspectorField` | 是 | 日期、时间、点检人字段 |
| `unitField` | 否 | 单位字段；留空表示整表 1 格 |
| `unitMatch` | 否 | `exact`（默认）/ `contains` |
| `matchFields` | 否 | 参与包含匹配的附加文本字段 |
| `offField` | 否 | 未开线/停线开关字段；命中 `offValues` 时不判结果未填或异常 |
| `offValues` | 配了 `offField` 必填 | 视作未开线的值列表（如 `["1", "未开线"]`） |
| `offLabel` | 否 | 看板与明细展示文案（默认 `未开线`） |
| `units[]` | 是 | `code`、`label`、`keywords`（contains 用） |
| `results[]` | 否 | `field`、`label`、`labels`、`normal`；支持多个 |
| `normal` | 结果表必填 | 全局正常值白名单 |
| `items[]` | 否 | `field`、`label`、`normal`、`remarkField`、`remarkLabel`、`imageField`、`count` |
| `images[]` | 否 | 整单图片位 |
| `imageUrl` | 配了图片必填 | 推荐 `{ctx}/api/checkboard/image?id={id}` |

`items[].count: true` 表示该值是「不合格数量」，明细显示成 `× 2`。

### 维护方式

`checkboard-prod.yaml` / `checkboard-test.yaml` 现在由人工维护，不再依赖生成脚本；`checkboard.yaml` 仅负责通过 `active` 切换启用哪一套。

表单改了以后：

1. 按 E9 建模表单里的实际字段名改对应环境配置文件里的 `forms[].items[].field`、`imageField`、`remarkField`，以及 `results[].field`。
2. 同步改 `units`、`results[].labels`、`normal`；新增单位时把 `code` / `label` / `keywords` 一起补上。
3. 保存后 5 秒内自动热加载；打开 `/api/checkboard/config` 确认 `profile` 正确、`errors` 为空、每张表 `columnsOk:true`、`unexpectedUnits` 为空。
4. 用目标日期的 `/summary` 和 `/detail` 核对格子数、明细和图片。

配置校验不通过时会保留上一版，错误会显示在页面横幅和 `/config` 里。

---

## 七、部署

1. 编译（需要 `dt-common.jar` 与 `dt-snakeyaml-2.7.jar`，和 ecology 依赖放在一起）：

   ```bash
   javac -encoding UTF-8 -source 1.8 -target 1.8 -cp "F:/fwLib/*" -d out $(find src -name "*.java")
   ```

2. 把 `dt-snakeyaml-2.7.jar` 放到服务器 `ecology/WEB-INF/lib/` 下（若已放过则跳过）。

3. 确认 `checkboard.yaml` 中的 `active` 与目标服务器一致（测试服设为 `test`，正式服设为 `prod`）。

4. 把 `out/com` 覆盖到 `ecology/classbean/com`。

5. 确认非 Java 文件真的进了 classbean：

   - `classbean/com/dt/checkboard/checkboard.yaml`（总控）
   - `classbean/com/dt/checkboard/checkboard-prod.yaml`（正式服）
   - `classbean/com/dt/checkboard/checkboard-test.yaml`（测试服）
   - `classbean/com/dt/checkboard/index.html`

   IDE 经常不复制非 java 文件，覆盖 `com` 后单独看一眼。

6. （可选）执行 `table_sql/checkboard_index.sql`，给 11 张表的日期字段补索引。索引已存在会报 1061，忽略即可。

7. 重启 E9（首次加入 `dt-snakeyaml-2.7.jar` 或改了 Java class 需要重启；以后只改 YAML 或切换 `active` 不用重启）。

8. 验收顺序：

   - `/api/checkboard/config`：`profile` 与预期环境一致、`errors=[]`，11 张表 `columnsOk:true`，`unexpectedUnits` 为空。
   - `/api/checkboard/page?date=2026-11-24`：应为 **17/25**（叉车 1、焊机 2、产线 3、天车 3、压力 2、视觉 1、辊压线设备 3、关键参数 1、异常品质 1，其余 0）。
   - 右侧人员栏应出现当天填过表的人。

---

## 八、排障

| 现象 | 先看什么 |
|---|---|
| 11 张表全部 0/25 | 日期格式、`/config` 的 `errors`；页面横幅有没有配置错误 |
| 某张表「查询失败」 | `/config` 里这张表的 `columnsOk` / `columnError`；字段是否随表单改过 |
| 天车一直 0/3 | 设备名称、线别里有没有 1/2/3 或 一/二/三；`unexpectedUnits` 会列出实际值 |
| 压力/辊压线设备缺格 | 设备编号/机台号是不是空值或写了配置外的编号；看 `orphan` |
| 卡片显示「未登记编号」 | 单位值没匹配上；改 `units[].keywords` 或规范表单填写 |
| 完成率看起来偏高 | 某张表被配置校验拒绝，分母变小；看 `configErrors` |
| 人员栏为空 | 点检人字段是否为空；`inspectorMissing` 会不会等于记录数 |
| 图片 404 / 显示成别的图 | 字段里存的是 docid；`/image` 只按 `DocImageFile.docid` 取最新版本，用 `/imagedebug?id=N` 看该 docid 的版本和最终 imagefileid |
| `/config` 返回登录页或 401 | E9 的 `/api` 鉴权没放行当前用户，不是代码问题 |

---

## 九、已知限制

- **图片接口没有鉴权**：`/image?id=N` 能读 `imagefile` 里任意文件；已加 20MB 读取上限，但仍应加白名单（校验 id 出现在点检表图片列里）。
- **没有节假日/停产日历**：周末、停产日会显示 0/25，时间久了红色会被忽略。
- **补录仍算完成**：`late` 只做标记，不影响完成判定；如果口径是「当天按时完成率」，需要另算。
- **交接班的人员取提交人**：主表没有人员列，子表的人员不参与统计；一人代填多个工位时按多条记录计入。
- **天车设备名称现状**：正式数据里 11 条记录的设备名称都是「桥式起重机T」，当前靠线别区分 1/2/3 号；设备名称按「1号/二号」填写后会优先按设备名称匹配。
- **明细上限 500 条**（`limit` 最大 2000），超过会在标题下提示。
- **`/config` 会做多次值域抽样**，属于诊断接口，不要拿去轮询。
- **产线第 10 行「其他」**：表单脚本的 UUID 疑似写反（countId 指到 `field022`，solutionId 指到 `field056`），配置按控件结构取 `field056` / `field065`。数据里 11-04 有一条 `field056=2` 但总结果=0，是表单脚本的问题。
- **视觉设备样本只有「开机」**：另外两个时刻（换线、交班）没有历史数据，字段和选项已核对，但还没有真实数据验证。
- Java 侧错误信息会把 SQL 和服务器路径带回前端，正式环境如果对外暴露，建议在网关层统一脱敏。

---

## 十、目录

```
src/com/api/checkboard/BoardApi.java        REST 入口：summary/detail/config/image/imagedebug/page
src/com/dt/checkboard/
  config/FormSpec.java                      单表契约（单位、结果、点检项）
  config/FormConfig.java                    配置加载、校验、5 秒热加载
  dao/CheckBoardDao.java                    当天原始行、明细、列校验
  service/BoardService.java                 格子判定、人员汇总、配置自检
  entity/                                   DayRow / CheckRecord / UnitStat
  checkboard.yaml                           总控配置（active 切换 prod/test）
  checkboard-prod.yaml                      正式服看板配置
  checkboard-test.yaml                      测试服看板配置
  index.html                                单文件看板
正式服点检表数据/                            正式服 E9 导出，仅作字段对照（不再参与生成）
测试服点检表数据/                            测试服 E9 导出，仅作字段对照（不再参与生成）
table_sql/checkboard_index.sql              11 张表的日期索引
```
# CheckBoards
