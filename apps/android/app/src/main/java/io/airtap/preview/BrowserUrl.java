package io.airtap.preview;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Browser navigation on the authorized device; never a relay-side fetch. */
final class BrowserUrl {
    private static final Pattern URL = Pattern.compile("(?i)^(https?)://([^/?#]+)(.*)$");
    private static final Pattern AUTHORITY = Pattern.compile("^(?:\\[([0-9A-Fa-f:.]+)\\]|([A-Za-z0-9.-]+))(?::([0-9]{1,5}))?$");
    static String validate(String value) {
        if (value == null || value.length() == 0 || value.length() > 2048) throw invalid();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7e || "\\\"<>^`{|}".indexOf(c) >= 0) throw invalid();
            if (c == '%') {
                if (i + 2 >= value.length()) throw invalid();
                int high = Character.digit(value.charAt(i + 1), 16), low = Character.digit(value.charAt(i + 2), 16);
                if (high < 0 || low < 0) throw invalid();
                int decoded = high * 16 + low;
                if (decoded < 0x20 || decoded == 0x7f) throw invalid();
                i += 2;
            }
        }
        Matcher url = URL.matcher(value);
        if (!url.matches() || url.group(3).indexOf('[') >= 0 || url.group(3).indexOf(']') >= 0) throw invalid();
        Matcher authority = AUTHORITY.matcher(url.group(2));
        if (!authority.matches()) throw invalid(); // No userinfo, escaped host, empty host or port.
        String port = authority.group(3);
        if (port != null && (Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535)) throw invalid();
        if (authority.group(2) != null && !dnsOrIpv4(authority.group(2))) throw invalid();
        String ipv6 = authority.group(1);
        if (ipv6 != null && ipv6.indexOf('.') >= 0 && !dnsOrIpv4(ipv6.substring(ipv6.lastIndexOf(':') + 1))) throw invalid();
        try {
            // URI's legacy DNS parser rejects legal browser hostnames such as example.1a.
            // DNS/IPv4 already passed the shared checks; use URI only to validate IPv6.
            if (ipv6 != null) {
                URI uri = new URI(url.group(1) + "://" + url.group(2) + "/").parseServerAuthority();
                if (uri.isOpaque() || uri.getHost() == null || uri.getRawUserInfo() != null) throw invalid();
            }
            return value; // Dispatch the checked original string, without decoding or rewriting it.
        } catch (URISyntaxException invalid) {
            throw invalid();
        }
    }

    private static boolean dnsOrIpv4(String host) {
        if (host.length() > 253) return false;
        String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        String[] labels = name.split("\\.", -1);
        for (String label : labels)
            if (!label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")) return false;
        // Browsers reinterpret abbreviated/octal/hex numeric hosts. Accept canonical IPv4 only.
        if (labels[labels.length - 1].matches("(?i)(?:[0-9]+|0x[0-9a-f]+)")) {
            if (host.endsWith(".") || labels.length != 4) return false;
            for (String label : labels)
                if (!label.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(label) > 255) return false;
        }
        return true;
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_url"); }
    private BrowserUrl() { }
}
