import argparse, json, pathlib, shutil, subprocess, sys, tempfile
parser = argparse.ArgumentParser()
parser.add_argument("--java", required=True)
parser.add_argument("--classpath", required=True)
args = parser.parse_args()
source=pathlib.Path(__file__).resolve().parent
temporary=tempfile.TemporaryDirectory(prefix="kast-binding-receipt-test-")
root=pathlib.Path(temporary.name)
example=root/'examples/topology-identity-binding'
shutil.copytree(source,example,ignore=shutil.ignore_patterns("build", ".gradle", "__pycache__"))
classpath=args.classpath
java=str(pathlib.Path(args.java).resolve(strict=True))
def run(args,cwd=root,expected=0):
 r=subprocess.run(args,cwd=cwd,text=True,capture_output=True)
 assert r.returncode==expected,(args,r.returncode,r.stdout,r.stderr)
 return r
run(['git','init','-q']);run(['git','add','.']);run(['git','-c','user.name=Reference Test','-c','user.email=reference-test@example.invalid','commit','-qm','isolated receipt test fixture'])
(example/'build').mkdir()
run([java,'-cp',classpath,'kast.example.binding.ProgramKt','graph',str(example/'build/program.json')])
verify=[sys.executable,str(example/'verify.py')]
record=verify+['receipt','build/program.json',java,classpath]
run(record,example); receipt=example/'build/identity-proof/model.json'; saved=receipt.read_bytes()
run(verify+['receipt-check',str(receipt)],example)
checks=['actual-command-receipt','current-receipt-verified']
report=example/'build/identity-proof/reference-observed.json';original=report.read_bytes();report.write_bytes(original+b' ')
run(verify+['receipt-check',str(receipt)],example,2);report.write_bytes(original);checks+=['artifact-corruption-rejected']
data=json.loads(saved);data['artifacts']={};receipt.write_text(json.dumps(data))
run(verify+['receipt-check',str(receipt)],example,2);receipt.write_bytes(saved);checks+=['missing-artifact-set-rejected']
tracked=example/'README.md';old=tracked.read_bytes();tracked.write_bytes(old+b'\nchanged\n')
run(verify+['receipt-check',str(receipt)],example,2);tracked.write_bytes(old);checks+=['dirty-source-rejected']
run(['git','-c','user.name=Reference Test','-c','user.email=reference-test@example.invalid','commit','--allow-empty','-qm','new revision'])
run(verify+['receipt-check',str(receipt)],example,2);checks+=['new-head-invalidates-receipt']
run(record,example)
result=run(verify+['delivery','build/program.json'],example,2)
assert 'production-proof-not-implemented' in result.stdout
checks+=['reference-cannot-complete-production']
run(verify+['idea','','262.9437.185','262.9437.185-IJ'],example,2);checks+=['missing-native-host-rejected']
print("PASS: " + str(len(checks)) + " isolated receipt-mechanism checks")
for name in checks: print(name)
temporary.cleanup()
