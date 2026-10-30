package ru.local.bitrixtasks;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;

final class BitrixApi {
    interface Transport { JSONObject call(String method, JSONObject payload) throws Exception; }
    private final Transport transport;
    BitrixApi(Webhook hook) { transport = (method, payload) -> request(hook, method, payload); }
    BitrixApi(Transport transport) { this.transport = transport; }
    static final class ApiException extends Exception { ApiException(String message) { super(message); } }
    static final class Page {
        final List<Task> tasks; final int next;
        Page(List<Task> tasks, int next) { this.tasks = tasks; this.next = next; }
    }
    Page list(int start, String query, int tab, boolean mine, long userId) throws Exception {
        JSONObject filter = new JSONObject();
        if (mine) filter.put("RESPONSIBLE_ID", userId);
        if (!query.trim().isEmpty()) filter.put("%TITLE", query.trim());
        if (tab == 0) filter.put("!REAL_STATUS", 5);
        if (tab == 1) filter.put("REAL_STATUS", 5);
        JSONObject params = new JSONObject().put("start", start).put("filter", filter)
                .put("order", new JSONObject().put("ID", "desc"))
                .put("select", new JSONArray(new String[]{"ID", "TITLE", "DESCRIPTION", "STATUS", "DEADLINE", "RESPONSIBLE_ID", "PRIORITY", "CHECKLIST"}));
        JSONObject response = transport.call("tasks.task.list", params);
        JSONArray array = response.getJSONObject("result").getJSONArray("tasks");
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) tasks.add(new Task(array.getJSONObject(i)));
        int next = response.optInt("next", -1);
        if (next < 0 && response.optInt("total", 0) > start + array.length() && array.length() > 0) next = start + array.length();
        return new Page(tasks, next > start ? next : -1);
    }
    Task add(String title, String description, long responsible, String deadline, boolean priority) throws Exception {
        JSONObject fields = new JSONObject().put("TITLE", title).put("DESCRIPTION", description)
                .put("RESPONSIBLE_ID", responsible).put("PRIORITY", priority ? 2 : 1);
        if (!deadline.isEmpty()) fields.put("DEADLINE", deadline);
        return new Task(transport.call("tasks.task.add", new JSONObject().put("fields", fields)).getJSONObject("result").getJSONObject("task"));
    }
    Task complete(long id) throws Exception {
        return new Task(transport.call("tasks.task.complete", new JSONObject().put("taskId", id))
                .getJSONObject("result").getJSONObject("task"));
    }
    Task getTask(long id) throws Exception {
        JSONObject response=transport.call("tasks.task.get",new JSONObject().put("taskId",id));
        JSONObject result=response.getJSONObject("result");
        JSONObject task=result.optJSONObject("task"); if(task==null) task=result;
        JSONArray checklist=getChecklist(id);
        task.put("checklist", checklist);
        return new Task(task);
    }
    private JSONArray getChecklist(long id) throws Exception {
        JSONObject response=transport.call("task.checklistitem.getlist",new JSONObject()
                .put("TASKID",id).put("ORDER",new JSONObject().put("SORT_INDEX","ASC")));
        JSONArray result=response.optJSONArray("result");
        return result==null?new JSONArray():result;
    }
    Task updateDeadline(long id, String deadline) throws Exception {
        if (!deadline.isEmpty()) java.time.OffsetDateTime.parse(deadline);
        // An explicit empty value clears the deadline; never send other task fields.
        JSONObject fields = new JSONObject().put("DEADLINE", deadline);
        return new Task(transport.call("tasks.task.update", new JSONObject().put("taskId", id).put("fields", fields))
                .getJSONObject("result").getJSONObject("task"));
    }
    Task updateTitle(long id,String title) throws Exception {
        if(title.trim().isEmpty()) throw new IllegalArgumentException("Название задачи не может быть пустым.");
        JSONObject fields=new JSONObject().put("TITLE",title.trim());
        return new Task(transport.call("tasks.task.update",new JSONObject().put("taskId",id).put("fields",fields)).getJSONObject("result").getJSONObject("task"));
    }
    private static JSONObject request(Webhook hook, String method, JSONObject params) throws Exception {
        HttpsURLConnection c = null;
        try {
            c = (HttpsURLConnection) new URL(hook.baseUrl + method + ".json").openConnection();
            c.setInstanceFollowRedirects(false); c.setRequestMethod("POST");
            c.setConnectTimeout(15000); c.setReadTimeout(25000); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            byte[] body = params.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = c.getOutputStream()) { out.write(body); }
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) throw new ApiException("Портал перенаправляет запрос. Укажите конечный HTTPS-адрес вебхука.");
            InputStream stream = code < 400 ? c.getInputStream() : c.getErrorStream();
            if (stream == null) throw new ApiException(httpError(code));
            String raw;
            try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > 8 * 1024 * 1024) throw new ApiException("Ответ портала слишком большой. Уточните поиск.");
                    out.write(buffer, 0, count);
                }
                raw = out.toString("UTF-8");
            }
            JSONObject result;
            try { result = new JSONObject(raw); }
            catch (Exception e) { throw new ApiException(code >= 400 ? httpError(code) : "Портал вернул неожиданный ответ. Проверьте адрес вебхука."); }
            if (result.has("error")) throw new ApiException(apiError(result.optString("error")));
            if (code >= 400) throw new ApiException(httpError(code));
            if (!result.has("result")) throw new ApiException("В ответе портала нет результата.");
            return result;
        } catch (ApiException e) { throw e; }
        catch (java.net.SocketTimeoutException e) { throw new ApiException("Портал не ответил вовремя. Обновите список перед повторной отправкой: изменение могло сохраниться."); }
        catch (javax.net.ssl.SSLException e) { throw new ApiException("Не удалось проверить HTTPS-сертификат портала."); }
        catch (java.io.IOException e) { throw new ApiException("Нет соединения с порталом. Проверьте интернет и адрес. После отправки изменения сначала обновите список."); }
        finally { if (c != null) c.disconnect(); }
    }
    static String apiError(String error) {
        String e = error.toUpperCase(java.util.Locale.ROOT);
        if (e.contains("CREDENTIAL") || e.contains("AUTH") || e.contains("TOKEN")) return "Вебхук не принят. Проверьте адрес и действительность ключа.";
        if (e.contains("SCOPE") || e.contains("ACCESS") || e.contains("DENIED")) return "Недостаточно прав. Проверьте доступ вебхука к задачам и права его владельца.";
        if (e.contains("LIMIT") || e.contains("OVERLOAD")) return "Лимит запросов Bitrix24. Подождите немного и повторите.";
        if (e.contains("RESULT")) return "Для завершения этой задачи нужен результат. Добавьте его в Bitrix24.";
        if (e.contains("NOT_FOUND")) return "Задача не найдена или недоступна.";
        return "Bitrix24 отклонил действие. Проверьте поля, права и требования задачи в портале.";
    }
    private static String httpError(int code) {
        if (code == 401) return apiError("AUTH");
        if (code == 403) return apiError("ACCESS");
        if (code == 429) return apiError("LIMIT");
        return "Портал вернул ошибку HTTP " + code + ". Повторите позже.";
    }
}
