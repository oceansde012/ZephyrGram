package org.telegram.messenger.zephyr.plugins;
import org.telegram.messenger.UserConfig;
public class PickerContextTest {
    private static int count;
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        count++; System.out.println("PASS " + label);
    }
    public static void main(String[] args) throws Exception {
        PluginPickerContext first = PluginPickerContext.capture(0);
        PluginPickerContext second = PluginPickerContext.capture(1);
        check(first.account == 0 && first.toJson().getString("ownerId").equals("2000"), "captures actual account owner");
        UserConfig.selectedAccount = 1;
        check(first.isCurrent() && first.toJson().getInt("account") == 0, "global selection cannot retarget source account");
        check(second.isCurrent() && second.toJson().getString("ownerId").equals("3000"), "second account stays separate");
        UserConfig.owners[0] = 4000;
        check(!first.isCurrent(), "owner slot reuse invalidates captured context");
        boolean refused = false;
        try { first.toJson(); } catch (IllegalStateException e) { refused = true; }
        check(refused, "stale provenance cannot be serialized");
        check(second.isCurrent(), "another account survives first owner replacement");
        check(PluginPickerContext.capture(2) == null && PluginPickerContext.capture(-1) == null
                && PluginPickerContext.capture(3) == null, "inactive and out-of-range accounts fail closed");
        System.out.println("Picker context JVM cases passed: " + count);
    }
}
