import ast,json,sqlite3,tempfile,contextlib
from pathlib import Path
source=Path('tools/HermesManagement/hermes_management.py').read_text(encoding='utf-8')
tree=ast.parse(source)
function=next(n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name=='lookup_run')
namespace={'sqlite3':sqlite3,'json':json,'contextlib':contextlib}
exec(compile(ast.Module(body=[function],type_ignores=[]),'<lookup-test>','exec'),namespace)
lookup=namespace['lookup_run']
with tempfile.TemporaryDirectory() as tmp:
    path=Path(tmp)/'runs.db'; key='lookup-validation-key'
    assert lookup(path,{'key':key,'session_id':'session'})=={'found':False}
    assert not path.exists()
    with sqlite3.connect(path) as db:
        db.execute('CREATE TABLE run_idempotency(scope TEXT,idempotency_key TEXT,run_id TEXT,status_json TEXT)')
        db.execute('INSERT INTO run_idempotency VALUES(?,?,?,?)',('scope',key,'run-test',json.dumps({'session_id':'session','status':'completed'})))
    db.close()
    before=path.read_bytes()
    assert lookup(path,{'key':key,'session_id':'session'})=={'found':True,'run_id':'run-test'}
    assert lookup(path,{'key':key,'session_id':'other'})=={'found':False}
    assert path.read_bytes()==before
    with sqlite3.connect(path) as db: db.execute('INSERT INTO run_idempotency VALUES(?,?,?,?)',('other',key,'run-other',json.dumps({'session_id':'session'})))
    db.close()
    assert lookup(path,{'key':key,'session_id':'session'})=={'found':False}
    try: lookup(path,{'key':"' OR 1=1",'session_id':'session'})
    except ValueError: pass
    else: raise AssertionError('invalid key accepted')
print('PASS: missing DB never created; exact lookup; wrong session and ambiguous scope refused; read-only; invalid key rejected')
