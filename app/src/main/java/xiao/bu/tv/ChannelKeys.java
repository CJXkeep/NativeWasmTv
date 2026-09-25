package xiao.bu.tv;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** Extracted from MainActivity (kept behaviour identical). */
final class ChannelKeys {
    private static final String TAG = "ChannelKeys";
    private ChannelKeys() {
    }
    static int findGroupByTitle(ChannelCatalog.Group[] groups, String title) {
        if (title == null) {
            return -1;
        }
        for (int index = 0; index < groups.length; index++) {
            if (title.equals(groups[index].title)) {
                return index;
            }
        }
        return -1;
    }

    static int findChannelByKey(ChannelCatalog.Group group, String key) {
        if (key == null) {
            return -1;
        }
        for (int index = 0; index < group.channels.length; index++) {
            if (key.equals(favoriteKey(group, group.channels[index]))) {
                return index;
            }
        }
        return -1;
    }

    static boolean sameChannelIdentity(Channel first, Channel second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null || !first.name.equals(second.name)) {
            return false;
        }
        if (first.yangshipinPid != null || second.yangshipinPid != null) {
            return first.yangshipinPid != null
                    && first.yangshipinPid.equals(second.yangshipinPid);
        }
        if (first.streamId != null || second.streamId != null) {
            return first.streamId != null && first.streamId.equals(second.streamId);
        }
        return first.url == null ? second.url == null
                : second.url != null && Channel.sameSourceUrl(first.url, second.url);
    }

    static int catalogSource(ChannelCatalog.Group group, Channel channel) {
        if (channel.catalogSource >= 0) {
            return channel.catalogSource;
        }
        return group.source;
    }


    static String favoriteKey(ChannelCatalog.Group group, Channel channel) {
        if (group.source == ChannelCatalog.SOURCE_FAVORITES
                && channel.favoriteKey != null) {
            return channel.favoriteKey;
        }
        String identity = channel.yangshipinPid;
        if (identity == null || identity.length() == 0) {
            identity = channel.streamId;
        }
        if ((identity == null || identity.length() == 0) && channel.url != null) {
            identity = channel.url;
        }
        return group.title + "\u001f" + channel.name + "\u001f"
                + (identity == null ? "" : identity);
    }
}
