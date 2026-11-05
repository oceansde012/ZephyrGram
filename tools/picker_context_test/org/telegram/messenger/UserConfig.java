package org.telegram.messenger;
public class UserConfig {
    public static final int MAX_ACCOUNT_COUNT = 3;
    public static int selectedAccount = 0;
    public static long[] owners = {2000, 3000, 0};
    private final int account;
    private UserConfig(int account) { this.account = account; }
    public static UserConfig getInstance(int account) { return new UserConfig(account); }
    public long getClientUserId() { return owners[account]; }
}
