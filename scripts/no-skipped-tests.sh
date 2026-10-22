#!/bin/sh
# CI: fails when any test was skipped. A skip (no Docker, no zsh, no systemd user session, image not pulled)
# would otherwise look green while proving nothing.
exec python3 - <<'PY'
import glob, re, sys
skipped = []
for f in glob.glob('*/build/test-results/test/*.xml'):
    xml = open(f, encoding='utf-8').read()
    for m in re.finditer(r'<testcase name="([^"]*)" classname="([^"]*)"[^>]*>\s*<skipped(?: message="([^"]*)")?', xml):
        skipped.append(f"  {m.group(2)}.{m.group(1)}: {m.group(3) or ''}")
if skipped:
    print("These tests were skipped (CI must run every test):")
    print("\n".join(skipped))
    sys.exit(1)
print("No skipped tests.")
PY
