package xiao.bu.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/**
 * 内置频道源的契约测试：源就是产品的一部分，任何改动都要显式改这里的数字——
 * 这一步本身就是一次「为什么变了」的审视。纯 JVM，读 assets 文本，不需要设备与网络。
 */
public final class BuiltinChannelContractTest {
    private static final int EXPECTED_CHANNELS = 65;
    private static final int EXPECTED_SINGLE_SOURCE_CHANNELS = 41;
    private static final Set<String> EXPECTED_GROUPS =
            new HashSet<String>(Arrays.asList("央视频道", "卫视频道"));
    private static final List<String> BANNED_FRAGMENTS =
            Arrays.asList("iill.top", "kwimgs", "auth=", "testpub");

    private static File builtinFile() {
        File[] candidates = {
                new File("src/main/assets/builtin_channels.txt"),
                new File("app/src/main/assets/builtin_channels.txt")
        };
        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        throw new AssertionError("找不到内置源文件：" + Arrays.toString(candidates));
    }

    /** 频道名 -> 线路地址，保持文件顺序。 */
    private static Map<String, List<String>> channels() throws Exception {
        Map<String, List<String>> channels = new LinkedHashMap<String, List<String>>();
        String pending = null;
        for (String line : Files.readAllLines(builtinFile().toPath(), StandardCharsets.UTF_8)) {
            String value = line.trim();
            if (value.startsWith("#EXTINF:")) {
                pending = value.substring(value.indexOf(',') + 1).trim();
                if (!channels.containsKey(pending)) {
                    channels.put(pending, new ArrayList<String>());
                }
                continue;
            }
            if (value.length() == 0 || value.startsWith("#") || pending == null) {
                continue;
            }
            channels.get(pending).add(value);
            pending = null;
        }
        return channels;
    }

    @Test
    public void keepsDeclaredChannelContract() throws Exception {
        Map<String, List<String>> channels = channels();
        assertEquals(EXPECTED_CHANNELS, channels.size());
        int singleSource = 0;
        for (List<String> addresses : channels.values()) {
            if (addresses.size() == 1) {
                singleSource++;
            }
        }
        assertEquals(EXPECTED_SINGLE_SOURCE_CHANNELS, singleSource);
    }

    @Test
    public void everyChannelHasUsableUniqueAddresses() throws Exception {
        for (Map.Entry<String, List<String>> entry : channels().entrySet()) {
            List<String> addresses = entry.getValue();
            assertTrue(entry.getKey() + " 没有任何线路", addresses.size() > 0);
            Set<String> unique = new HashSet<String>();
            for (String address : addresses) {
                assertTrue(entry.getKey() + " 的地址不受支持：" + address,
                        address.startsWith("http://") || address.startsWith("https://")
                                || address.startsWith("webview://http"));
                assertTrue(entry.getKey() + " 存在重复线路：" + address, unique.add(address));
            }
        }
    }

    @Test
    public void excludesBlacklistedAddresses() throws Exception {
        for (Map.Entry<String, List<String>> entry : channels().entrySet()) {
            for (String address : entry.getValue()) {
                for (String banned : BANNED_FRAGMENTS) {
                    assertTrue(entry.getKey() + " 命中黑名单 " + banned + "：" + address,
                            address.indexOf(banned) < 0);
                }
            }
        }
    }

    @Test
    public void keepsDeclaredGroups() throws Exception {
        Set<String> groups = new HashSet<String>();
        for (String line : Files.readAllLines(builtinFile().toPath(), StandardCharsets.UTF_8)) {
            String value = line.trim();
            if (!value.startsWith("#EXTINF:")) {
                continue;
            }
            String key = "group-title=\"";
            int mark = value.indexOf(key);
            if (mark < 0) {
                continue;
            }
            int end = value.indexOf('"', mark + key.length());
            if (end > mark) {
                groups.add(value.substring(mark + key.length(), end));
            }
        }
        assertEquals(EXPECTED_GROUPS, groups);
    }
}
