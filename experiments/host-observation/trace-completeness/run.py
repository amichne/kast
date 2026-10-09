from dataclasses import dataclass, asdict, field
from enum import Enum
from pathlib import Path
import hashlib, json, os, sys, zipfile, io, argparse, shutil
SUITE=Path(__file__).resolve().parent
sys.path.insert(0,str(SUITE.parent))
parser=argparse.ArgumentParser()
parser.add_argument('action',choices=['prepare','run'])
parser.add_argument('--owned-root',type=Path,required=True)
parser.add_argument('--pid',type=int)
parser.add_argument('--version')
parser.add_argument('--phase',choices=['baseline','candidate'],default='candidate')
args=parser.parse_args()
ROOT=args.owned_root.resolve()
assert ROOT != Path.home() and ROOT not in SUITE.parents and ROOT != SUITE, 'INVALID_OWNED_ROOT'
if args.action=='prepare':
    assert not ROOT.exists(), 'OWNED_ROOT_ALREADY_EXISTS'
    ROOT.mkdir(mode=0o700)
    shutil.copytree(SUITE/'fixture',ROOT/'fixture')
    for name in ['manifest.json','manifest.sha256','requests-manifest.json','requests-manifest.sha256']:
        shutil.copy2(SUITE/name,ROOT/name)
    print('Prepared frozen fixture at',ROOT/'fixture')
    raise SystemExit(0)
assert args.pid and args.version, 'NATIVE_IDENTITY_REQUIRED'
import reproduce_semantic_queries as replay
import qualify_enterprise_cache as shapes
import kast_ide
import jsonschema

FIXTURE=ROOT/'fixture'
RPC=ROOT/'control/bin/kast-tool-rpc-complete'
MANIFEST=json.loads((ROOT/'manifest.json').read_text())
SCHEMA=next(t['inputSchema'] for t in json.loads((ROOT/'catalog.json').read_text())['catalog']['tools'] if t['name']=='query_symbols')
VALIDATOR=jsonschema.Draft202012Validator(SCHEMA)
RESULT_VALIDATOR=shapes.contract_validator('query_symbolsSemanticResult')

@dataclass(frozen=True)
class ScopedTrace:
    expansionScope: shapes.SourceDomain
    type: str=field(default='TRACE',init=False)
@dataclass(frozen=True)
class Strict:
    type: str=field(default='COMPLETE_ONLY',init=False)
    model: str=field(default='COMPILER_RESOLVED_STATIC_V1',init=False)
@dataclass(frozen=True)
class Run:
    source: shapes.Search|shapes.Location
    steps: tuple
    executionBudget: shapes.Budget
    output: shapes.Output=shapes.Output()
    retention: str='RETAIN'
    completion: Strict=Strict()
    type: str=field(default='RUN',init=False)
@dataclass(frozen=True)
class Spec:
    label: str
    payload: shapes.Payload
@dataclass(frozen=True)
class RequestsManifest:
    type: str
    fixtureManifestSha256: str
    inputSchemaSha256: str
    configSha256: str
    requests: tuple[Spec,...]
@dataclass(frozen=True)
class Call:
    label: str
    payload: dict # Actual canonical contract JSON, surrounding receipt owns this opaque field.
    process: dict # Existing capture DTO projection; includes bounded actual stdout/stderr.
    document: dict|None # Validated public semantic document or absent transport document.
@dataclass(frozen=True)
class Finding:
    label: str
    status: str
    exhaustive: bool|None
    itemCount: int
    retainedCount: int
    rejection: dict|None # Canonical closed public rejection contract, retained verbatim.
    calls: tuple[str,...]
@dataclass(frozen=True)
class BaselineReport:
    type: str
    sourceRevision: str
    fixtureRevision: str
    fixtureManifestSha256: str
    requestsManifestSha256: str
    nativePinSha256: str
    findings: tuple[Finding,...]
    sourceUnchanged: bool

def hashfile(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def encode(v):return json.loads(json.dumps(asdict(v)))
def write(p,v):p.write_text(json.dumps(encode(v),indent=2)+'\n')
def check_fixture():
    assert hashfile(ROOT/'manifest.json')==(ROOT/'manifest.sha256').read_text().strip(),'MANIFEST_CHANGED'
    for f in MANIFEST['files']:assert hashfile(FIXTURE/f['path'])==f['sha256'],'FIXTURE_CHANGED:'+f['path']

def verify_native():
    pin=json.loads((ROOT/(args.phase+'-native-pin.json')).read_text());assert pin['outcome']=='READY','NATIVE_NOT_READY'
    assert pin['pid']==args.pid and pin['version']==args.version,'HOST_PIN_MISMATCH'
    assert Path(pin['profile']['home'])==ROOT/'h','HOST_HOME_MISMATCH'
    for key in ['config','system','plugins']:assert Path(pin['profile'][key])==ROOT/key,'HOST_PROFILE_MISMATCH:'+key
    assert all(pin['model'][key] for key in ['smart','saved','committed']),'MODEL_NOT_READY'
    archives={}
    for p in (ROOT/'plugins/kast-ide-hosted/lib').glob('*.jar'):
        with zipfile.ZipFile(p) as z:
            for c in pin['classes']:
                name=c['name'].replace('.','/')+'.class'
                if name in z.namelist():archives.setdefault(c['name'],[]).append(hashlib.sha256(z.read(name)).hexdigest())
    for c in pin['classes']:assert archives.get(c['name'])==[c['sha256']],'LOADED_CLASS_MISMATCH:'+c['name']
    for s in pin['sources']:assert s['diskSha256']==s['psiSha256']==hashfile(FIXTURE/s['file']),'NATIVE_SOURCE_MISMATCH'
    result=kast_ide.exchange(FIXTURE,{'type':'DESCRIBE'},ROOT/'h')
    assert isinstance(result,kast_ide.Answer) and result.document['type']=='KAST_IDE_HOST' and result.document['hostPid']==pin['pid'],'NATIVE_ENDPOINT_UNAVAILABLE'
    (ROOT/(args.phase+'-native-preflight.json')).write_text(json.dumps(asdict(result),indent=2)+'\n')
    print('Native READY; class hashes:',len(pin['classes']),'source hashes:',len(pin['sources']),flush=True)

def run():
    check_fixture();verify_native()
    assert hashfile(ROOT/'requests-manifest.json')==(ROOT/'requests-manifest.sha256').read_text().strip(),'REQUESTS_CHANGED'
    output=ROOT/(args.phase+'-observations');output.mkdir()
    os.environ['JAVA_OPTS']='-Duser.home='+str(ROOT/'h')
    os.environ['KAST_CONFIGURATION_FILE']=str(ROOT/'control/config/environment')
    os.environ['KAST_INSTALL_IDEA_HOME']=str(ROOT/'IntelliJ IDEA.app/Contents')
    os.environ['IDEA_PROPERTIES']=str(ROOT/'idea.properties')
    os.environ['IDEA_VM_OPTIONS']=str(ROOT/'idea.vmoptions')
    os.environ['GRADLE_USER_HOME']=str(ROOT/'gradle')
    os.environ['TMPDIR']=str(ROOT/'tmp')
    count=0;stored=0;calls=[]
    def invoke(payload,label):
        nonlocal count,stored
        assert count<128,'CALL_CAP'
        arguments=payload if isinstance(payload,dict) else encode(payload)
        VALIDATOR.validate(arguments)
        p=replay.capture([RPC,'call','query_symbols'],FIXTURE,json.dumps(arguments),90)
        assert len(p.get('stdout','').encode())<=4*1024*1024 and len(p.get('stderr','').encode())<=2*1024*1024,'CALL_RECEIPT_CAP'
        stored+=len(json.dumps(p).encode());assert stored<=32*1024*1024,'TOTAL_RECEIPT_CAP'
        name=f'call-{count:03}.json';count+=1;calls.append(name)
        write(output/name,Call(label,arguments,p,None))
        envelope=json.loads(p['stdout'])
        document=envelope.get('document')
        write(output/name,Call(label,arguments,p,document))
        assert document is not None,'NO_SEMANTIC_DOCUMENT:'+name+':'+envelope.get('reason','unknown')
        RESULT_VALIDATOR.validate(document)
        return document
    findings=[]
    for spec in json.loads((ROOT/'requests-manifest.json').read_text())['requests']:
        before=len(calls);label=spec['label']
        log=ROOT/'logs/idea.log';start=log.stat().st_size
        first=invoke(spec['payload'],label)
        pages=[]
        if first.get('status')=='rejected':
            rejection=first.get('rejection',{})
            evidence=rejection.get('detail',{}).get('evidence',{})
            if evidence.get('type')=='RETAINED':
                recovered=invoke(evidence['nextQuery'],label+'.recovery')
                assert recovered.get('interpretation',{}).get('type')=='POLICY_REJECTED_EVIDENCE','RECOVERY_MANUFACTURED_SUCCESS'
                pages=shapes.retained_pages(recovered,shapes.Output(),shapes.Budget(**MANIFEST['grant']),lambda v:invoke(v,label+'.page'),max_calls=32)
        else:
            pages=shapes.retained_pages(first,shapes.Output(),shapes.Budget(**MANIFEST['grant']),lambda v:invoke(v,label+'.page'),max_calls=32)
        end=log.stat().st_size
        assert 0<=end-start<=2*1024*1024,'LOG_APPEND_CAP_OR_ROTATION'
        with log.open('rb') as f:f.seek(start);segment=f.read(end-start).decode('utf-8',errors='strict')
        records=[line for line in segment.splitlines() if any(x in line for x in ('kast_semantic_read','kast_project_read_epoch','kast_workspace_preparation'))]
        (output/(label+'.native.jsonl')).write_text('\n'.join(records)+'\n')
        finding=Finding(label,first.get('status','HOST_REJECTED' if first.get('outcome')=='rejected' else 'UNAVAILABLE'),first.get('coverage',{}).get('exhaustive'),len(first.get('items',[])),sum(len(p.get('items',[])) for p in pages),first.get('rejection',first if first.get('outcome')=='rejected' else None),tuple(calls[before:]))
        findings.append(finding)
        report=ROOT/(args.phase+'-report.json')
        write(report,BaselineReport('TRACE_'+args.phase.upper()+'_REPORT',MANIFEST['sourceRevision'],MANIFEST['fixtureRevision'],hashfile(ROOT/'manifest.json'),hashfile(ROOT/'requests-manifest.json'),hashfile(ROOT/(args.phase+'-native-pin.json')),tuple(findings),True))
        print(label, finding.status,'exhaustive:',finding.exhaustive,'retained:',finding.retainedCount,'calls:',len(finding.calls),flush=True)
        check_fixture()
    print('Report:',ROOT/(args.phase+'-report.json'),flush=True)

run()
