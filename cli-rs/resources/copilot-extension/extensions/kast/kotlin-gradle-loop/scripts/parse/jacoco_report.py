#!/usr/bin/env python3
"""Parse JaCoCo XML coverage reports into structured JSON.
Usage: python3 jacoco_report.py <project_root> [--module :name] [--threshold N]
"""
import json, os, sys
import xml.etree.ElementTree as ET
from pathlib import Path

def find_jacoco_files(project_root, module_filter=None):
    root = Path(project_root); results = []; seen = set()
    for pattern in ["build/reports/jacoco/**/jacocoTestReport.xml", "build/reports/jacoco/**/*.xml"]:
        for xf in root.rglob(pattern):
            if xf in seen: continue
            seen.add(xf)
            rel = xf.relative_to(root); parts = list(rel.parts)
            module = ":"
            for i, p in enumerate(parts):
                if p == "build":
                    mp = parts[:i]
                    module = ":" + ":".join(mp) if mp else ":"
                    break
            if module_filter and module != module_filter: continue
            results.append((xf, module))
    return results

def parse_counter(elem, ctype):
    for c in elem.findall("counter"):
        if c.get("type") == ctype:
            m, cv = int(c.get("missed",0)), int(c.get("covered",0))
            t = m + cv
            return {"missed":m,"covered":cv,"total":t,"percent":round((cv/t)*100,1) if t>0 else 0.0}
    return {"missed":0,"covered":0,"total":0,"percent":0.0}

def parse_jacoco_xml(xml_path, module):
    try: tree = ET.parse(xml_path)
    except ET.ParseError: return None, []
    root = tree.getroot()
    if root.tag != "report": return None, []
    line, branch = parse_counter(root,"LINE"), parse_counter(root,"BRANCH")
    cls, method = parse_counter(root,"CLASS"), parse_counter(root,"METHOD")
    lowest = []
    for pkg in root.findall(".//package"):
        for ce in pkg.findall("class"):
            cn = ce.get("name","").replace("/",".")
            cl = parse_counter(ce, "LINE")
            if cl["total"] > 10:
                lowest.append({"class":cn,"module":module,"line_percent":cl["percent"],
                               "lines_missed":cl["missed"],"lines_total":cl["total"]})
    lowest.sort(key=lambda c: c["line_percent"])
    return {"module":module,"line":line,"branch":branch,"class":cls,"method":method}, lowest[:20]

def main():
    if len(sys.argv)<2:
        json.dump({"ok":False,"error":"Usage: jacoco_report.py <project_root> [--module :name] [--threshold N]"}, sys.stdout); sys.exit(1)
    project_root = os.path.abspath(sys.argv[1])
    mf = None; threshold = None; args = sys.argv[2:]; i = 0
    while i < len(args):
        if args[i]=="--module" and i+1<len(args): mf=args[i+1]; i+=2
        elif args[i]=="--threshold" and i+1<len(args):
            try: threshold=float(args[i+1])
            except ValueError: pass
            i+=2
        else: i+=1
    xml_files = find_jacoco_files(project_root, mf)
    if not xml_files:
        json.dump({"ok":True,"aggregate":{"line_percent":None,"branch_percent":None,
            "class_percent":None,"method_percent":None,"covered_lines":0,"missed_lines":0,
            "total_lines":0},"modules":[],"lowest_coverage_classes":[],
            "note":"No JaCoCo XML reports found. Run jacocoTestReport first."}, sys.stdout, indent=2)
        return
    modules = []; all_lowest = []
    agg = {k:{"missed":0,"covered":0,"total":0} for k in ["line","branch","class","method"]}
    for xp, mod in xml_files:
        result, lowest = parse_jacoco_xml(xp, mod)
        if result is None: continue
        modules.append(result); all_lowest.extend(lowest or [])
        for key in agg:
            c = result[key]
            agg[key]["missed"]+=c["missed"]; agg[key]["covered"]+=c["covered"]; agg[key]["total"]+=c["total"]
    pct = lambda a: round((a["covered"]/a["total"])*100,1) if a["total"]>0 else None
    all_lowest.sort(key=lambda c: c["line_percent"])
    aggregate = {"line_percent":pct(agg["line"]),"branch_percent":pct(agg["branch"]),
                 "class_percent":pct(agg["class"]),"method_percent":pct(agg["method"]),
                 "covered_lines":agg["line"]["covered"],"missed_lines":agg["line"]["missed"],
                 "total_lines":agg["line"]["total"]}
    output = {"ok":True,"aggregate":aggregate,"modules":modules,"lowest_coverage_classes":all_lowest[:20]}
    if threshold is not None:
        lp = aggregate["line_percent"]
        output["meets_threshold"] = lp is not None and lp >= threshold
        output["threshold"] = threshold
    json.dump(output, sys.stdout, indent=2)

if __name__ == "__main__":
    main()
