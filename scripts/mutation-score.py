# Mutation score of product code only, from PIT's mutations.xml: caught mutants / all mutants.
# "Caught" = a test failed (KILLED), hung (TIMED_OUT) or ran out of memory (MEMORY_ERROR) on the changed code.
# Test helpers (fakes, scripted runtimes, test engines) are left out: their score says nothing about the product.
# Usage: python3 scripts/mutation-score.py <module>/build/reports/pitest/mutations.xml
import re
import sys
import xml.etree.ElementTree as ET

HELPER = re.compile(r"(Test($|\$)|\.Fake|\.Scripted|\.TestEngine|\.TestContainer|\.TestPostgres|\.TestMysql)")
CAUGHT = ("KILLED", "TIMED_OUT", "MEMORY_ERROR")

caught = total = 0
for m in ET.parse(sys.argv[1]).getroot().iter("mutation"):
    if HELPER.search(m.find("mutatedClass").text):
        continue
    total += 1
    caught += m.get("status") in CAUGHT
print(f"{caught}/{total} = {100 * caught / total:.1f}%" if total else "no mutants")
