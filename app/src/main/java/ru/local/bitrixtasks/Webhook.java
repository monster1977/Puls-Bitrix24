package ru.local.bitrixtasks;

import java.net.URI;

/** Immutable endpoint. Never include this object or its URL in logs. */
public final class Webhook {
    public final String baseUrl;
    public final String host;
    public final long userId;
    private Webhook(String baseUrl, String host, long userId) {
        this.baseUrl = baseUrl; this.host = host; this.userId = userId;
    }
    public static Webhook parse(String input) {
        try {
            URI uri = new URI(input.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) throw new IllegalArgumentException();
            String path = uri.getRawPath();
            if (!path.matches("/rest/[1-9][0-9]*/[A-Za-z0-9_-]+/?")) throw new IllegalArgumentException();
            long id = Long.parseLong(path.split("/")[2]);
            return new Webhook("https://" + uri.getHost() + (path.endsWith("/") ? path : path + "/"), uri.getHost(), id);
        } catch (Exception e) {
            throw new IllegalArgumentException("Нужен полный HTTPS-адрес вебхука: https://портал/rest/ID/ключ/");
        }
    }
    @Override public String toString() { return host + " (секрет скрыт)"; }
}
