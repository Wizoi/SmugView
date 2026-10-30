#!/usr/bin/env python3
"""Prints the test results found under a folder as GitHub notice annotations (readable without logging in).

Usage: report-tests.py <folder with JUnit XML> <title>. Totals first, then each suite, then any failed test name.
"""
import glob
import sys
import xml.etree.ElementTree as ET

folder, title = sys.argv[1], sys.argv[2]
files = sorted(glob.glob(f"{folder}/**/*.xml", recursive=True))
total = failed = skipped = 0
lines = []
for f in files:
    suite = ET.parse(f).getroot()
    if suite.tag != "testsuite":
        continue
    t, fl, er, sk = (int(suite.get(k, 0)) for k in ("tests", "failures", "errors", "skipped"))
    total += t
    failed += fl + er
    skipped += sk
    lines.append(f"{suite.get('name')}: {t} tests, {fl + er} failed, {sk} skipped")
    for case in suite.findall("testcase"):
        if case.find("failure") is not None or case.find("error") is not None:
            lines.append(f"   FAILED {case.get('name')}")
head = f"{total} tests, {failed} failed, {skipped} skipped, in {len(lines)} lines"
level = "notice" if total and not failed else "error"
text = (head + "\n" + "\n".join(lines)).replace("%", "%25").replace("\r", "").replace("\n", "%0A")
print(f"::{level} title={title}::{text[:3800]}")
if not files:
    print(f"::error title={title}::no test result files under {folder}")
