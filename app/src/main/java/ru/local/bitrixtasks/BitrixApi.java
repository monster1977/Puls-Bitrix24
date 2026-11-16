package ru.local.bitrixtasks;

import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

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
    /** Marks a checklist item complete or reopens it, then reloads the task with its fresh checklist. */
    Task setChecklistItemState(long taskId, long itemId, boolean complete) throws Exception {
        JSONObject response=transport.call(complete?"task.checklistitem.complete":"task.checklistitem.renew",
                new JSONObject().put("TASKID",taskId).put("ITEMID",itemId));
        if(!response.optBoolean("result")) throw new ApiException("Портал не принял изменение чек-листа. Обновите задачу и повторите.");
        return getTask(taskId);
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
            if (Build.VERSION.SDK_INT < 25) configureLegacyTls(c);
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

    /**
     * Android 4.4–7.0: TLS 1.2 там не включён по умолчанию, а современного корневого
     * сертификата Let's Encrypt (ISRG Root X1) в системе нет до Android 7.1.1.
     * Включаем TLS 1.2 принудительно и добавляем ISRG Root X1 как резервную точку доверия,
     * не отменяя обычную проверку сертификатов системой.
     */
    private static void configureLegacyTls(HttpsURLConnection c) throws Exception {
        SSLContext context = SSLContext.getInstance("TLSv1.2");
        context.init(null, legacyTrustManagers(), new SecureRandom());
        c.setSSLSocketFactory(new Tls12SocketFactory(context.getSocketFactory()));
        c.setHostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier());
    }

    private static TrustManager[] legacyTrustManagers() throws Exception {
        TrustManagerFactory system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        system.init((KeyStore) null);
        X509TrustManager systemTrust = (X509TrustManager) system.getTrustManagers()[0];
        KeyStore portalRoots = KeyStore.getInstance(KeyStore.getDefaultType());
        portalRoots.load(null, null);
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        portalRoots.setCertificateEntry("isrg-root-x1", (X509Certificate) factory.generateCertificate(
                new ByteArrayInputStream(ISRG_ROOT_X1.getBytes(StandardCharsets.US_ASCII))));
        TrustManagerFactory anchored = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        anchored.init(portalRoots);
        X509TrustManager portalTrust = (X509TrustManager) anchored.getTrustManagers()[0];
        return new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                systemTrust.checkClientTrusted(chain, authType);
            }
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try { systemTrust.checkServerTrusted(chain, authType); }
                catch (CertificateException e) { portalTrust.checkServerTrusted(chain, authType); }
            }
            public X509Certificate[] getAcceptedIssuers() { return systemTrust.getAcceptedIssuers(); }
        }};
    }

    /** Обёртка сокета: на Android 4.4 протокол TLS 1.2 поддерживается, но по умолчанию выключен. */
    private static final class Tls12SocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory base;
        Tls12SocketFactory(SSLSocketFactory base) { this.base = base; }
        private Socket tls(Socket socket) {
            if (socket instanceof SSLSocket) ((SSLSocket) socket).setEnabledProtocols(new String[]{"TLSv1.2"});
            return socket;
        }
        public String[] getDefaultCipherSuites() { return base.getDefaultCipherSuites(); }
        public String[] getSupportedCipherSuites() { return base.getSupportedCipherSuites(); }
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            return tls(base.createSocket(socket, host, port, autoClose));
        }
        public Socket createSocket(String host, int port) throws IOException { return tls(base.createSocket(host, port)); }
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return tls(base.createSocket(host, port, localHost, localPort));
        }
        public Socket createSocket(InetAddress host, int port) throws IOException { return tls(base.createSocket(host, port)); }
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return tls(base.createSocket(address, port, localAddress, localPort));
        }
    }

    // ISRG Root X1 — корневой сертификат Let's Encrypt, актуальный для адресов вида bitrix.plpl.ru.
    static final String ISRG_ROOT_X1 = pem(new String[]{
            "-----BEGIN CERTIFICATE-----",
            "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",
            "-----END CERTIFICATE-----"});
    private static String pem(String[] lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) text.append(line).append('\n');
        return text.toString();
    }
}
