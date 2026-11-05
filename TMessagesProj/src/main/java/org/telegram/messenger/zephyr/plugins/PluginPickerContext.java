package org.telegram.messenger.zephyr.plugins;

import org.json.JSONObject;
import org.telegram.messenger.UserConfig;

/** Account provenance captured before a picker/lookup starts; no global-account fallback. */
public final class PluginPickerContext {
    public static final int API_VERSION = 1;
    public final int account;
    private final long ownerId;

    private PluginPickerContext(int account, long ownerId) {
        this.account = account;
        this.ownerId = ownerId;
    }

    public static PluginPickerContext capture(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return null;
        long owner = UserConfig.getInstance(account).getClientUserId();
        return owner > 0 ? new PluginPickerContext(account, owner) : null;
    }

    public boolean isCurrent() {
        return UserConfig.getInstance(account).getClientUserId() == ownerId;
    }

    public JSONObject toJson() throws Exception {
        if (!isCurrent()) throw new IllegalStateException("picker_account_changed");
        return new JSONObject().put("account", account).put("ownerId", String.valueOf(ownerId));
    }
}
