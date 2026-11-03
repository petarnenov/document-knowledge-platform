"""Original Java compiler catalog assembly; no runtime registration or caught-error inputs."""
import importlib.util
import json
import os
from pathlib import Path
import re
import stat

spec=importlib.util.spec_from_file_location('java_catalog_manifest',Path(__file__).with_name('log-build-manifest.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


def require(value):
    if not value:raise ValueError('LOG_JAVA_CATALOG_INVALID')


def unique(pairs):
    result={}
    for name,value in pairs:
        require(name not in result);result[name]=value
    return result


def original_file(path,maximum,expected=None):
    path=Path(path);require(path.is_absolute() and path.resolve(strict=True)==path and not path.is_symlink())
    info=path.stat();require(stat.S_ISREG(info.st_mode) and 0<info.st_size<=maximum)
    digest,_,size,_=m.file_hash(path)
    if expected is not None:require(expected=={'path':str(path),'sha256':digest,'bytes':size})
    return {'path':str(path),'sha256':digest,'bytes':size}


def selection(root):
    path=m.regular(root,'tools/runtime/log-java-vocabulary.json');original_file(path,65536)
    value=json.loads(path.read_bytes(),object_pairs_hook=unique)
    require(type(value) is dict and value.keys()=={'schemaVersion','phases','errorCodes','requiredExceptionClasses','optionalExceptionClasses'}
        and type(value['schemaVersion']) is int and value['schemaVersion']==1)
    for key,pattern,maximum,limit in (('phases',r'[A-Za-z][A-Za-z0-9_.-]*',96,256),
            ('errorCodes',r'[A-Z0-9_]+',96,1024),('requiredExceptionClasses',r'[A-Za-z0-9_.$]+',256,256),
            ('optionalExceptionClasses',r'[A-Za-z0-9_.$]+',256,256)):
        rows=value[key];require(type(rows) is list and len(rows)<=limit and len(set(rows))==len(rows))
        for row in rows:m.text(row,pattern,maximum)
    require({'STARTUP','SHUTDOWN'}<=set(value['phases']))
    require(not set(value['requiredExceptionClasses'])&set(value['optionalExceptionClasses']))
    return value


def compilation_inputs(root,record):
    require(record.keys()=={'schemaVersion','component','toolchain','dependencies','compilation'}
        and type(record['schemaVersion']) is int and record['schemaVersion']==2)
    value=record['compilation'];require(type(value) is dict and value.keys()=={'projects','classes','artifacts'})
    projects=value['projects'];classes=value['classes'];artifacts=value['artifacts']
    require(type(projects) is list and 1<=len(projects)<=32 and type(classes) is list and len(classes)<=4096
        and type(artifacts) is list and len(artifacts)<=256)
    indexed={};bindings=[]
    for row in projects:
        require(type(row) is dict and row.keys()=={'project','sourceRoots','classRoots'})
        name=m.text(row['project'],r':(?:[a-z][a-z0-9-]*:)*[a-z][a-z0-9-]*',256);require(name not in indexed)
        project=root/str(name[1:]).replace(':','/');require(project.is_dir() and project.resolve()==project)
        if name.startswith(':services:'):require(name.startswith(':services:'+record['component']+':'))
        else:require(name.startswith(':modules:'))
        for key in ('sourceRoots','classRoots'):
            require(type(row[key]) is list and len(row[key])<=16 and len(set(row[key]))==len(row[key]))
            for raw in row[key]:
                path=Path(raw);require(path.is_absolute() and path.resolve(strict=True)==path and path.is_dir())
                require(path.is_relative_to(project/('build' if key=='classRoots' else 'src/main')))
        indexed[name]=row
    seen=set();total=0
    for row in classes:
        require(type(row) is dict and row.keys()=={'project','path','sha256','bytes'} and row['project'] in indexed)
        path=Path(row['path']);require(path not in seen and path.suffix=='.class');seen.add(path)
        require(any(path.is_relative_to(Path(base)) for base in indexed[row['project']]['classRoots']))
        original={key:row[key] for key in ('path','sha256','bytes')};bindings.append(original_file(path,8*1024*1024,original))
        total+=row['bytes'];require(total<=128*1024*1024)
    dependencies={(row['name'],row['version']) for row in m.dependencies(record['dependencies'])}
    found=set()
    for row in artifacts:
        require(type(row) is dict and row.keys()=={'name','version','path','sha256','bytes'})
        key=(row['name'],row['version']);require(key in dependencies and key not in found);found.add(key)
        bindings.append(original_file(row['path'],64*1024*1024,{key:row[key] for key in ('path','sha256','bytes')}))
    require(found==dependencies)
    return indexed,bindings


def assemble(root,record,metadata,hierarchy):
    """Hierarchy is compiler-byte metadata supplied by the private pinned classpath reader."""
    root=Path(root);projects,bindings=compilation_inputs(root,record);chosen=selection(root)
    inputs={row['path'] for row in m.inputs(root)['inputs']};require(type(metadata) is dict
        and metadata.keys()=={'schemaVersion','classes','unsupportedSymbols','missingSources'}
        and type(metadata['schemaVersion']) is int and metadata['schemaVersion']==1)
    for key in ('unsupportedSymbols','missingSources'):require(type(metadata[key]) is int and 0<=metadata[key]<=9007199254740991)
    require(type(hierarchy) is dict and len(hierarchy)<=8192 and hierarchy.get('java.lang.Throwable')=='java.lang.Object'
        and 'java.lang.Object' in hierarchy and hierarchy['java.lang.Object'] is None)
    require(type(metadata['classes']) is list and len(metadata['classes'])<=len(record['compilation']['classes']))
    sources={};frames={};classes={};seen=set();missing=0;unsupported=metadata['unsupportedSymbols']
    for row in metadata['classes']:
        require(type(row) is dict and row.keys()=={'inputIndex','name','superclass','sourceFile','methods'})
        number=row['inputIndex'];require(type(number) is int and 0<=number<len(record['compilation']['classes']) and number not in seen);seen.add(number)
        name=m.text(row['name'],r'[A-Za-z0-9_.$]+',256);require(name not in classes);classes[name]=row['superclass']
        if row['superclass'] is not None:m.text(row['superclass'],r'[A-Za-z0-9_.$]+',256)
        file=record['compilation']['classes'][number];project=projects[file['project']];package=name.rpartition('.')[0].replace('.','/')
        require(any(Path(file['path'])==Path(base)/(name.replace('.','/')+'.class') for base in project['classRoots']))
        source=row['sourceFile'];candidates=set()
        if source is not None:
            m.text(source,r'[A-Za-z0-9_$-]+\.java',256)
            for base in project['sourceRoots']:
                relative=(Path(base)/package/source).relative_to(root).as_posix()
                if relative in inputs:candidates.add(relative)
        require(len(candidates)<=1)
        if not candidates:missing+=1;continue
        canonical=candidates.pop()
        if len(canonical)>256 or re.fullmatch(r'[A-Za-z0-9_./$-]+',canonical) is None:unsupported+=1;continue
        sources[name]=canonical;require(type(row['methods']) is list and len(row['methods'])<=32768 and len(set(row['methods']))==len(row['methods']))
        for method in row['methods']:
            m.text(method,r'[A-Za-z0-9_.$<>-]+',256);symbol=name+'.'+method
            if len(symbol)>256:unsupported+=1;continue
            require(symbol not in frames or frames[symbol]==canonical);frames[symbol]=canonical;require(len(frames)<=32768)
    def throwable(name):
        seen=set();current=name
        while current is not None:
            require(current not in seen and len(seen)<64);seen.add(current)
            if current=='java.lang.Throwable':return True
            require(current in classes or current in hierarchy)
            current=classes[current] if current in classes else hierarchy[current]
        return False
    exceptions={name for name in sources if throwable(name)}
    for name in chosen['requiredExceptionClasses']:require(name in hierarchy and throwable(name));exceptions.add(name)
    optional_missing=0
    for name in chosen['optionalExceptionClasses']:
        if name not in hierarchy:optional_missing+=1;continue
        require(throwable(name));exceptions.add(name)
    require(len(exceptions)<=8192)
    dependencies=m.dependencies(record['dependencies'])+[row for row in m.dependencies(record['toolchain']) if row['name']=='java']
    require(len({row['name'] for row in dependencies})==len(dependencies))
    vocabulary={'events':sorted(m.EVENTS),'phases':chosen['phases'],'errorCodes':chosen['errorCodes'],'dependencies':dependencies,
        'exceptionClasses':sorted(exceptions),'frames':[{'symbol':name,'source':frames[name]} for name in sorted(frames)]}
    m.vocabulary(vocabulary)
    return {'vocabulary':vocabulary,'bindings':bindings,'counts':{'ownedClasses':len(classes),'frames':len(frames),'exceptionClasses':len(exceptions),
        'missingSources':missing,'unsupportedSymbols':unsupported,'missingOptionalExceptions':optional_missing}}


def revalidate(bindings):
    for row in bindings:original_file(row['path'],max(row['bytes'],1),row)


class MetadataReader:
    """Private JDK-only process; inspected application classes never enter its classpath."""
    def __init__(self,root,java_home,directory):
        self.directory=Path(directory);self.java=Path(java_home);self.sequence=0
        require(self.directory.is_absolute() and self.directory.resolve(strict=True)==self.directory)
        self.output=self.directory/'compiler-metadata';self.output.mkdir(mode=0o700)
        source=m.regular(root,'tools/runtime/java/org/documentknowledge/loggingbuild/LogJavaClassMetadata.java')
        self.bindings=[original_file(source,65536)]
        for relative,maximum in (('bin/java',64*1024*1024),('bin/javac',64*1024*1024),
                ('release',65536),('lib/modules',512*1024*1024)):
            path=(self.java/relative).resolve(strict=True)
            require(path.is_relative_to(self.java));self.bindings.append(original_file(path,maximum))
        self.environment={key:value for key,value in os.environ.items()
            if key not in {'JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH'}}
        self.environment['JAVA_HOME']=str(self.java)
        self.invoke([str(self.java/'bin/javac'),'-proc:none','-classpath','','-d',str(self.output),str(source)],'compile',65536)
        for path in sorted(self.output.rglob('*.class')):self.bindings.append(original_file(path,8*1024*1024))
        require(any(Path(row['path']).suffix=='.class' for row in self.bindings))

    def invoke(self,command,label,maximum):
        import subprocess
        self.sequence+=1;path=self.directory/('metadata-'+str(self.sequence)+'-'+label+'.private')
        fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'wb') as stream:
            result=subprocess.run(command,cwd=self.directory,env=self.environment,
                stdout=stream,stderr=subprocess.STDOUT,timeout=60)
        require(result.returncode==0 and path.stat().st_size<=maximum)
        return path.read_bytes()

    def read(self,paths):
        require(1<=len(paths)<=4096)
        index=self.directory/('metadata-'+str(self.sequence+1)+'.inputs.private')
        raw=''.join(str(path)+'\n' for path in paths)
        require(len(raw.encode())<=2*1024*1024 and all('\n' not in str(path) and '\r' not in str(path) for path in paths))
        fd=os.open(index,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'w',encoding='utf-8') as stream:stream.write(raw)
        value=json.loads(self.invoke([str(self.java/'bin/java'),'-Xmx128m','-cp',str(self.output),
            'org.documentknowledge.loggingbuild.LogJavaClassMetadata',str(index)],'read',16*1024*1024),object_pairs_hook=unique)
        require(type(value) is dict and value.get('schemaVersion')==1 and type(value.get('classes')) is list)
        return value


def extract(root,record,java_home,directory):
    """Build hierarchy only from exact owned/JDK/resolved-artifact bytes, then assemble v1."""
    import zipfile
    root=Path(root);directory=Path(directory)
    _,bindings=compilation_inputs(root,record);chosen=selection(root)
    reader=MetadataReader(root,java_home,directory)
    owned=reader.read([row['path'] for row in record['compilation']['classes']])
    owned_parents={row['name']:row['superclass'] for row in owned['classes']}
    require(len(owned_parents)==len(owned['classes']))
    hierarchy={};missing=set();visited=set();total=0
    archives=[]
    try:
        for artifact in record['compilation']['artifacts']:
            archive=zipfile.ZipFile(artifact['path']);archives.append(archive)
            require(len(archive.infolist())<=65536)
        pending=set(chosen['requiredExceptionClasses'])|set(chosen['optionalExceptionClasses'])|{'java.lang.Object'}
        pending.update(parent for parent in owned_parents.values() if parent is not None)
        while pending:
            name=min(pending);pending.remove(name)
            if name in visited:continue
            visited.add(name)
            m.text(name,r'[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*',256)
            require(len(visited)<=8192)
            if name in owned_parents:
                parent=owned_parents[name]
                if parent is not None:pending.add(parent)
                continue
            if name.startswith(('java.','javax.','jdk.','sun.','com.sun.','org.w3c.','org.xml.')):
                metadata=reader.read(['jdk:'+name])
            else:
                entry=name.replace('.','/')+'.class';matches=[]
                for archive in archives:
                    rows=[row for row in archive.infolist() if row.filename==entry]
                    require(len(rows)<=1)
                    if rows:matches.append((archive,rows[0]))
                require(len(matches)<=1)
                if not matches:missing.add(name);continue
                archive,info=matches[0];require(0<info.file_size<=8*1024*1024 and not info.is_dir())
                with archive.open(info) as stream:raw=stream.read(8*1024*1024+1)
                total+=len(raw);require(len(raw)==info.file_size and total<=128*1024*1024)
                path=reader.output/('hierarchy-'+str(len(hierarchy))+'.class')
                fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
                with os.fdopen(fd,'wb') as stream:stream.write(raw)
                metadata=reader.read([path])
            require(len(metadata['classes'])==1 and metadata['classes'][0]['name']==name)
            parent=metadata['classes'][0]['superclass'];hierarchy[name]=parent
            if parent is not None:pending.add(parent)
        result=assemble(root,record,owned,hierarchy)
        result['bindings']+=reader.bindings
        result['counts']['hierarchyClasses']=len(hierarchy)
        revalidate(result['bindings'])
        return result
    finally:
        for archive in archives:archive.close()
