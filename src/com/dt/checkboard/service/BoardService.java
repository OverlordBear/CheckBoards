package com.dt.checkboard.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.dt.checkboard.config.FormConfig;
import com.dt.checkboard.config.FormSpec;
import com.dt.checkboard.dao.CheckBoardDao;
import com.dt.checkboard.entity.CheckRecord;
import com.dt.checkboard.entity.DayRow;
import com.dt.checkboard.entity.UnitStat;
import com.dt.common.util.LogUtil;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 看板业务层。
 *
 * <p>所有表单共用一条判定：应检单位当天有记录即完成。异常只由结果字段的白名单判定，
 * 与点检人、班次、记录条数无关。</p>
 */
public class BoardService {

    private static final int DETAIL_LIMIT = 500;

    /** 概览缓存时长：挡住重复打开页面和连点，手动刷新会绕过。 */
    private static final long CACHE_TTL_MS = 5000L;
    private static final int CACHE_MAX = 64;
    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<String, CacheEntry>();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern HHMM = Pattern.compile("(\\d{1,2}):(\\d{2})");

    private final CheckBoardDao dao = new CheckBoardDao();

    /**
     * @param force true 表示绕过缓存，用于用户主动点刷新
     */
    public JSONObject summary(String date, boolean force) {
        FormConfig.Snapshot snap = FormConfig.get();
        if (!force) {
            CacheEntry entry = CACHE.get(date);
            if (entry != null && entry.expireAt > System.currentTimeMillis()
                    && entry.version == snap.version) {
                return entry.data;
            }
        }
        JSONObject res = buildSummary(date, snap);
        if (CACHE.size() >= CACHE_MAX) {
            CACHE.clear();
        }
        CACHE.put(date, new CacheEntry(res, System.currentTimeMillis() + CACHE_TTL_MS, snap.version));
        return res;
    }

    private JSONObject buildSummary(String date, FormConfig.Snapshot snap) {
        JSONArray forms = new JSONArray();
        int required = 0;
        int done = 0;
        int bad = 0;
        int off = 0;
        int late = 0;

        Map<String, Person> people = new LinkedHashMap<String, Person>();
        int personRows = 0;
        int missingInspector = 0;

        for (FormSpec f : snap.forms) {
            JSONObject item = new JSONObject();
            item.put("key", f.key);
            item.put("name", f.name);
            item.put("table", f.table);
            item.put("required", f.required());
            item.put("recordOnly", f.recordOnly());
            // 查询失败也要计入应检，否则分母变小会让完成率虚高
            required += f.required();

            int fDone = 0;
            int fBad = 0;
            int fEmpty = 0;
            int fOff = 0;
            int fLate = 0;
            int fRecords = 0;
            int orphan = 0;
            JSONArray missing = new JSONArray();
            JSONArray cells = new JSONArray();

            try {
                List<DayRow> rows = dao.rows(f, date);
                Map<String, UnitStat> byUnit = new LinkedHashMap<String, UnitStat>();
                for (DayRow row : rows) {
                    // 先记人再看单位：单位认不出来（orphan）的记录同样是人填的
                    collectPerson(people, row, f.name);
                    personRows++;
                    if (row.inspectorId.isEmpty() || "0".equals(row.inspectorId)) {
                        missingInspector++;
                    }
                    FormSpec.Unit u = f.matchUnit(row.unitParts);
                    if (u == null) {
                        orphan++;
                        continue;
                    }
                    UnitStat s = byUnit.get(u.code);
                    if (s == null) {
                        s = new UnitStat();
                        s.unit = u.code;
                        byUnit.put(u.code, s);
                    }
                    s.count++;
                    int m = minutes(row.time);
                    if (m >= s.lastMinutes) {
                        s.lastMinutes = m;
                        s.last = row.time;
                    }
                    boolean rowBad = false;
                    boolean rowEmpty = false;
                    if (row.off) {
                        s.off++;
                    } else {
                        for (int i = 0; i < f.results.size(); i++) {
                            String v = i < row.results.length ? row.results[i] : "";
                            if (v.isEmpty()) {
                                rowEmpty = true;
                            } else if (!f.normalOf(i).contains(v)) {
                                rowBad = true;
                            }
                        }
                    }
                    if (rowBad) {
                        s.bad++;
                    }
                    if (rowEmpty) {
                        s.empty++;
                    }
                    if (!row.createdDate.isEmpty() && row.createdDate.compareTo(date) > 0) {
                        s.late++;
                    }
                }

                for (FormSpec.Unit u : f.units) {
                    UnitStat s = byUnit.get(u.code);
                    boolean isDone = s != null;
                    boolean isOff = isDone && s.off > 0 && s.off == s.count;
                    JSONObject c = new JSONObject();
                    c.put("code", u.code);
                    c.put("label", u.label);
                    c.put("done", isDone);
                    c.put("count", isDone ? s.count : 0);
                    c.put("bad", isDone && s.bad > 0);
                    c.put("empty", isDone && s.empty > 0);
                    c.put("off", isOff);
                    if (isOff) {
                        c.put("offLabel", f.offLabel);
                    }
                    c.put("late", isDone && s.late > 0);
                    c.put("last", isDone ? shortTime(s.last) : "");
                    cells.add(c);

                    if (isDone) {
                        fDone++;
                        fRecords += s.count;
                        if (s.bad > 0) {
                            fBad++;
                        }
                        if (s.empty > 0) {
                            fEmpty++;
                        }
                        if (isOff) {
                            fOff++;
                        }
                        if (s.late > 0) {
                            fLate++;
                        }
                    } else {
                        missing.add(u.label);
                    }
                }

                done += fDone;
                bad += fBad;
                off += fOff;
                late += fLate;

                item.put("done", fDone);
                item.put("records", fRecords);
                item.put("bad", fBad);
                item.put("empty", fEmpty);
                item.put("off", fOff);
                if (fOff > 0) {
                    item.put("offLabel", f.offLabel);
                }
                item.put("late", fLate);
                item.put("orphan", orphan);
                item.put("missing", missing);
                item.put("cells", cells);
            } catch (Exception e) {
                item.put("error", message(e));
                item.put("done", 0);
                item.put("records", 0);
                item.put("bad", 0);
                item.put("off", 0);
                item.put("late", 0);
                item.put("orphan", 0);
                item.put("missing", new JSONArray());
                item.put("cells", cells);
                LogUtil.error("表单 " + f.key + " 统计失败", e);
            }
            forms.add(item);
        }

        JSONObject total = new JSONObject();
        total.put("required", required);
        total.put("done", done);
        total.put("missing", Math.max(0, required - done));
        total.put("bad", bad);
        total.put("off", off);
        total.put("late", late);
        total.put("records", personRows);

        JSONObject res = new JSONObject();
        res.put("success", true);
        res.put("title", snap.title);
        res.put("profile", snap.profile);
        res.put("date", date);
        res.put("source", snap.source);
        res.put("loadedAt", snap.loadedAt);
        res.put("generatedAt", LocalTime.now().format(TIME));
        res.put("total", total);
        res.put("forms", forms);
        res.put("inspectors", peopleArray(people));
        res.put("inspectorMissing", missingInspector);
        res.put("configErrors", strings(snap.errors));
        res.put("reloadError", FormConfig.reloadError());
        return res;
    }

    private static void collectPerson(Map<String, Person> people, DayRow row, String formName) {
        String id = row.inspectorId == null ? "" : row.inspectorId.trim();
        String name = row.inspector == null ? "" : row.inspector.trim();
        if (id.isEmpty() || "0".equals(id)) {
            if (name.isEmpty()) {
                return;
            }
            id = "name:" + name;
        }
        Person p = people.get(id);
        if (p == null) {
            p = new Person();
            p.id = id;
            p.name = name.isEmpty() ? ("ID " + id) : name;
            people.put(id, p);
        }
        p.count++;
        p.forms.add(formName);
    }

    private static JSONArray peopleArray(Map<String, Person> people) {
        List<Person> list = new ArrayList<Person>(people.values());
        Collections.sort(list, new Comparator<Person>() {
            @Override
            public int compare(Person a, Person b) {
                if (a.count != b.count) {
                    return b.count - a.count;
                }
                return a.name.compareTo(b.name);
            }
        });
        JSONArray arr = new JSONArray();
        for (Person p : list) {
            JSONObject o = new JSONObject();
            o.put("id", p.id);
            o.put("name", p.name);
            o.put("count", p.count);
            JSONArray fs = new JSONArray();
            for (String s : p.forms) {
                fs.add(s);
            }
            o.put("forms", fs);
            arr.add(o);
        }
        return arr;
    }

    public JSONObject detail(String key, String date, String unit, int limit) {
        JSONObject res = new JSONObject();
        FormSpec f = FormConfig.form(key);
        if (f == null) {
            res.put("success", false);
            res.put("error", "没有这张表单: " + key);
            return res;
        }
        int max = limit > 0 ? Math.min(limit, 2000) : DETAIL_LIMIT;
        try {
            List<CheckRecord> rows = dao.detail(f, date, unit, max);
            JSONArray records = new JSONArray();
            for (CheckRecord r : rows) {
                JSONObject o = new JSONObject();
                o.put("id", r.id);
                o.put("time", shortTime(r.time));
                o.put("inspector", r.inspector.isEmpty() ? r.inspectorId : r.inspector);
                o.put("unit", r.unit);
                o.put("unitLabel", r.unit.isEmpty() || !f.hasUnitField() ? "" : f.labelOf(r.unit));
                o.put("abnormal", r.abnormal);
                o.put("empty", r.emptyResult);
                o.put("off", r.off);
                if (r.off) {
                    o.put("offLabel", f.offLabel);
                }
                o.put("late", r.late);
                o.put("createdDate", r.createdDate);

                JSONArray results = new JSONArray();
                for (int i = 0; i < f.results.size(); i++) {
                    FormSpec.ResultField rf = f.results.get(i);
                    String v = i < r.resultValues.length ? r.resultValues[i] : "";
                    String label = rf.labels.get(v);
                    JSONObject ro = new JSONObject();
                    ro.put("label", rf.label);
                    ro.put("value", v);
                    ro.put("valueLabel", label == null ? v : label);
                    ro.put("abnormal", !r.off && !v.isEmpty() && !f.normalOf(i).contains(v));
                    results.add(ro);
                }
                if (!results.isEmpty()) {
                    o.put("results", results);
                    if (results.size() == 1) {
                        o.put("result", results.getJSONObject(0).getString("value"));
                        o.put("resultLabel", results.getJSONObject(0).getString("valueLabel"));
                    }
                }

                JSONArray abnormalItems = new JSONArray();
                if (!r.off) {
                    for (int i = 0; i < f.items.size(); i++) {
                        FormSpec.Item item = f.items.get(i);
                        String v = i < r.itemValues.length ? r.itemValues[i] : "";
                        List<String> itemNormal = item.normal.isEmpty() ? f.normal : item.normal;
                        if (v.isEmpty() || itemNormal.contains(v)) {
                            continue;
                        }
                        JSONObject it = new JSONObject();
                        it.put("label", item.label);
                        it.put("value", v);
                        it.put("count", item.count);
                        it.put("remarkLabel", item.remarkLabel);
                        String rawImage = i < r.itemImages.length ? r.itemImages[i] : "";
                        JSONArray itemPics = images(rawImage, f.imageUrl);
                        if (!itemPics.isEmpty()) {
                            it.put("images", itemPics);
                        }
                        String itemRemark = i < r.itemRemarks.length ? r.itemRemarks[i] : "";
                        if (!itemRemark.isEmpty()) {
                            it.put("remark", itemRemark);
                        }
                        abnormalItems.add(it);
                    }
                }
                o.put("abnormalItems", abnormalItems);
                o.put("itemTotal", f.items.size());

                JSONArray pics = new JSONArray();
                for (String rawImage : r.imageValues) {
                    JSONArray one = images(rawImage, f.imageUrl);
                    for (int k = 0; k < one.size(); k++) {
                        pics.add(one.getString(k));
                    }
                }
                if (!pics.isEmpty()) {
                    o.put("images", pics);
                }

                String url = f.url(r.id);
                if (!url.isEmpty()) {
                    o.put("url", url);
                }
                records.add(o);
            }
            res.put("success", true);
            res.put("key", f.key);
            res.put("name", f.name);
            res.put("date", date);
            res.put("recordOnly", f.recordOnly());
            res.put("unit", unit == null ? "" : unit);
            res.put("unitLabel", unit == null || unit.isEmpty() ? "" : f.labelOf(unit));
            res.put("limit", max);
            res.put("pendingItems", strings(f.pendingItems));
            res.put("records", records);
            return res;
        } catch (Exception e) {
            res.put("success", false);
            res.put("error", message(e));
            return res;
        }
    }

    /**
     * 配置自检：字段是否存在、实际值域与配置是否一致。让"配置错"在页面上可见。
     */
    public JSONObject config() {
        FormConfig.Snapshot snap = FormConfig.get();
        String from = LocalDate.now().minusDays(30).toString();
        JSONArray forms = new JSONArray();

        for (FormSpec f : snap.forms) {
            JSONObject item = new JSONObject();
            item.put("key", f.key);
            item.put("name", f.name);
            item.put("table", f.table);
            item.put("required", f.required());
            item.put("units", f.units.size());
            item.put("unitField", f.unitField);
            item.put("unitMatch", f.unitMatch);
            item.put("matchFields", strings(f.matchFields));
            JSONArray rfs = new JSONArray();
            for (FormSpec.ResultField rf : f.results) {
                rfs.add(rf.field + "(" + rf.label + ")");
            }
            item.put("resultFields", rfs);
            item.put("normal", strings(f.normal));
            item.put("pendingItems", strings(f.pendingItems));
            item.put("pendingImages", strings(f.pendingImages));

            String columnError = dao.checkColumns(f);
            item.put("columnsOk", columnError.isEmpty());
            if (!columnError.isEmpty()) {
                item.put("columnError", columnError);
            } else {
                JSONArray unexpected = new JSONArray();
                JSONArray unitValues = new JSONArray();
                if (f.hasUnitField()) {
                    for (String[] v : dao.unitValueCounts(f, from, 30)) {
                        JSONObject o = new JSONObject();
                        o.put("value", v[0]);
                        o.put("count", v[1]);
                        unitValues.add(o);
                        if (f.matchUnit(v[0].split("\\s+")) == null) {
                            unexpected.add(v[0] + "（" + v[1] + " 条）");
                        }
                    }
                }
                item.put("unitValues", unitValues);
                item.put("unexpectedUnits", unexpected);

                JSONArray resultValues = new JSONArray();
                for (FormSpec.ResultField rf : f.results) {
                    JSONObject ro = new JSONObject();
                    ro.put("field", rf.field);
                    ro.put("label", rf.label);
                    ro.put("values", values(dao.distinct(f, rf.field, from, 20)));
                    resultValues.add(ro);
                }
                item.put("resultValues", resultValues);
            }
            forms.add(item);
        }

        JSONObject res = new JSONObject();
        res.put("success", true);
        res.put("title", snap.title);
        res.put("profile", snap.profile);
        res.put("source", snap.source);
        res.put("loadedAt", snap.loadedAt);
        res.put("errors", strings(snap.errors));
        res.put("reloadError", FormConfig.reloadError());
        res.put("forms", forms);
        return res;
    }

    private static JSONArray values(List<String[]> list) {
        JSONArray arr = new JSONArray();
        for (String[] v : list) {
            JSONObject o = new JSONObject();
            o.put("value", v[0]);
            o.put("count", v[1]);
            arr.add(o);
        }
        return arr;
    }

    static JSONArray strings(List<String> list) {
        JSONArray arr = new JSONArray();
        if (list == null) {
            return arr;
        }
        for (String s : list) {
            arr.add(s);
        }
        return arr;
    }

    static String shortTime(String t) {
        if (t == null) {
            return "";
        }
        String s = t.trim();
        if (s.length() >= 16 && s.charAt(10) == ' ') {
            return s.substring(11, 16);
        }
        if (s.length() >= 5 && s.charAt(2) == ':') {
            return s.substring(0, 5);
        }
        return s;
    }

    /** 从 "10:31" / "2026-11-09 10:31:00" 里取出当天分钟数，用于比较最晚时间。 */
    private static int minutes(String t) {
        if (t == null) {
            return -1;
        }
        Matcher m = HHMM.matcher(t);
        if (!m.find()) {
            return -1;
        }
        int h = Integer.parseInt(m.group(1));
        int mi = Integer.parseInt(m.group(2));
        return h * 60 + mi;
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private static final Pattern FILE_ID = Pattern.compile("\"(?:fileid|fileId|id)\"\\s*:\\s*\"?(\\d+)");
    private static final Pattern SPLIT = Pattern.compile("[,;|\\s]+");

    /**
     * 把图片字段的原始值转成可访问地址。
     *
     * <p>兼容三种存法：纯文件 id（123）、逗号分隔的 id 列表、泛微的 JSON 串；
     * 已经是 http(s) 或 / 开头的地址则原样使用。最多取 9 张。</p>
     */
    static JSONArray images(String raw, String template) {
        JSONArray arr = new JSONArray();
        if (raw == null || raw.isEmpty() || template == null || template.isEmpty()) {
            return arr;
        }
        List<String> tokens = new ArrayList<String>();
        String s = raw.trim();
        if (s.indexOf('{') >= 0) {
            Matcher m = FILE_ID.matcher(s);
            while (m.find()) {
                tokens.add(m.group(1));
            }
        } else {
            for (String t : SPLIT.split(s)) {
                if (!t.isEmpty()) {
                    tokens.add(t);
                }
            }
        }
        for (String t : tokens) {
            String url;
            if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("/")) {
                url = t;
            } else if (t.matches("\\d+")) {
                url = template.replace("{id}", t);
            } else {
                continue;
            }
            arr.add(url);
            if (arr.size() >= 9) {
                break;
            }
        }
        return arr;
    }

    private static final class Person {
        String id = "";
        String name = "";
        int count;
        Set<String> forms = new LinkedHashSet<String>();
    }

    private static final class CacheEntry {
        final JSONObject data;
        final long expireAt;
        final long version;

        CacheEntry(JSONObject data, long expireAt, long version) {
            this.data = data;
            this.expireAt = expireAt;
            this.version = version;
        }
    }
}
