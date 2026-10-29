import json, os, pathlib, sys
pathlib.Path(os.environ['KAST_LAUNCH_REPORT']).write_text(json.dumps({
    'argv': sys.argv, 'javaHome': os.environ['JAVA_HOME'],
    'projectLauncher': os.environ.get('KAST_PROJECT_LAUNCHER', 'absent')}))
raise SystemExit(int(os.environ['KAST_GATE_EXIT']))
