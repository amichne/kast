#!/usr/bin/env python3
"""Parse Kotlin build reports to diagnose incremental compilation health.
Usage: python3 kotlin_build_report.py <project_root>
"""
import json, os, re, sys
from pathlib import Path

def find_build_reports(project_root):
    root = Path(project_root); reports = []; seen = set()
    gp = root / "gradle.properties"
    if gp.exists():
        for line in gp.read_text().splitlines():
            line = line.strip()
            if line.startswith("kotlin.build.report.file="):
                rp = root / line.split("=",1)[1].strip()
                if rp.exists() and rp not in seen: reports.append(rp); seen.add(rp)
    for pat in ["build/reports/kotlin-build-report*.txt","build/kotlin/compile*/**/*build-report*.txt",
                "**/build/reports/kotlin-build/*.txt"]:
        for f in root.rglob(pat):
            if f not in seen: reports.append(f); seen.add(f)
    return reports

def parse_report(report_path):
    content = report_path.read_text(errors="replace")
    compilations = []; reasons = {}
    tp = re.compile(r"Task info:\s*Gradle task:\s*:?([\w:.-]+):(\w+)")
    ip = re.compile(r"Compilation\s+(?:is|was)\s+(incremental|non-incremental)")
    rp = re.compile(r"Non-incremental\s+compilation\s+because[:\s]+(.+)", re.IGNORECASE)
    fp = re.compile(r"Compiled\s+(\d+)\s+(?:of\s+)?(\d+)?\s*(?:Kotlin)?\s*(?:source)?\s*files?")
    dp = re.compile(r"Total\s+Kotlin\s+compilation\s+time:\s*([\d.]+)\s*(ms|s)", re.IGNORECASE)
    for section in re.split(r"(?=Task info:)", content):
        tm = tp.search(section)
        if not tm: continue
        raw = tm.group(1)
        module = ":" + raw if not raw.startswith(":") else raw
        im = ip.search(section)
        is_inc = True
        if im: is_inc = im.group(1) == "incremental"
        reason = None
        if not is_inc:
            rm = rp.search(section)
            if rm: reason = rm.group(1).strip(); reasons[reason] = reasons.get(reason,0)+1
        fc, tf = None, None
        fm = fp.search(section)
        if fm:
            fc = int(fm.group(1))
            if fm.group(2): tf = int(fm.group(2))
        dur = None
        dm = dp.search(section)
        if dm:
            v = float(dm.group(1)); u = dm.group(2).lower()
            dur = int(v) if u == "ms" else int(v*1000)
        compilations.append({"module":module,"incremental":is_inc,"reason":reason,
                             "files_compiled":fc,"total_files":tf,"duration_ms":dur})
    return compilations, reasons

def main():
    if len(sys.argv)<2:
        json.dump({"ok":False,"error":"Usage: kotlin_build_report.py <project_root>"}, sys.stdout); sys.exit(1)
    pr = os.path.abspath(sys.argv[1])
    reports = find_build_reports(pr)
    if not reports:
        json.dump({"ok":True,"reports_found":0,"compilations":[],"non_incremental_modules":[],
                   "non_incremental_reasons":{},
                   "note":"No Kotlin build reports found. Enable with kotlin.build.report.output=file in gradle.properties."},
                  sys.stdout, indent=2); return
    ac = []; ar = {}
    for r in reports:
        c, rs = parse_report(r); ac.extend(c)
        for k,v in rs.items(): ar[k] = ar.get(k,0)+v
    ni = sorted({c["module"] for c in ac if not c["incremental"]})
    json.dump({"ok":True,"reports_found":len(reports),"compilations":ac,
               "non_incremental_modules":ni,"non_incremental_reasons":ar}, sys.stdout, indent=2)

if __name__ == "__main__":
    main()
