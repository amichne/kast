"""Author a finite Kotlin/Java relation graph and independent source-range oracle.

This creates source only. It never opens/imports an IDE project, installs a
plugin, or uses Kast output to derive expectations. Native qualification must
explicitly import the authored fixture and pin the loaded plugin separately.
"""

from dataclasses import dataclass, asdict
from pathlib import Path
import argparse, json, hashlib

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
arguments = parser.parse_args()
ROOT = arguments.output.resolve()
if ROOT.exists() and any(ROOT.iterdir()):
    parser.error('Output must be absent or empty; existing source is preserved.')
ROOT.mkdir(parents=True, exist_ok=True)
N=121
@dataclass(frozen=True)
class Location:
 file:str
 start:int
 end:int
 name:str
 language:str
@dataclass(frozen=True)
class QueryAnchor:
 file:str
 offset:int
 name:str
 kind:str
@dataclass(frozen=True)
class RelationCase:
 label:str
 meaning:str
 anchor:QueryAnchor
 expected:list[Location]
@dataclass(frozen=True)
class ManifestFile:
 path:str
 sha256:str
 bytes:int
@dataclass(frozen=True)
class ProviderFixtureOracle:
 authoring:str
 perLanguageCount:int
 cases:list[RelationCase]
 files:list[ManifestFile]

files={};cases=[]
def source(file,header,lines):
 text=header;locations=[]
 for name,line,language,method in lines:
  start=len(text);text+=line+'\n'
  locations.append(Location(file,start,start+len(line),name,language))
 files[file]=text
 return locations
kt='src/main/kotlin/fixture/providers/'
ja='src/main/java/fixture/providers/'
target_kt=kt+'NativeKotlinProviderTargets.kt'
kt_text='''package fixture.providers

open class NativeKotlinBase {
    open fun kotlinOverrideTarget(): Int = 0
}

interface NativeKotlinContract {
    fun kotlinImplementationTarget(): Int
}

fun nativeKotlinCallTarget() = Unit

fun nativeKotlinCalls() {
'''
kt_calls=[]
for i in range(N):
 line='    nativeKotlinCallTarget()';start=len(kt_text)+4;kt_text+=line+'\n'
 kt_calls.append(Location(target_kt,start,start+len('nativeKotlinCallTarget'),'nativeKotlinCallTarget','kotlin'))
kt_text+='}\n\nfun nativeMixedCalls() {\n';mixed=[]
for i in range(N):
 for call,target,lang in [('nativeKotlinCallTarget()','nativeKotlinCallTarget','kotlin'),('NativeJavaCalls.nativeJavaCallTarget()','nativeJavaCallTarget','java')]:
  start=len(kt_text)+4+call.index(target);kt_text+='    '+call+'\n';mixed.append(Location(target_kt,start,start+len(target),target,lang))
kt_text+='}\n';files[target_kt]=kt_text
kotlin_base=QueryAnchor(target_kt,kt_text.index('NativeKotlinBase'),'NativeKotlinBase','class')
kotlin_overrides=QueryAnchor(target_kt,kt_text.index('kotlinOverrideTarget'),'kotlinOverrideTarget','function')
kotlin_contract=QueryAnchor(target_kt,kt_text.index('NativeKotlinContract'),'NativeKotlinContract','class')
kotlin_calls=QueryAnchor(target_kt,kt_text.index('nativeKotlinCalls'),'nativeKotlinCalls','function')
mixed_calls=QueryAnchor(target_kt,kt_text.index('nativeMixedCalls'),'nativeMixedCalls','function')
java_base_file=ja+'NativeJavaBase.java'
java_base='''package fixture.providers;

public class NativeJavaBase {
    public int javaOverrideTarget() { return 0; }
}
''';files[java_base_file]=java_base
java_base_anchor=QueryAnchor(java_base_file,java_base.index('NativeJavaBase'),'NativeJavaBase','class')
java_override_anchor=QueryAnchor(java_base_file,java_base.index('javaOverrideTarget'),'javaOverrideTarget','function')
java_contract_file=ja+'NativeJavaContract.java';java_contract='''package fixture.providers;

public interface NativeJavaContract {
    int javaImplementationTarget();
}
''';files[java_contract_file]=java_contract
java_contract_anchor=QueryAnchor(java_contract_file,java_contract.index('NativeJavaContract'),'NativeJavaContract','class')
files[ja+'NativeJavaCalls.java']='''package fixture.providers;

public final class NativeJavaCalls {
    public static void nativeJavaCallTarget() {}
}
'''

def family(prefix,base,member,implements):
 kfile=kt+prefix+'Kotlin.kt';jfile=ja+prefix+'Java.java'
 krows=[];jrows=[]
 for i in range(N):
  kn='K'+prefix+f'{i:03}';jn='J'+prefix+f'{i:03}'
  kbase=base if implements else base+'()'
  kline=f'class {kn} : {kbase} {{ override fun {member}(): Int = {i} }}'
  jword='implements' if implements else 'extends'
  jline=f'    public static class {jn} {jword} {base} {{ @Override public int {member}() {{ return {i}; }} }}'
  krows.append((kn,kline,'kotlin',member));jrows.append((jn,jline,'java',member))
 klocs=source(kfile,'package fixture.providers\n\n',krows)
 jlocs=source(jfile,'package fixture.providers;\n\npublic final class '+prefix+'Java {\n',jrows)
 files[jfile]+='}\n'
 # Native class PSI includes modifiers but excludes preceding indentation.
 jlocs=[Location(x.file,x.start+4,x.end,x.name,x.language) for x in jlocs]
 method_locations=[]
 for classloc,(_,line,lang,_) in zip(klocs+jlocs,krows+jrows):
  text=files[classloc.file]
  if lang=='kotlin':
   a=text.index('override fun '+member,classloc.start,classloc.end);b=classloc.end-2
  else:
   a=text.index('@Override public int '+member,classloc.start,classloc.end);b=text.index('}',a,classloc.end)+1
  method_locations.append(Location(classloc.file,a,b,member,lang))
 return klocs+jlocs,method_locations

kb,kbo=family('KotlinBaseChildren','NativeKotlinBase','kotlinOverrideTarget',False)
jb,jbo=family('JavaBaseChildren','NativeJavaBase','javaOverrideTarget',False)
ki,_=family('KotlinContractImplementers','NativeKotlinContract','kotlinImplementationTarget',True)
ji,_=family('JavaContractImplementers','NativeJavaContract','javaImplementationTarget',True)
cases=[RelationCase('kotlin-inheritors','inheritors',kotlin_base,kb),RelationCase('java-inheritors','inheritors',java_base_anchor,jb),RelationCase('kotlin-overrides','overrides',kotlin_overrides,kbo),RelationCase('java-overrides','overrides',java_override_anchor,jbo),RelationCase('kotlin-implementations','implementations',kotlin_contract,ki),RelationCase('java-implementations','implementations',java_contract_anchor,ji),RelationCase('kotlin-callees','callees',kotlin_calls,kt_calls),RelationCase('mixed-callees','callees',mixed_calls,mixed)]
manifest=[]
for file,text in files.items():
 path=ROOT/file;path.parent.mkdir(exist_ok=True,parents=True);path.write_text(text)
 manifest.append(ManifestFile(file,hashlib.sha256(text.encode()).hexdigest(),len(text.encode())))
oracle=ProviderFixtureOracle('Authored finite source graph and literal source ranges; no Kast output establishes expected identities.',N,cases,manifest)
(ROOT/'oracle.json').write_text(json.dumps(asdict(oracle),indent=2))
print(json.dumps({'stagedRoot':str(ROOT),'sourceFiles':len(files),'cases':[{ 'case':c.label,'expected':len(c.expected)} for c in cases]},indent=2))
