package xiao.bu.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.util.Collections;

import org.junit.Test;

/** CJS 频道地址解析的回归用例；纯 JVM，不需要设备与网络。 */
public final class CjsSourceTest {
    private static final String BASE = "https://raw.githubusercontent.com/TvWasm/cjs/main/";

    private static void reject(String source) throws Exception {
        try {
            CjsSource.parse(source);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Accepted invalid source: " + source);
    }

    @Test
    public void parsesDescriptorAndParameters() throws Exception {
        CjsSource source = CjsSource.parse(
                BASE + "gxtv.cjs?id=abc&quality=low&token=a%2Bb%26c&label=%E4%B8%AD%E6%96%87");
        assertEquals(BASE + "gxtv.cjs", source.descriptorUrl);
        assertEquals("low", source.parameters.get("quality"));
        assertEquals("a+b&c", source.parameters.get("token"));
        assertEquals("中文", source.parameters.get("label"));
        assertEquals("https://example.com/abc", source.page("https://example.com/{id}",
                Collections.singletonMap("id", "[a-z]+")));
    }

    @Test
    public void ignoresNonCjsAddresses() throws Exception {
        assertNull(CjsSource.parse("https://example.com/live.m3u8?id=1"));
    }

    @Test
    public void rejectsUnsupportedOrAmbiguousAddresses() throws Exception {
        reject("file:///tmp/gxtv.cjs?id=1");
        reject("content://plugin/gxtv.cjs?id=1");
        reject("https://user:password@example.com/gxtv.cjs?id=1");
        reject(BASE + "gxtv.cjs?id=1#bad");
        reject(BASE + "gxtv.cjs?id=1&id=2");
        reject(BASE + "gxtv.cjs?id=1&%69d=2");
        reject(BASE + "gxtv.cjs?id=%ZZ");
        reject(BASE + "gxtv.cjs?id=1&quality=4k");
        reject(BASE + "gxtv.cjs?id=1&=empty");
    }

    @Test
    public void rejectsUnsafePageTemplates() throws Exception {
        try {
            CjsSource.parse(BASE + "gxtv.cjs?id=..%2F..%2Ffile").page(
                    "https://example.com/{id}", Collections.singletonMap("id", "[a-z0-9]+"));
            throw new AssertionError("Path injection accepted");
        } catch (IOException expected) {
            // expected
        }
        try {
            CjsSource.parse(BASE + "gxtv.cjs").page("https://example.com/{id}",
                    Collections.singletonMap("id", "[a-z]+"));
            throw new AssertionError("Missing id accepted");
        } catch (IOException expected) {
            // expected
        }
    }
}
