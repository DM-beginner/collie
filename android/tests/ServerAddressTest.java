package dev.dmbeginner.colliepocket;

/** Runs with the JDK; pins origin boundaries without an emulator or external dependencies. */
public final class ServerAddressTest {
    private static int checks;
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("Check " + checks + " failed");
    }
    private static void rejects(String value) {
        try { ServerAddress.normalize(value); throw new AssertionError("Accepted invalid address: " + value); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) {
        check(ServerAddress.normalize(" 100.100.20.30:8787 ").equals("http://100.100.20.30:8787/"));
        check(ServerAddress.normalize("HTTP://PC.tail123.ts.net:8787").equals("http://pc.tail123.ts.net:8787/"));
        check(ServerAddress.normalize("https://example.com").equals("https://example.com/"));
        check(ServerAddress.normalize("http://10.0.2.2:9876").equals("http://10.0.2.2:9876/"));
        check(ServerAddress.normalize("http://192.168.0.1").equals("http://192.168.0.1/"));
        check(ServerAddress.normalize("http://[::1]:8787").equals("http://[::1]:8787/"));
        check(ServerAddress.sameOrigin("http://pc.tail123.ts.net:8787/", "http://pc.tail123.ts.net:8787/settings?pair=ABC"));
        check(ServerAddress.sameOrigin("https://example.com/", "https://example.com:443/pane/a"));
        check(!ServerAddress.sameOrigin("https://example.com/", "http://example.com/"));
        check(!ServerAddress.sameOrigin("https://example.com/", "https://example.com.evil.test/"));
        check(!ServerAddress.sameOrigin("https://example.com/", "https://user@example.com/"));
        check(!ServerAddress.sameOrigin("http://localhost:8787/", "http://localhost:8788/"));
        check(!ServerAddress.sameOrigin("https://example.com/", "javascript:alert(1)"));
        check(!ServerAddress.sameOrigin("https://example.com/", "file:///etc/passwd"));
        for (String value : new String[] {"", "http://example.com", "http://evil.ts.net.example.com", "http://100.63.0.1",
                "http://100.128.0.1", "http://172.32.0.1", "http://user:pass@localhost", "http://localhost/settings",
                "http://localhost/?pair=secret", "http://localhost/#secret", "ftp://localhost", "javascript:alert(1)",
                "http://localhost:0", "http://localhost:65536", "http://localhost\\@evil.test", "http://local\nhost"}) rejects(value);
        System.out.println("ServerAddress: " + checks + " checks passed");
    }
}
