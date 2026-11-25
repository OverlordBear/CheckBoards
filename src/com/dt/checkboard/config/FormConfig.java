package com.dt.checkboard.config;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.dt.common.util.LogUtil;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/*
 * 看板配置加载器。
 * <p>查找顺序：{weaver.root}/classbean/com/dt/checkboard/ 下依次查找
 * checkboard.yaml → checkboard.yml → checkboard.json，均不存在时回退到
 * classpath:/com/dt/checkboard/ 下同顺序查找。</p>
 *
 * <p>支持总控模式与单文件模式：若 YAML 包含 {@code active} / {@code profiles}，则视为总控配置，
 * 根据 {@code active} 映射到同目录（或 classpath）下的目标配置文件（如 {@code checkboard-prod.yaml}、
 * {@code checkboard-test.yaml}）；若直接包含 {@code forms}，则按单文件直接解析。</p>
 *
 * <p>支持 5 秒热加载：同时监听总控文件与当前激活的目标配置文件，任一变化都会重新加载；
 * 新配置校验不通过时保留上一版，并把错误通过 /config 和页面横幅暴露出来。</p>
 */
public final class FormConfig {

    public static final String FILE_NAME = "checkboard.json";
    private static final String[] FILE_NAMES = {"checkboard.yaml", "checkboard.yml", FILE_NAME};

    private static final long CHECK_INTERVAL_MS = 5000L;

    private static volatile Snapshot current;
    private static volatile long lastCheckAt;
    private static volatile String failedMasterKey = "";
    private static volatile long failedMasterMtime;
    private static volatile String failedTargetName = "";
    private static volatile String failedTargetKey = "";
    private static volatile long failedTargetMtime;
    private static volatile String reloadError = "";
    private static final AtomicLong VERSION = new AtomicLong();

    static {
        current = load(resolveMaster());
        LogUtil.info("看板配置: " + current.source + "，表单 " + current.forms.size() + " 张"
                + (current.errors.isEmpty() ? "" : "，配置错误 " + current.errors.size() + " 条"));
    }

    private FormConfig() {
    }

    public static Snapshot get() {
        long now = System.currentTimeMillis();
        if (now - lastCheckAt >= CHECK_INTERVAL_MS) {
            synchronized (FormConfig.class) {
                if (now - lastCheckAt >= CHECK_INTERVAL_MS) {
                    lastCheckAt = now;
                    reloadIfChanged();
                }
            }
        }
        return current;
    }

    public static List<FormSpec> forms() {
        return get().forms;
    }

    public static FormSpec form(String key) {
        if (key == null) {
            return null;
        }
        for (FormSpec f : forms()) {
            if (f.key.equals(key)) {
                return f;
            }
        }
        return null;
    }

    public static String reloadError() {
        return reloadError;
    }

    private static void reloadIfChanged() {
        ConfigSource master = resolveMaster();
        if (master == null) {
            if ("none".equals(current.masterKey) || "none".equals(failedMasterKey)) {
                return;
            }
            Snapshot next = load(null);
            recordFailed(null, next);
            return;
        }

        boolean masterMatchesCurrent = master.key.equals(current.masterKey)
                && master.mtime == current.masterMtime;
        boolean targetMatchesCurrent = current.targetName.isEmpty()
                || sameSource(resolveFileByName(current.targetName), current.targetKey, current.targetMtime);
        if (failedMasterKey.isEmpty() && masterMatchesCurrent && targetMatchesCurrent) {
            return;
        }

        if (!failedMasterKey.isEmpty()
                && master.key.equals(failedMasterKey)
                && master.mtime == failedMasterMtime
                && (failedTargetName.isEmpty()
                || sameSource(resolveFileByName(failedTargetName), failedTargetKey, failedTargetMtime))) {
            return;
        }

        Snapshot next = load(master);
        if (next.errors.isEmpty() && !next.forms.isEmpty()) {
            current = next;
            clearFailed();
            LogUtil.info("看板配置已热加载: " + next.source + "，表单 " + next.forms.size() + " 张");
        } else {
            recordFailed(master, next);
        }
    }

    private static boolean sameSource(ConfigSource src, String key, long mtime) {
        if (src == null) {
            return "none".equals(key);
        }
        return src.key.equals(key) && src.mtime == mtime;
    }

    private static void clearFailed() {
        failedMasterKey = "";
        failedMasterMtime = 0L;
        failedTargetName = "";
        failedTargetKey = "";
        failedTargetMtime = 0L;
        reloadError = "";
    }

    private static void recordFailed(ConfigSource master, Snapshot next) {
        failedMasterKey = master == null ? "none" : master.key;
        failedMasterMtime = master == null ? 0L : master.mtime;
        failedTargetName = next.targetName;
        failedTargetKey = next.targetKey;
        failedTargetMtime = next.targetMtime;
        reloadError = "配置改动未生效，仍使用上一版：" + join(next.errors);
        LogUtil.error(reloadError, null);
    }

    private static ConfigSource resolveMaster() {
        String root = System.getProperty("weaver.root", "/data/weaver/ecology");
        File dir = new File(root, "classbean/com/dt/checkboard");
        for (String name : FILE_NAMES) {
            File f = new File(dir, name);
            if (f.isFile()) {
                return new ConfigSource(f, null, "文件 " + f.getAbsolutePath(),
                        "file:" + f.getAbsolutePath(), f.lastModified(), isYamlName(name));
            }
        }
        for (String name : FILE_NAMES) {
            String[] paths = {"/com/dt/checkboard/" + name, "/" + name};
            for (String path : paths) {
                if (FormConfig.class.getResource(path) != null) {
                    return new ConfigSource(null, path, "classpath:" + name,
                            "classpath:" + path, 0L, isYamlName(name));
                }
            }
        }
        return null;
    }

    private static ConfigSource resolveFileByName(String name) {
        String root = System.getProperty("weaver.root", "/data/weaver/ecology");
        File f = new File(root, "classbean/com/dt/checkboard/" + name);
        if (f.isFile()) {
            return new ConfigSource(f, null, "文件 " + f.getAbsolutePath(),
                    "file:" + f.getAbsolutePath(), f.lastModified(), isYamlName(name));
        }
        String[] paths = {"/com/dt/checkboard/" + name, "/" + name};
        for (String path : paths) {
            if (FormConfig.class.getResource(path) != null) {
                return new ConfigSource(null, path, "classpath:" + name,
                        "classpath:" + path, 0L, isYamlName(name));
            }
        }
        return null;
    }

    private static boolean isYamlName(String name) {
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private static boolean isSafeConfigFileName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".json");
    }

    private static Snapshot load(ConfigSource master) {
        Snapshot snap = new Snapshot();
        snap.version = VERSION.incrementAndGet();
        if (master == null) {
            snap.source = "未找到 checkboard.yaml / " + FILE_NAME;
            snap.sourceKey = "none";
            snap.masterKey = "none";
            snap.targetKey = "none";
            snap.errors.add("没有找到 checkboard.yaml 或 " + FILE_NAME
                    + "，请放在 classbean/com/dt/checkboard/ 下");
            snap.loadedAt = nowString();
            return snap;
        }
        snap.source = master.label;
        snap.sourceKey = master.key;
        snap.masterKey = master.key;
        snap.masterMtime = master.mtime;
        snap.targetKey = master.key;
        snap.targetMtime = master.mtime;
        snap.mtime = master.mtime;

        String text = readSource(master, snap);
        if (text == null) {
            snap.loadedAt = nowString();
            return snap;
        }
        try {
            JSONObject root = master.yaml ? parseYamlRoot(text, snap) : parseJsonRoot(text, snap);
            if (root != null) {
                if (master.yaml && isMasterConfig(root)) {
                    loadFromMaster(root, snap);
                } else {
                    parseFormsRoot(root, snap);
                }
            }
        } catch (Exception e) {
            snap.errors.add("解析配置失败: " + e.getMessage());
        }
        snap.loadedAt = nowString();
        return snap;
    }

    private static boolean isMasterConfig(JSONObject root) {
        return root.containsKey("active") || root.containsKey("profiles");
    }

    private static void loadFromMaster(JSONObject masterRoot, Snapshot snap) {
        if (masterRoot.containsKey("forms")) {
            snap.errors.add("总控配置不能同时包含 active/profiles 与 forms");
            return;
        }
        String active = trim(masterRoot.getString("active"));
        if (active.isEmpty()) {
            snap.errors.add("总控配置缺少 active");
            return;
        }
        Object rawProfiles = masterRoot.get("profiles");
        if (!(rawProfiles instanceof JSONObject)) {
            snap.errors.add("总控配置 profiles 必须是环境映射对象（例如 prod / test）");
            return;
        }
        JSONObject profiles = (JSONObject) rawProfiles;
        if (profiles.isEmpty()) {
            snap.errors.add("总控配置 profiles 不能为空");
            return;
        }
        String targetName = trim(profiles.getString(active));
        if (targetName.isEmpty()) {
            snap.errors.add("总控配置未找到环境 \"" + active + "\"（可选：" + joinKeys(profiles) + "）");
            return;
        }
        if (!isSafeConfigFileName(targetName)) {
            snap.errors.add("环境 \"" + active + "\" 的配置文件名不合法: " + targetName
                    + "（仅支持同目录下的 .yaml/.yml/.json 文件）");
            return;
        }

        snap.profile = active;
        snap.targetName = targetName;
        ConfigSource target = resolveFileByName(targetName);
        if (target == null) {
            snap.targetKey = "none";
            snap.targetMtime = 0L;
            snap.source = "环境 " + active + " → 未找到 " + targetName;
            snap.errors.add("总控配置启用环境 \"" + active + "\"，但未找到配置文件: " + targetName);
            return;
        }

        snap.source = "环境 " + active + " → " + target.label;
        snap.sourceKey = target.key;
        snap.targetKey = target.key;
        snap.targetMtime = target.mtime;
        snap.mtime = target.mtime;

        String targetText = readSource(target, snap);
        if (targetText == null) {
            return;
        }
        JSONObject targetRoot = target.yaml
                ? parseYamlRoot(targetText, snap)
                : parseJsonRoot(targetText, snap);
        if (targetRoot == null) {
            return;
        }
        if (isMasterConfig(targetRoot)) {
            snap.errors.add("环境配置文件 " + targetName + " 不能再嵌套 active/profiles，必须直接配置 forms");
            return;
        }
        parseFormsRoot(targetRoot, snap);
    }

    private static String readSource(ConfigSource src, Snapshot snap) {
        String text;
        try {
            text = src.file != null ? readFile(src.file) : readClasspath(src.classpath);
        } catch (Exception e) {
            snap.errors.add("读取配置失败: " + e.getMessage());
            return null;
        }
        if (text == null) {
            snap.errors.add("读取配置失败: 资源不存在 " + src.label);
            return null;
        }
        return text;
    }

    private static String joinKeys(JSONObject obj) {
        StringBuilder sb = new StringBuilder();
        for (String k : obj.keySet()) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(k);
        }
        return sb.toString();
    }

    private static String nowString() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    private static void parseFormsRoot(JSONObject root, Snapshot snap) {
        String title = trim(root.getString("title"));
        if (!title.isEmpty()) {
            snap.title = title;
        }
        JSONArray arr = root.getJSONArray("forms");
        if (arr == null || arr.isEmpty()) {
            snap.errors.add("forms 为空，看板不会有任何表单");
            return;
        }
        List<FormSpec> list = new ArrayList<FormSpec>();
        for (int i = 0; i < arr.size(); i++) {
            FormSpec f;
            try {
                f = parseForm(arr.getJSONObject(i), i, snap);
            } catch (Exception e) {
                snap.errors.add("forms[" + i + "] 结构不对: " + e.getMessage());
                continue;
            }
            if (f == null) {
                continue;
            }
            if (find(list, f.key) != null) {
                snap.errors.add("表单 key 重复: " + f.key);
                continue;
            }
            list.add(f);
        }
        snap.forms = list;
    }

    private static JSONObject parseJsonRoot(String text, Snapshot snap) {
        JSONObject root;
        try {
            root = JSON.parseObject(text);
        } catch (Exception e) {
            snap.errors.add("JSON 语法错误: " + e.getMessage());
            return null;
        }
        if (root == null) {
            snap.errors.add("配置内容为空");
            return null;
        }
        return root;
    }

    private static JSONObject parseYamlRoot(String text, Snapshot snap) {
        Object doc;
        try {
            LoaderOptions opts = new LoaderOptions();
            opts.setAllowDuplicateKeys(false);
            opts.setMaxAliasesForCollections(500);
            Yaml parser = new Yaml(new SafeConstructor(opts));
            doc = parser.load(text);
        } catch (Exception e) {
            snap.errors.add("YAML 语法错误: " + e.getMessage());
            return null;
        }
        if (doc == null) {
            snap.errors.add("配置内容为空");
            return null;
        }
        if (!(doc instanceof Map)) {
            snap.errors.add("YAML 根节点必须是对象（映射）");
            return null;
        }
        try {
            return toJsonObject((Map<?, ?>) doc);
        } catch (Exception e) {
            snap.errors.add("YAML 结构错误: " + e.getMessage());
            return null;
        }
    }

    private static JSONObject toJsonObject(Map<?, ?> map) {
        JSONObject obj = new JSONObject(true);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object rawKey = entry.getKey();
            if (rawKey == null) {
                throw new IllegalArgumentException("存在空键名 (null)");
            }
            String key = String.valueOf(rawKey);
            if (obj.containsKey(key)) {
                throw new IllegalArgumentException("存在重复键: " + key);
            }
            obj.put(key, toJsonValue(entry.getValue()));
        }
        return obj;
    }

    private static JSONArray toJsonArray(Collection<?> list) {
        JSONArray arr = new JSONArray();
        for (Object item : list) {
            arr.add(toJsonValue(item));
        }
        return arr;
    }

    private static Object toJsonValue(Object val) {
        if (val == null) {
            return null;
        }
        if (val instanceof Map) {
            return toJsonObject((Map<?, ?>) val);
        }
        if (val instanceof Collection) {
            return toJsonArray((Collection<?>) val);
        }
        return val;
    }

    private static FormSpec parseForm(JSONObject o, int idx, Snapshot snap) {
        if (o == null) {
            snap.errors.add("forms[" + idx + "] 不是对象");
            return null;
        }
        FormSpec f = new FormSpec();
        f.key = trim(o.getString("key"));
        f.name = trim(o.getString("name"));
        f.table = trim(o.getString("table"));
        f.dateField = trim(o.getString("dateField"));
        f.timeField = trim(o.getString("timeField"));
        f.inspectorField = trim(o.getString("inspectorField"));
        f.unitField = trim(o.getString("unitField"));
        f.detailUrl = trim(o.getString("detailUrl"));
        f.unitMatch = trim(o.getString("unitMatch"));
        if (f.unitMatch.isEmpty()) {
            f.unitMatch = "exact";
        }

        String at = "forms[" + idx + "]" + (f.key.isEmpty() ? "" : "(" + f.key + ")");
        List<String> errs = new ArrayList<String>();

        if (f.key.isEmpty()) {
            errs.add("缺 key");
        }
        if (f.name.isEmpty()) {
            errs.add("缺 name");
        }
        if (!ident(f.table)) {
            errs.add("table 必填，且只能包含字母、数字、下划线");
        }
        if (!ident(f.dateField)) {
            errs.add("dateField 必填，且只能包含字母、数字、下划线");
        }
        if (!ident(f.timeField)) {
            errs.add("timeField 必填，且只能包含字母、数字、下划线");
        }
        if (!ident(f.inspectorField)) {
            errs.add("inspectorField 必填，且只能包含字母、数字、下划线");
        }
        if (!f.unitField.isEmpty() && !ident(f.unitField)) {
            errs.add("unitField 只能包含字母、数字、下划线");
        }
        if (!"exact".equalsIgnoreCase(f.unitMatch) && !"contains".equalsIgnoreCase(f.unitMatch)) {
            errs.add("unitMatch 只能是 exact 或 contains");
        }

        JSONArray mfa = o.getJSONArray("matchFields");
        if (mfa != null) {
            for (int i = 0; i < mfa.size(); i++) {
                String mf = trim(mfa.getString(i));
                if (!ident(mf)) {
                    errs.add("matchFields[" + i + "] 只能包含字母、数字、下划线");
                } else if (!f.matchFields.contains(mf)) {
                    f.matchFields.add(mf);
                }
            }
        }

        JSONArray ra = o.getJSONArray("results");
        if (ra != null) {
            for (int i = 0; i < ra.size(); i++) {
                JSONObject ro = ra.getJSONObject(i);
                if (ro == null) {
                    errs.add("results[" + i + "] 不是对象");
                    continue;
                }
                FormSpec.ResultField rf = new FormSpec.ResultField();
                rf.field = trim(ro.getString("field"));
                rf.label = trim(ro.getString("label"));
                if (!ident(rf.field)) {
                    errs.add("results[" + i + "] field 只能包含字母、数字、下划线");
                    continue;
                }
                if (rf.label.isEmpty()) {
                    rf.label = rf.field;
                }
                JSONObject labels = ro.getJSONObject("labels");
                if (labels != null) {
                    for (Map.Entry<String, Object> e : labels.entrySet()) {
                        if (e.getValue() != null) {
                            rf.labels.put(e.getKey(), String.valueOf(e.getValue()));
                        }
                    }
                }
                JSONArray rna = ro.getJSONArray("normal");
                if (rna != null) {
                    for (int k = 0; k < rna.size(); k++) {
                        String nv = trim(rna.getString(k));
                        if (!nv.isEmpty() && !rf.normal.contains(nv)) {
                            rf.normal.add(nv);
                        }
                    }
                }
                if (f.resultField(rf.field) != null) {
                    errs.add("results 有重复 field: " + rf.field);
                    continue;
                }
                f.results.add(rf);
            }
        }
        // 兼容旧配置：只有一个结果字段时可以直接写 resultField
        String legacyResult = trim(o.getString("resultField"));
        if (f.results.isEmpty() && !legacyResult.isEmpty()) {
            if (!ident(legacyResult)) {
                errs.add("resultField 只能包含字母、数字、下划线");
            } else {
                FormSpec.ResultField rf = new FormSpec.ResultField();
                rf.field = legacyResult;
                rf.label = f.name;
                f.results.add(rf);
            }
        }

        JSONArray ua = o.getJSONArray("units");
        if (ua == null || ua.isEmpty()) {
            errs.add("units 不能为空，单台设备也写一个元素");
        } else {
            for (int i = 0; i < ua.size(); i++) {
                JSONObject uo = ua.getJSONObject(i);
                if (uo == null) {
                    errs.add("units[" + i + "] 不是对象");
                    continue;
                }
                FormSpec.Unit u = new FormSpec.Unit();
                u.code = trim(uo.getString("code"));
                u.label = trim(uo.getString("label"));
                if (u.code.isEmpty()) {
                    errs.add("units[" + i + "] 缺 code");
                    continue;
                }
                if (u.label.isEmpty()) {
                    u.label = u.code;
                }
                JSONArray kwa = uo.getJSONArray("keywords");
                if (kwa != null) {
                    for (int k = 0; k < kwa.size(); k++) {
                        String kw = trim(kwa.getString(k));
                        if (!kw.isEmpty() && !u.keywords.contains(kw)) {
                            u.keywords.add(kw);
                        }
                    }
                }
                if (f.unit(u.code) != null) {
                    errs.add("units 有重复 code: " + u.code);
                } else {
                    f.units.add(u);
                }
            }
        }
        if (f.unitField.isEmpty() && f.units.size() > 1) {
            errs.add("unitField 为空时 units 只能有一个元素");
        }
        if (f.containsMatch() && f.unitField.isEmpty()) {
            errs.add("unitMatch=contains 时必须配 unitField（文本字段）");
        }

        JSONArray na = o.getJSONArray("normal");
        if (na != null) {
            for (int i = 0; i < na.size(); i++) {
                String v = trim(na.getString(i));
                if (!v.isEmpty() && !f.normal.contains(v)) {
                    f.normal.add(v);
                }
            }
        }
        if (f.hasResult() && f.normal.isEmpty() && !hasOwnNormal(f)) {
            errs.add("配了 results 就必须配 normal（正常值白名单，如 [\"0\",\"正常\"]）");
        }
        if (!f.hasResult()) {
            f.normal.clear();
        }

        JSONObject labels = o.getJSONObject("valueLabels");
        if (labels != null && !f.results.isEmpty()) {
            // 旧配置的 valueLabels 归属第一个结果字段
            for (Map.Entry<String, Object> e : labels.entrySet()) {
                if (e.getValue() != null && !f.results.get(0).labels.containsKey(e.getKey())) {
                    f.results.get(0).labels.put(e.getKey(), String.valueOf(e.getValue()));
                }
            }
        }

        f.imageUrl = trim(o.getString("imageUrl"));

        JSONArray ia = o.getJSONArray("items");
        if (ia != null) {
            List<String> seen = new ArrayList<String>();
            for (int i = 0; i < ia.size(); i++) {
                JSONObject io = ia.getJSONObject(i);
                if (io == null) {
                    errs.add("items[" + i + "] 不是对象");
                    continue;
                }
                FormSpec.Item it = new FormSpec.Item();
                it.field = trim(io.getString("field"));
                it.label = trim(io.getString("label"));
                it.imageField = trim(io.getString("imageField"));
                it.remarkField = trim(io.getString("remarkField"));
                it.remarkLabel = trim(io.getString("remarkLabel"));
                it.count = "true".equalsIgnoreCase(io.getString("count"));
                JSONArray ina = io.getJSONArray("normal");
                if (ina != null) {
                    for (int k = 0; k < ina.size(); k++) {
                        String nv = trim(ina.getString(k));
                        if (!nv.isEmpty() && !it.normal.contains(nv)) {
                            it.normal.add(nv);
                        }
                    }
                }
                if (it.field.isEmpty()) {
                    // 占位项：字段还没填，跳过查询，只在 /config 里提示
                    if (!it.label.isEmpty()) {
                        f.pendingItems.add(it.label);
                    }
                    continue;
                }
                if (!ident(it.field)) {
                    errs.add("items[" + i + "] field 只能包含字母、数字、下划线");
                    continue;
                }
                if (it.label.isEmpty()) {
                    it.label = it.field;
                }
                if (!it.imageField.isEmpty() && !ident(it.imageField)) {
                    errs.add("items[" + i + "] 的 imageField 只能包含字母、数字、下划线");
                    continue;
                }
                if (!it.remarkField.isEmpty() && !ident(it.remarkField)) {
                    errs.add("items[" + i + "] 的 remarkField 只能包含字母、数字、下划线");
                    continue;
                }
                if (seen.contains(it.field)) {
                    errs.add("items 有重复 field: " + it.field);
                    continue;
                }
                seen.add(it.field);
                f.items.add(it);
            }
        }

        JSONArray ga = o.getJSONArray("images");
        if (ga != null) {
            List<String> seen = new ArrayList<String>();
            for (int i = 0; i < ga.size(); i++) {
                JSONObject go = ga.getJSONObject(i);
                if (go == null) {
                    errs.add("images[" + i + "] 不是对象");
                    continue;
                }
                FormSpec.ImageField img = new FormSpec.ImageField();
                img.field = trim(go.getString("field"));
                img.label = trim(go.getString("label"));
                if (img.field.isEmpty()) {
                    f.pendingImages.add(img.label.isEmpty() ? "未命名图片位" : img.label);
                    continue;
                }
                if (!ident(img.field)) {
                    errs.add("images[" + i + "] field 只能包含字母、数字、下划线");
                    continue;
                }
                if (img.label.isEmpty()) {
                    img.label = img.field;
                }
                if (seen.contains(img.field)) {
                    errs.add("images 有重复 field: " + img.field);
                    continue;
                }
                seen.add(img.field);
                f.images.add(img);
            }
        }

        boolean needImageUrl = !f.images.isEmpty();
        for (FormSpec.Item it : f.items) {
            if (!it.imageField.isEmpty()) {
                needImageUrl = true;
            }
        }
        if (needImageUrl && f.imageUrl.isEmpty()) {
            errs.add("配了图片字段就必须配 imageUrl，推荐 {ctx}/api/checkboard/image?id={id}");
        }

        if (!errs.isEmpty()) {
            snap.errors.add(at + ": " + join(errs));
            return null;
        }
        return f;
    }

    private static boolean hasOwnNormal(FormSpec f) {
        for (FormSpec.ResultField rf : f.results) {
            if (rf.normal != null && !rf.normal.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static FormSpec find(List<FormSpec> list, String key) {
        for (FormSpec f : list) {
            if (f.key.equals(key)) {
                return f;
            }
        }
        return null;
    }

    private static String readFile(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        try {
            return read(in);
        } finally {
            close(in);
        }
    }

    private static String readClasspath(String path) {
        InputStream in = FormConfig.class.getResourceAsStream(path);
        if (in == null) {
            return null;
        }
        try {
            return read(in);
        } catch (Exception e) {
            LogUtil.error("读取 classpath:" + path + " 失败", e);
            return null;
        } finally {
            close(in);
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void close(InputStream in) {
        try {
            in.close();
        } catch (Exception ignore) {
            // ignore
        }
    }

    private static boolean ident(String s) {
        return s != null && !s.isEmpty() && s.matches("[A-Za-z0-9_]+");
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    public static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static final class ConfigSource {
        final File file;
        final String classpath;
        final String label;
        final String key;
        final long mtime;
        final boolean yaml;

        ConfigSource(File file, String classpath, String label, String key, long mtime, boolean yaml) {
            this.file = file;
            this.classpath = classpath;
            this.label = label;
            this.key = key;
            this.mtime = mtime;
            this.yaml = yaml;
        }
    }

    public static final class Snapshot {
        public String title = "点检看板";
        public String profile = "";
        public String source = "未加载";
        public String sourceKey = "";
        public String masterKey = "";
        public long masterMtime;
        public String targetKey = "";
        public long targetMtime;
        public String targetName = "";
        public String loadedAt = "";
        /** 每次加载递增，用于让缓存自然失效。 */
        public long version;
        public long mtime;
        public List<FormSpec> forms = new ArrayList<FormSpec>();
        public List<String> errors = new ArrayList<String>();
    }
}
