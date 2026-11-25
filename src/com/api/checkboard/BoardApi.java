package com.api.checkboard;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.dt.checkboard.service.BoardService;
import com.dt.common.util.LogUtil;
import weaver.conn.RecordSet;
import weaver.file.ImageFileManager;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 点检看板接口。
 * <ul>
 *   <li>GET /api/checkboard/summary?date=yyyy-MM-dd&amp;force=1</li>
 *   <li>GET /api/checkboard/detail?form=&amp;date=&amp;unit=&amp;limit=</li>
 *   <li>GET /api/checkboard/config</li>
 *   <li>GET /api/checkboard/image?id=</li>
 *   <li>GET /api/checkboard/imagedebug?id=</li>
 *   <li>GET /api/checkboard/page</li>
 * </ul>
 */
@Path("checkboard")
public class BoardApi {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String JSON_UTF8 = "application/json;charset=UTF-8";
    /** 单张图片读取上限，防止异常大文件把内存打满。 */
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;

    private final BoardService service = new BoardService();

    @GET
    @Path("/summary")
    @Produces(MediaType.APPLICATION_JSON)
    public Response summary(@QueryParam("date") String date, @QueryParam("force") String force) {
        String day = normalize(date);
        if (day == null) {
            return badDate(date);
        }
        boolean fresh = "1".equals(force) || "true".equalsIgnoreCase(force);
        try {
            return json(service.summary(day, fresh).toString());
        } catch (Throwable t) {
            LogUtil.error("看板概况查询失败 date=" + day, t);
            return json(fail(t));
        }
    }

    @GET
    @Path("/detail")
    @Produces(MediaType.APPLICATION_JSON)
    public Response detail(@QueryParam("form") String form,
                           @QueryParam("date") String date,
                           @QueryParam("unit") String unit,
                           @QueryParam("limit") String limit) {
        if (form == null || form.trim().isEmpty()) {
            return badRequest("缺少 form 参数");
        }
        String day = normalize(date);
        if (day == null) {
            return badDate(date);
        }
        try {
            return json(service.detail(form.trim(), day, unit == null ? "" : unit.trim(), intValue(limit))
                    .toString());
        } catch (Throwable t) {
            LogUtil.error("看板明细查询失败 form=" + form + " date=" + day, t);
            return json(fail(t));
        }
    }

    @GET
    @Path("/config")
    @Produces(MediaType.APPLICATION_JSON)
    public String config() {
        try {
            return service.config().toString();
        } catch (Throwable t) {
            LogUtil.error("看板配置自检失败", t);
            return fail(t);
        }
    }

    /**
     * 点检图片代理。
     *
     * <p>表单图片字段只会存 docid，解析只有一条路：DocImageFile.docid → 最新版本的
     * imagefileid → ImageFile。不能把 docid 当 imagefileid 直读，也不能拿同号的
     * ImageFile 顶替——那正是之前「显示成别的图 / 触发下载」的原因。</p>
     *
     * <p>同一 docid 可能有多个版本，只取 versionid 最大的一条；imagefileid 可能是负数，
     * 0 表示没有文件。最新版本读不出来就 404，不再回退到旧版本或别的 id。</p>
     */
    @GET
    @Path("/image")
    public Response image(@QueryParam("id") Integer id) {
        if (id == null || id <= 0) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        try {
            int imageFileId = latestImageFileId(id);
            if (imageFileId == 0) {
                LogUtil.error("点检图片没有可用的 imagefileid docid=" + id, null);
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            byte[] data = readImage(imageFileId);
            if (data == null || data.length == 0) {
                LogUtil.error("点检图片读取为空 docid=" + id + ", imagefileid=" + imageFileId, null);
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            String type = contentType(data);
            if (!type.startsWith("image/")) {
                // 最新版本可能挂的是 PDF/Excel，不能当图片返回给浏览器
                LogUtil.error("点检图片最新版本不是图片 docid=" + id
                        + ", imagefileid=" + imageFileId + ", type=" + type, null);
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            return Response.ok(data)
                    .type(type)
                    .header("Cache-Control", "no-store")
                    .build();
        } catch (Throwable t) {
            LogUtil.error("读取点检图片失败 id=" + id, t);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 图片解析诊断：列出该 docid 在 DocImageFile 里的所有版本，以及最终选中的最新版本。
     *
     * <p>排障接口，不要拿去做业务调用。</p>
     */
    @GET
    @Path("/imagedebug")
    @Produces("application/json;charset=UTF-8")
    public String imageDebug(@QueryParam("id") Integer id) {
        JSONObject res = new JSONObject();
        if (id == null || id <= 0) {
            res.put("success", false);
            res.put("error", "id 参数不合法");
            return res.toString();
        }
        try {
            res.put("success", true);
            res.put("docid", id);
            res.put("rows", docImageRows(id));
            int imageFileId = latestImageFileId(id);
            res.put("imagefileid", imageFileId);
            if (imageFileId != 0) {
                JSONObject latest = new JSONObject();
                latest.put("imagefileid", imageFileId);
                byte[] data = readImage(imageFileId);
                latest.put("readable", data != null && data.length > 0);
                latest.put("bytes", data == null ? 0 : data.length);
                String type = data == null || data.length == 0 ? "" : contentType(data);
                latest.put("type", type);
                latest.put("image", type.startsWith("image/"));
                res.put("latest", latest);
            }
        } catch (Throwable t) {
            res.put("success", false);
            res.put("error", t.getMessage() == null ? t.toString() : t.getMessage());
        }
        return res.toString();
    }

    /**
     * 表单图片字段的值是 docid；取 DocImageFile 里该 docid 的最新版本。
     *
     * <p>versionid 相同或为空时用 id 倒序兜底，保证顺序稳定。imagefileid 可能是负数
     * （E9 表单附件会出现 -5 这种值），0 表示没有文件，按 404 处理。</p>
     */
    private static int latestImageFileId(int docid) {
        try {
            RecordSet rs = new RecordSet();
            rs.executeQuery("SELECT imagefileid FROM DocImageFile"
                    + " WHERE docid = ? ORDER BY versionid DESC, id DESC", docid);
            return rs.next() ? rs.getInt("imagefileid") : 0;
        } catch (Exception e) {
            LogUtil.error("解析图片 docid 失败 docid=" + docid, e);
            return 0;
        }
    }

    private static JSONArray docImageRows(int docid) {
        JSONArray arr = new JSONArray();
        String sql = "SELECT id, docid, imagefileid, imagefilename, versionid"
                + " FROM DocImageFile WHERE docid = ? ORDER BY versionid DESC, id DESC";
        try {
            RecordSet rs = new RecordSet();
            rs.executeQuery(sql, docid);
            while (rs.next() && arr.size() < 10) {
                JSONObject one = new JSONObject();
                one.put("id", rs.getInt("id"));
                one.put("docid", rs.getInt("docid"));
                one.put("imagefileid", rs.getInt("imagefileid"));
                one.put("filename", rs.getString("imagefilename"));
                one.put("versionid", rs.getInt("versionid"));
                arr.add(one);
            }
        } catch (Exception e) {
            JSONObject one = new JSONObject();
            one.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
            arr.add(one);
        }
        return arr;
    }

    private static byte[] readImage(int imageFileId) {
        InputStream in = null;
        try {
            in = ImageFileManager.getInputStreamById(imageFileId);
            if (in == null) {
                return null;
            }
            return readAll(in, MAX_IMAGE_BYTES);
        } catch (Throwable t) {
            LogUtil.error("读取图片失败 imagefileid=" + imageFileId, t);
            return null;
        } finally {
            close(in);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static byte[] readAll(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > maxBytes) {
                throw new IllegalStateException("图片超过大小上限 " + maxBytes + " 字节");
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static void close(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (Exception ignore) {
            // ignore
        }
    }

    private static String contentType(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 4 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        if (b.length >= 4 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return "image/gif";
        }
        if (b.length >= 2 && b[0] == 'B' && b[1] == 'M') {
            return "image/bmp";
        }
        return "application/octet-stream";
    }

    @GET
    @Path("/page")
    @Produces("text/html;charset=UTF-8")
    public Response page() {
        try (InputStream in = BoardApi.class.getResourceAsStream("/com/dt/checkboard/index.html")) {
            if (in == null) {
                return Response.ok("<h3>缺少页面文件 classbean/com/dt/checkboard/index.html</h3>")
                        .type("text/html; charset=UTF-8").build();
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return Response.ok(new String(bos.toByteArray(), StandardCharsets.UTF_8))
                    .type("text/html; charset=UTF-8")
                    .header("Cache-Control", "no-cache")
                    .build();
        } catch (Exception e) {
            LogUtil.error("读取看板页面失败", e);
            return Response.ok("<h3>读取页面失败: " + e.getMessage() + "</h3>")
                    .type("text/html; charset=UTF-8").build();
        }
    }

    /**
     * 解析日期参数：留空取今天；格式或日期本身不合法返回 null，由接口回 400。
     *
     * <p>用 ISO_LOCAL_DATE 严格解析，2026-02-30、2026-99-99 这类值会被拒绝，
     * 不再像旧版那样静默退回今天——那会把「日期传错」显示成「今天的数据」。</p>
     */
    private static String normalize(String date) {
        if (date == null || date.trim().isEmpty()) {
            return LocalDate.now().format(DATE);
        }
        try {
            return LocalDate.parse(date.trim(), DateTimeFormatter.ISO_LOCAL_DATE).format(DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Response json(String body) {
        return Response.ok(body).type(JSON_UTF8).build();
    }

    private static int intValue(String s) {
        if (s == null || s.trim().isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Response badDate(String raw) {
        return badRequest("date 参数不合法：" + (raw == null ? "" : raw.trim())
                + "，应为 yyyy-MM-dd（例如 2026-11-09）");
    }

    private static Response badRequest(String message) {
        JSONObject res = new JSONObject();
        res.put("success", false);
        res.put("error", message);
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(res.toString())
                .type(JSON_UTF8)
                .build();
    }

    private static String fail(Throwable t) {
        JSONObject res = new JSONObject();
        res.put("success", false);
        res.put("error", t.getMessage() == null ? t.toString() : t.getMessage());
        return res.toString();
    }
}
