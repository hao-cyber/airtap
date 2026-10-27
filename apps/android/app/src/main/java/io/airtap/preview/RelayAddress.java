package io.airtap.preview;

import java.net.URI;
import java.util.Locale;

/** Only an HTTPS root or one canonical public-service device namespace. */
final class RelayAddress {
    static String canonical(String raw) {
        if (raw == null || raw.indexOf('%') >= 0 || raw.indexOf('\\') >= 0
                || raw.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        URI uri = URI.create(raw.trim());
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || host.isEmpty()
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) throw new IllegalArgumentException();
        String authority = uri.getRawAuthority();
        if (!authority.equalsIgnoreCase(host) && !authority.equalsIgnoreCase(host + ":443")) throw new IllegalArgumentException();
        String path = uri.getRawPath();
        if (path.isEmpty() || path.equals("/")) path = "";
        else if (!path.matches("/d/[a-f0-9]{32}")) throw new IllegalArgumentException();
        return "https://" + host.toLowerCase(Locale.ROOT) + path;
    }

    static String root(String raw) {
        String canonical = canonical(raw);
        int slash = canonical.indexOf('/', 8);
        return slash < 0 ? canonical : canonical.substring(0, slash);
    }

    static String requireRoot(String raw) {
        String canonical = canonical(raw);
        if (!canonical.equals(root(canonical))) throw new IllegalArgumentException();
        return canonical;
    }

    static String deviceBase(String origin, String deviceId) {
        if (deviceId == null || !deviceId.matches("[a-f0-9]{32}")) throw new IllegalArgumentException();
        return requireRoot(origin) + "/d/" + deviceId;
    }

    static String webSocket(String base) {
        return "wss://" + canonical(base).substring(8) + "/v1/device";
    }
}
