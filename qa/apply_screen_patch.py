#!/usr/bin/env python3
"""QA screenshot builds only: let LaunchActivity open a Zephyr screen from an intent extra without login.
Run from the repo root inside the CI runner. Changes are NOT committed."""
import pathlib, shutil, sys
root = pathlib.Path(".")
la = root / "TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java"
s = la.read_text()
old = "actionBarLayout.addFragmentToStack(getClientNotActivatedFragment());"
if s.count(old) != 1:
    sys.exit(f"expected exactly one anchor, found {s.count(old)}")
new = ("BaseFragment qaFragment = QaScreenLauncher.create(getIntent());\n"
       "                actionBarLayout.addFragmentToStack(qaFragment != null ? qaFragment : getClientNotActivatedFragment());")
la.write_text(s.replace(old, new))
shutil.copy(pathlib.Path(__file__).parent / "patches" / "QaScreenLauncher.java", la.parent / "QaScreenLauncher.java")
print("patched", la)
# release builds are minified: keep the screens we open by class name
pr = root / "TMessagesProj/proguard-rules.pro"
pr.write_text(pr.read_text() + "\n# QA screenshots only\n-keep class org.telegram.ui.Zephyr* { *; }\n-keep class org.telegram.ui.SettingsActivity { *; }\n-keep class org.telegram.ui.PrivacySettingsActivity { *; }\n")
print("proguard keep rules added")
