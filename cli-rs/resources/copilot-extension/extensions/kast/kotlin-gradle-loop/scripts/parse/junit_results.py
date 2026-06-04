#!/usr/bin/env python3
"""Parse JUnit XML test results into structured JSON.
Usage: python3 junit_results.py <project_root> [--module :module-name]
"""
import json, os, sys
import xml.etree.ElementTree as ET
from pathlib import Path

def find_junit_xml_files(project_root, module_filter=None):
    root = Path(project_root)
    results = []
    for xml_file in root.rglob("build/test-results/**/TEST-*.xml"):
        rel = xml_file.relative_to(root)
        parts = list(rel.parts)
        module = ":"
        for i, part in enumerate(parts):
            if part == "build":
                mp = parts[:i]
                module = ":" + ":".join(mp) if mp else ":"
                break
        if module_filter and module != module_filter:
            continue
        results.append((xml_file, module))
    return results

def parse_junit_xml(xml_path, module):
    try: tree = ET.parse(xml_path)
    except ET.ParseError: return [], []
    root = tree.getroot()
    suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
    if root.tag not in ("testsuite", "testsuites"): return [], []
    parsed_suites, all_failures = [], []
    for suite in suites:
        tests = int(suite.get("tests", 0))
        failures = int(suite.get("failures", 0))
        errors = int(suite.get("errors", 0))
        skipped_count = int(suite.get("skipped", 0))
        time_val = float(suite.get("time", 0))
        for tc in suite.findall("testcase"):
            fe = tc.find("failure") or tc.find("error")
            if fe is not None:
                st = fe.text or ""
                head = "\n".join(st.strip().split("\n")[:5])
                all_failures.append({
                    "class": tc.get("classname","unknown"), "method": tc.get("name","unknown"),
                    "module": module, "message": (fe.get("message") or "")[:300],
                    "type": fe.get("type","unknown"), "stacktrace_head": head[:500]
                })
        parsed_suites.append({
            "name": suite.get("name","unknown"), "module": module, "tests": tests,
            "failures": failures + errors, "skipped": skipped_count,
            "duration_seconds": round(time_val, 3)
        })
    return parsed_suites, all_failures

def main():
    if len(sys.argv) < 2:
        json.dump({"ok":False,"error":"Usage: junit_results.py <project_root> [--module :name]"}, sys.stdout); sys.exit(1)
    project_root = os.path.abspath(sys.argv[1])
    mf = None
    if "--module" in sys.argv:
        idx = sys.argv.index("--module")
        if idx+1 < len(sys.argv): mf = sys.argv[idx+1]
    xml_files = find_junit_xml_files(project_root, mf)
    if not xml_files:
        json.dump({"ok":True,"total":0,"passed":0,"failed":0,"skipped":0,
                   "duration_seconds":0,"suites":[],"failures":[],
                   "note":"No JUnit XML files found. Run a test task first."}, sys.stdout, indent=2)
        return
    all_suites, all_failures = [], []
    total = failed = skipped = 0
    total_duration = 0.0
    for xp, mod in xml_files:
        suites, failures = parse_junit_xml(xp, mod)
        for s in suites:
            total += s["tests"]; failed += s["failures"]; skipped += s["skipped"]
            total_duration += s["duration_seconds"]
        all_suites.extend(suites); all_failures.extend(failures)
    json.dump({"ok":True,"total":total,"passed":total-failed-skipped,"failed":failed,
               "skipped":skipped,"duration_seconds":round(total_duration,3),
               "suites":all_suites,"failures":all_failures}, sys.stdout, indent=2)

if __name__ == "__main__":
    main()
