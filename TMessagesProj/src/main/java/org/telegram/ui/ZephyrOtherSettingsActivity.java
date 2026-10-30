/*
 * Settings screens ported from exteraGram (exteraSquad), GPLv2.
 * Modified for ZephyrGram. Original: github.com/exteraSquad/exteraGram
 */
package org.telegram.ui;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

public class ZephyrOtherSettingsActivity extends ZephyrBaseSettingsActivity {

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.ZephyrSettingsOther);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asButton(ID_RESET, LocaleController.getString(R.string.ZephyrResetSettings)));
        items.add(UItem.asButton(ID_CREDITS, LocaleController.getString(R.string.ZephyrCredits)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ZephyrCreditsText)));
    }

    private static final int ID_RESET = 1;
    private static final int ID_CREDITS = 2;

    @Override
    protected void onCustomClick(UItem item, android.view.View view) {
        if (item.id == ID_RESET) {
            confirmReset(this, null);
        } else if (item.id == ID_CREDITS) {
            showCredits(this);
        }
    }
}
