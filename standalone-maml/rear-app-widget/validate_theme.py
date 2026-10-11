"""Actual XML command/expression model; not a native/device rendering or frame-pacing test.
ABI: JADX Expression.BinaryExpression.evaluateStr concatenates only ADD recursively;
VariableAssignmentCommand object copies Variables.get(evaluateStr()), dynamic nameExp;
LoopCommand count/indexName/loopCondition; native ScreenElementArray tag is Array.
This model is an ABI regression guard, not proof of native rendering.
Time inputs are the picker bridge's read-only uptime milliseconds, not XML wall-clock time.
"""
from pathlib import Path
import ast
import math
import re
import xml.etree.ElementTree as ET

screen = ET.parse(Path(__file__).resolve().parent / 'manifest.xml').getroot()
functions = {n.get('name'): n for n in screen.findall('Function')}
variables = {n.get('name'): n for n in screen.findall('Var')}
binders = {n.get('name'): n for n in screen.find('VariableBinders')}
events = {n.get('action'): n for n in screen.findall(".//Button[@name='gesture']/Triggers/Trigger")}
assert screen.get('screenWidth') == '1080' and screen.get('frameRate') == '60'
assert len(binders) == 17 and len(screen.findall('.//Image')) == 85
assert binders['apps'].get('limit') == '5'
assert binders['apps'].get('offsetExp') == 'max(0,#appIndex-2)'
assert set(events) == {'down', 'double', 'move', 'up', 'cancel'}
assert not screen.findall('.//AnimationCommand') and not screen.findall('.//VariableAnimation')
assert variables['motionTick'].get('expression') == '#rw_frame_time'
assert len([n for n in variables if n.startswith('mem') and n.endswith('Package')]) == 32
widget = screen.find('RearAppWidget')
picker = screen.find("Group[@name='picker']")
assert widget.get('userRequestExp') == '@rw_user_request'
assert widget.get('keyExp') == '@rw_instance' and widget.get('providerExp') == '@rw_provider'
assert all(widget.get(a) == picker.get(a) for a in ('x', 'y', 'w', 'h'))
assert {n.get('name') for n in screen.findall(".//VariableCommand[@persist='true']")} == {'rw_provider', 'rw_package', 'rw_selected'}
assert not any(n.get('name') in {'rw_provider', 'rw_package', 'rw_selected', 'rw_instance'} for n in screen.findall('Var'))
assert screen.find("Rectangle[@name='opaqueBackground']").get('visibility') is None
for r in range(5):
    row = picker.find(f".//Group[@name='row{r}']")
    assert row.get('clip') == 'false'
    hv = row.find('Group')
    assert hv.get('x') == '-#safeX' and hv.get('w') == '1080' and hv.get('clip') == 'true'
    for s in 'vln':
        b = binders[f'r{r}{s}']
        assert b.get('queryAtStart') == 'false' and b.get('priorityExp') == f'#r{r}{s}Priority'
        assert b.get('previewWidthExp') == b.get('previewHeightExp') == '#previewSize'
for n in screen.findall('.//FunctionCommand'):
    assert n.get('target') in functions
for n in screen.findall('.//BinderCommand'):
    assert n.get('name') in binders and n.get('command') == 'refresh'

REF = re.compile(r'([#@])([A-Za-z_][A-Za-z_0-9.]*)')
def translate(s):
    parts = re.split(r"('(?:[^'\\]|\\.)*')", s)
    for i in range(0, len(parts), 2):
        parts[i] = REF.sub(lambda m: f"{'getN' if m[1] == '#' else 'getS'}('{m[2]}')", parts[i])
        parts[i] = re.sub(r'\bnot\(', 'not_(', parts[i])
    return ''.join(parts)

class NativeExpression:
    """Propagate native evaluate/evaluateStr context, never Python mixed-type '+'."""
    def __init__(self, strict=False): self.strict = strict
    def call(self, name, args):
        return ast.Call(func=ast.Name(id=name, ctx=ast.Load()), args=args, keywords=[])
    def numeric_only(self, n):
        if isinstance(n, ast.Constant): return not isinstance(n.value, str)
        if isinstance(n, ast.Call): return n.func.id == 'getN'
        if isinstance(n, ast.Subscript): return n.value.func.id == 'getN'
        if isinstance(n, ast.BinOp): return self.numeric_only(n.left) and self.numeric_only(n.right)
        if isinstance(n, ast.UnaryOp): return self.numeric_only(n.operand)
        return False
    def convert(self, n, text=False):
        if isinstance(n, ast.BinOp):
            if text:
                if self.strict:
                    assert isinstance(n.op, ast.Add), 'native evaluateStr invalid binary operator: ' + ast.unparse(n)
                    assert not self.numeric_only(n), 'numeric ADD would concatenate digits: ' + ast.unparse(n)
                left, right = self.convert(n.left, True), self.convert(n.right, True)
                if not isinstance(n.op, ast.Add):
                    return self.call('invalid_string_binary', [left, right])
                return self.call('concat_', [left, right])
            # Java remainder is sign-of-dividend; Python % differs for negative rows.
            if isinstance(n.op, ast.Mod):
                return self.call('mod_', [self.convert(n.left), self.convert(n.right)])
            return ast.BinOp(left=self.convert(n.left), op=n.op, right=self.convert(n.right))
        if isinstance(n, ast.UnaryOp):
            value = ast.UnaryOp(op=n.op, operand=self.convert(n.operand))
            return self.call('string', [value]) if text else value
        if isinstance(n, ast.Subscript):
            # Native array index is always numeric, even when the selected item is a string.
            value = self.call('array_item', [n.value, self.convert(n.slice)])
            return self.call('string' if text else 'number', [value])
        if isinstance(n, ast.Call):
            name = n.func.id
            if name in {'getN', 'getS'}:
                return self.call('string' if text else 'number', [n])
            if name == 'eval':
                if self.strict and isinstance(n.args[0], ast.Constant) and isinstance(n.args[0].value, str):
                    # A literal nested expression is statically knowable, including evaluateStr(#foo+1).
                    nested = ast.parse(translate(n.args[0].value), mode='eval').body
                    self.convert(nested, text)
                return self.call('eval_', [self.convert(n.args[0], True), ast.Constant(text)])
            if name == 'ifelse':
                return ast.IfExp(test=self.convert(n.args[0]), body=self.convert(n.args[1], text),
                                 orelse=self.convert(n.args[2], text))
            args = [self.convert(a, name == 'eqs') for a in n.args]
            value = self.call(name, args)
            return self.call('string', [value]) if text else value
        if isinstance(n, ast.Constant):
            return self.call('string' if text else 'number', [n])
        raise AssertionError('unsupported native expression: ' + ast.dump(n))

def string(v):
    if v is None: return None
    if isinstance(v, (int, float)) and v == int(v): return str(int(v))
    return str(v)

def number(v):
    if v is None: return 0
    if isinstance(v, (int, float)): return v
    try: return float(v)
    except (ValueError, TypeError): return 0

def concat(a, b):
    if a is None: return b
    if b is None: return a
    return a+b

from functools import lru_cache

@lru_cache(maxsize=4096)
def compiled(s, text=False, strict=False):
    tree = ast.parse(translate(s), mode='eval')
    tree.body = NativeExpression(strict).convert(tree.body, text)
    return compile(ast.fix_missing_locations(tree), '<maml>', 'eval')

def text_attribute(node, key):
    return key in {'nameExp', 'textExp', 'srcExp', 'packageExp', 'keyExp', 'providerExp', 'userRequestExp'} or (
        key == 'expression' and node.get('type') in {'string', 'object'})

ATTRS = {'expression', 'nameExp', 'ifCondition', 'condition', 'loopCondition', 'visibility', 'textExp', 'srcExp',
         'packageExp', 'offsetExp', 'limitExp', 'priorityExp', 'previewWidthExp', 'previewHeightExp', 'keyExp', 'providerExp', 'userRequestExp',
         'x', 'y', 'w', 'h', 'size', 'alpha', 'weight', 'value', 'count'}
assert not screen.findall('.//ElementArray')  # Native factory recognizes Array, not ElementArray.
for n in screen.iter():
    for k, v in n.attrib.items():
        if k in ATTRS: compiled(v, text_attribute(n, k), strict=True)
for n in screen.findall('.//LoopCommand'):
    assert n.get('count') and n.get('indexName') and not n.get('begin') and not n.get('end')
for n in screen.findall('.//VariableCommand'):
    assert n.get('type', 'number') in {'number', 'string', 'object'}
    if n.get('type') == 'object':
        assert not n.get('index') and not n.get('values')  # Whole object-domain reference, not array allocation.

class Model:
    def __init__(self, app=10, count=45, widgets=30, left=296, width=1080):
        self.v = {'rw_ready': 1, 'rw_selected': 0, 'rw_instance': 'card-A',
                  'rw_provider': '', 'touch_x': 650, 'touch_y': 300,
                  'display.count': 1, 'display.loading': 0, 'display.error': '',
                  'display.width': width, 'display.height': 700, 'display.safeLeft': left,
                  'display.safeRight': 0, 'display.safeTop': 0, 'display.safeBottom': 0,
                  'rw_touch_time': 1000, 'rw_frame_time': 1000}
        self.queries, self.saved = [], []
        self.count, self.widget_count = count, widgets
        self.time = 1000
        for n, node in variables.items():
            self.v[n] = self.eval(node.get('expression'), text_attribute(node, 'expression')) if node.get('expression') else (None if node.get('type') in {'string', 'object'} else 0)
        self.v['appIndex'] = app
        self.run('initializePicker')
        self.publish_apps()
        self.publish_all()
    def eval(self, s, text=False):
        env = {'getN': lambda n: self.v.get(n, 0), 'getS': lambda n: self.v.get(n),
               'abs': abs, 'min': min, 'max': max, 'pow': pow, 'sqrt': math.sqrt, 'floor': math.floor, 'ceil': math.ceil,
               'round': lambda v: math.floor(v+.5),
               'eq': lambda a,b: int(a==b), 'ne': lambda a,b: int(a!=b),
               'gt': lambda a,b: int(a>b), 'ge': lambda a,b: int(a>=b),
               'lt': lambda a,b: int(a<b), 'le': lambda a,b: int(a<=b),
               'eqs': lambda a,b: int(a==b), 'not_': lambda a: int(not a),
               'eval_': self.eval, 'string': string, 'number': number,
               'concat_': concat, 'mod_': lambda a,b: math.fmod(a,b) if b else 0,
               'array_item': lambda a,i: a[int(i)] if isinstance(a, list) and 0 <= int(i) < len(a) else None,
               'invalid_string_binary': lambda a,b: None}
        return eval(compiled(s, text), {'__builtins__': {}}, env)
    def commands(self, p):
        for n in p:
            if n.get('condition') and not self.eval(n.get('condition')): continue
            if n.tag == 'VariableCommand':
                name = self.eval(n.get('nameExp'), True) if n.get('nameExp') else n.get('name')
                if not name: continue  # Native empty dynamic name performs no assignment.
                val = self.eval(n.get('expression'), text_attribute(n, 'expression'))
                if n.get('type') == 'object': val = self.v.get(val)
                self.v[name] = val
                if n.get('persist') == 'true': self.saved.append((name, val))
            elif n.tag == 'FunctionCommand': self.run(n.get('target'))
            elif n.tag == 'IfCommand':
                child = n.find('Consequent' if self.eval(n.get('ifCondition')) else 'Alternate')
                if child is not None: self.commands(child)
            elif n.tag == 'LoopCommand':
                for i in range(int(self.eval(n.get('count')))):
                    if n.get('loopCondition') and not self.eval(n.get('loopCondition')): break
                    self.v[n.get('indexName')] = i
                    self.commands(n)
            elif n.tag == 'BinderCommand':
                b = binders[n.get('name')];name = b.get('name')
                q = (name, self.eval(b.get('packageExp', "''"), True), int(self.eval(b.get('offsetExp', '0'))),
                     int(self.eval(b.get('limitExp', b.get('limit', '0')))), int(self.eval(b.get('priorityExp','0'))))
                assert q[3] <= 32
                self.queries.append(q);self.v[name+'.loading'] = 1
            else: raise AssertionError(n.tag)
    def run(self, name): self.commands(functions[name])
    def event(self, action): self.commands(events[action])
    def publish_apps(self, package_override=None, offset=None):
        offset = max(0, self.v['appIndex']-2) if offset is None else offset
        count = min(5, max(0,self.count-offset))
        self.v.update({'apps.loading':0, 'apps.error':'', 'apps.total':self.count, 'apps.count':count,
                       'apps.offset':offset, 'apps.packageNames':[package_override or f'pkg.{i}' for i in range(offset,offset+count)],
                       'apps.appNames':[f'App {i}' for i in range(offset,offset+count)], 'apps.widgetCounts':[self.widget_count]*count})
        self.run('acceptApps')
    def publish(self, name, package=None, offset=None, error='', loading=0, count=None, provider=None):
        package = package or self.v.get(name+'QPackage','')
        offset = self.v.get(name+'QOffset',0) if offset is None else offset
        count = self.v.get(name+'QLimit',0) if count is None else count
        self.v.update({name+'.loading':loading, name+'.error':error, name+'.offset':offset, name+'.count':count,
                       name+'.packageNames':[package]*count, name+'.providers':[provider or f'{package}/Provider{i}' for i in range(offset,offset+count)],
                       name+'.widgetNames':[f'Widget {i}' for i in range(offset,offset+count)],
                       name+'.previews':[f'bitmap:{package}:{i}' for i in range(offset,offset+count)]})
        self.run('accept'+name)
    def publish_all(self):
        for r in range(5):
            for s in 'vln':
                n=f'r{r}{s}'
                if self.v.get(n+'QLimit',0)>0 and self.v.get(n+'QPackage'): self.publish(n)
    def point(self, x, y, dt=16):
        self.time += dt
        self.v.update({'touch_x':x,'touch_y':y,'rw_touch_time':self.time,'rw_frame_time':self.time})
    def down(self, double=False):
        self.point(self.v['anchor'], self.v['safeY']+self.v['centerTop']+self.v['rowH']/2)
        self.event('down')
        if double:self.event('double')
    def move(self, x, y, dt=16):
        self.point(x,y,dt);self.event('move')
    def tick(self, dt=16):
        self.time += dt;self.v['rw_frame_time']=self.time
        # Native Var.update runs expression-backed Vars each frame; mutable Vars must have none.
        for name,node in variables.items():
            if node.get('expression') is not None and node.get('const') != 'true':
                self.v[name]=self.eval(node.get('expression'), text_attribute(node, 'expression'))
                trigger=node.find('Trigger')
                if trigger is not None and (not trigger.get('condition') or self.eval(trigger.get('condition'))):
                    self.commands(trigger)
    def up(self):
        self.time+=16;self.v['rw_touch_time']=self.time;self.event('up')
    def finish(self):
        for _ in range(20):self.tick();self.publish_apps();self.publish_all()
        assert not self.v['settling']

# Reproduce the actual native failure, rather than accepting Python's numeric subexpression coercion.
abi = Model(app=3)
abi.v.update(step=3, foo=3)
assert abi.eval("'r'+((#step%5+5)%5)", True) == 'r'  # invalid MOD => null, ADD preserves non-null prefix.
assert abi.eval("#step%5", True) is None
assert abi.eval("'mem'+(#step%32)+'Index'", True) == 'memIndex'
assert abi.eval("'r'+(#foo+1)", True) == 'r31'  # ADD is valid, but never numeric in string context.
assert abi.eval("eval('#foo+1')", True) == '31'
assert abi.eval("#foo+1") == 4
assert abi.eval("'r'+#centerSlot", True) == 'r3'
assert abi.eval("@apps.packageNames[#step-#apps.offset]", True) == 'pkg.3'
for expr, text in [("'r'+((#step%5+5)%5)", True), ("'mem'+(#step%32)+'Index'", True),
                   ("'r'+(#foo+1)", True), ("eval('#foo+1')", True),
                   ("eval('#mem'+(#step%32)+'Index')", False)]:
    try: compiled(expr, text, strict=True)
    except AssertionError: pass
    else: raise AssertionError('ABI guard accepted illegal numeric-string expression: ' + expr)
# Whole object assignment must retain the exact published array, not stringify or recreate it.
for r in range(5):
    for part, source in [('Bitmap', 'previews'), ('Providers', 'providers'), ('Names', 'widgetNames')]:
        n = f'r{r}v'
        if abi.v[n+'Ready']: assert abi.v[n+part] is abi.v[n+'.'+source]
# First app still maps negative neighbour candidates into valid ring names under Java remainder.
first = Model(app=0)
assert all(first.v[f'r{i}Valid'] == int(i < 3) for i in range(5))
assert not any(name in first.v for name in ('rValid', 'rPackage', 'memIndex'))
for app in (0, 31, 32, 44):
    memory = Model(app=app)
    row = app % 5
    memory.v[f'r{row}WidgetIndex'] = 12
    memory.run('saveHorizontal')
    assert memory.v[f'mem{app%32}Package'] == f'pkg.{app}'
    assert memory.v[f'mem{app%32}Index'] == 12
    memory.v[f'r{row}Package'] = 'recycled.identity'
    memory.publish_apps()
    assert memory.v['currentPackage'] == f'pkg.{app}' and memory.v['currentWidgetIndex'] == 12
# Page number text must display numeric increment, not append digit '1'.
abi.tick()
for r in range(5):
    abi.v[f'r{r}WidgetIndex'] = 12
abi.tick()
for r in range(5):
    labels = picker.findall(f".//Group[@name='row{r}']/Text[@textExp]")
    assert abi.eval(labels[-1].get('textExp'), True).startswith('13 / ')
print('PASS: native evaluateStr/null propagation, numeric ADD trap/static rejection, object-array identity, negative rows and page labels.')

# All catalogue positions, including indices beyond a Source's 32-item page ceiling.
for app in range(45):
    page=Model(app=app)
    assert page.v['currentPackage']==f'pkg.{app}'
    assert page.v['currentWidgetIndex']==0 and page.v['currentReady']
    assert sum(page.v[f'r{r}Valid'] for r in range(5))==min(44,app+2)-max(0,app-2)+1
empty=Model(app=0,count=0)
assert not empty.v['appsReady'] and not empty.v['currentReady']
# Shrink/delete callbacks belong to the pre-clamp offset. Do not rebuild/sync/query preview slots from that page.
for app,total in [(42,7),(44,44),(42,0)]:
    shrink=Model(app=app)
    old_offset=max(0,app-2)
    slots={name:value for name,value in shrink.v.items() if re.fullmatch(r'r[0-4](AppIndex|Package|WidgetIndex|Valid)',name)}
    old_center=shrink.v['centerSlot'];before=len(shrink.queries)
    shrink.count=total;shrink.publish_apps(offset=old_offset)
    clamped=max(0,total-1);new_offset=max(0,clamped-2)
    assert shrink.v['appIndex']==clamped
    assert shrink.queries[before:]==[('apps','',new_offset,5,0)]
    assert shrink.v['centerSlot']==old_center and all(shrink.v[n]==v for n,v in slots.items())
    assert not shrink.v['appsReady'] and not shrink.v['currentReady']
    before=len(shrink.queries);shrink.publish_apps(offset=old_offset)
    assert len(shrink.queries)==before  # Late old-page callback neither loops nor reconstructs rows.
    shrink.publish_apps();shrink.publish_all()
    assert shrink.v['centerSlot']==clamped%5
    if total:
        assert shrink.v['currentPackage']==f'pkg.{clamped}' and shrink.v['currentReady']
    else:
        assert not shrink.v['currentReady'] and not any(shrink.v[f'r{r}Valid'] for r in range(5))
# Deleting the current app in a still-valid page replaces identity and drops its cached index/bitmap.
deleted=Model(app=10)
deleted.v['r0WidgetIndex']=3;deleted.run('saveHorizontal')
deleted.count=44;deleted.publish_apps()
deleted.v['apps.packageNames']=['pkg.8','pkg.9','pkg.11','pkg.12','pkg.13']
deleted.run('acceptApps')
assert deleted.v['currentPackage']=='pkg.11' and deleted.v['currentWidgetIndex']==0
assert not deleted.v['currentReady'] and deleted.v['r0vBitmap'] is None
deleted.publish_all();assert deleted.v['currentReady'] and deleted.v['currentProvider']=='pkg.11/Provider0'
# Publication covers every intersecting horizontal cell of every visible row, including camera side.
for width,left in [(1080,296),(1080,1000),(600,100),(1080,0)]:
    m=Model(width=width,left=left)
    for delta in [-.99,-.4,0,.4,.99]:
        m.v.update(direction=1, dx=delta*m.v['stride']);m.run('queryWindows')
        for r in range(5):
            p=f'r{r}';n=p+'v';off=m.v[n+'Offset'];lim=m.v[n+'Limit']
            assert lim<=15
            for j in range(m.widget_count):
                x=m.v['anchor']+(j-m.v[p+'WidgetIndex'])*m.v['stride']+(m.v['dx'] if m.v[p+'AppIndex']==m.v['appIndex'] else 0)
                if x+m.v['previewBoxW']/2>0 and x-m.v['previewBoxW']/2<1080:
                    assert off<=j<off+lim, (width,left,delta,r,j,off,lim)
# Evaluate actual XML image visibility and the complete group coordinate chain, not
# a second hand-written track formula. Visibility here is pre-viewport clipping.
parents = {child: parent for parent in screen.iter() for child in parent}
preview_nodes = {r: picker.findall(f".//Group[@name='widgetTrack{r}']/Image") for r in range(5)}

def xml_previews(model, row):
    result = {}
    for image in preview_nodes[row]:
        chain = [image]
        while chain[-1] in parents:
            chain.append(parents[chain[-1]])
        if any(n.get('visibility') and not model.eval(n.get('visibility')) for n in chain):
            continue
        match = re.fullmatch(r'(r[0-4])([vln])Preview(\d+)', image.get('name'))
        prefix, source, index = match.groups();index = int(index)
        column = int(model.v[prefix+source+'CacheOffset'])+index
        assert column not in result, ('duplicate absolute widget column', row, column)
        assert model.eval(image.get('srcExp'), True) == f'{prefix}{source}Bitmap[{index}]'
        result[column] = (source, sum(model.eval(n.get('x', '0')) for n in chain),
                          sum(model.eval(n.get('y', '0')) for n in chain))
    return result

def assert_cache_owners(model):
    for r in range(5):
        prefix = f'r{r}';expected = {}
        for source in 'vln':  # Main cache wins even while its replacement is loading.
            name = prefix+source
            if model.v[name+'Ready'] and model.v[name+'CachePackage'] == model.v[prefix+'Package']:
                capacity = 15 if source == 'v' else 1
                for i in range(min(capacity, int(model.v[name+'CacheCount']))):
                    expected.setdefault(int(model.v[name+'CacheOffset'])+i, source)
        actual = xml_previews(model, r)
        row_node = picker.find(f".//Group[@name='row{r}']")
        ancestor = row_node
        while ancestor is not None:
            if ancestor.get('visibility') and not model.eval(ancestor.get('visibility')):
                expected = {};break
            ancestor = parents.get(ancestor)
        assert {col: item[0] for col,item in actual.items()} == expected, (r, actual, expected)
        for col, (_, x, y) in actual.items():
            dx = model.v['dx'] if model.v['direction'] == 1 and model.v[prefix+'AppIndex'] == model.v['appIndex'] else 0
            dy = model.v['dy'] if model.v['direction'] == 2 else 0
            assert abs(x-(model.v['anchor']+(col-model.v[prefix+'WidgetIndex'])*model.v['stride']+dx)) < 1e-6
            assert abs(y-(model.v['safeY']+model.v['centerTop']+(model.v[prefix+'AppIndex']-model.v['appIndex'])*model.v['rowH']+model.v['previewCenterY']+dy)) < 1e-6

# A single MOVE commits stride crossings before a Var tick; reverse without UP.
for app in range(10,15):  # Exercise all five independently authored XML rows.
    m=Model(app=app, widgets=60);r=app%5
    m.v[f'r{r}WidgetIndex']=12;m.run('syncCenter');m.run('queryWindows');m.publish_all()
    m.down();x,y=m.v['touch_x'],m.v['touch_y'];stride=m.v['stride']
    initial=xml_previews(m,r)
    for delta in (-.99,-1.01,-2.2,-.4,.4,1.01,1.65):
        m.move(x+delta*stride,y,dt=400)
        assert_cache_owners(m)
        current=xml_previews(m,r)
        for col in initial.keys() & current.keys():
            assert abs(current[col][1]-initial[col][1]-delta*stride)<1e-6
    m.move(x+1.65*stride,y,dt=400)  # Dwell removes reversal velocity before release.
    index_before_settle=m.v[f'r{r}WidgetIndex']
    m.up()
    stale_samples=0
    for _ in range(20):
        m.tick(16)
        stale_samples += abs(m.v['offsetX']-m.v['dx']*(m.v['direction']==1))>1e-6
        assert_cache_owners(m)
    assert stale_samples>0 and not m.v['settling']  # Old sampled track would fail.
    assert m.v[f'r{r}WidgetIndex']==index_before_settle-1  # motionTick also crosses a stride.
# The same ordering hazard exists for vertical motionTick displacement.
m=Model();m.down();x,y=m.v['touch_x'],m.v['touch_y'];m.move(x,y-.65*m.v['rowH'],dt=400);m.up()
stale_samples=0
for _ in range(20):
    m.tick(16);stale_samples += abs(m.v['offsetY']-m.v['dy']*(m.v['direction']==2))>1e-6
    assert_cache_owners(m)
assert stale_samples>0 and not m.v['settling']

# Every callback ordering: check after query, after each staggered publication,
# and while reversing. This explicitly retains old transition cache coverage.
from itertools import permutations
for order in permutations('vln'):
    m=Model(widgets=60);m.v['r0WidgetIndex']=12;m.run('syncCenter');m.run('queryWindows');m.publish_all()
    m.down();x,y=m.v['touch_x'],m.v['touch_y']
    for delta in (-1.2,-2.2,-.2,.2):
        m.move(x+delta*m.v['stride'],y,dt=400);assert_cache_owners(m)
        for source in order:
            m.publish('r0'+source);assert_cache_owners(m)
# limit=0 does not erase an old neighbour. New v must win that absolute column.
m=Model();m.v['r0WidgetIndex']=3;m.run('syncCenter');m.run('queryWindows');m.publish_all()
assert m.v['r0lCacheCount']==1
old_left=m.v['r0lCacheOffset']
m.v['r0WidgetIndex']=0;m.run('syncCenter');m.run('queryWindows')
assert m.v['r0lLimit']==0 and m.v['r0lCacheOffset']==old_left
m.publish('r0l');assert m.v['r0lCacheCount']==1
m.publish('r0v');assert_cache_owners(m)
assert xml_previews(m,0)[old_left][0]=='v'  # Not just a non-overlapping limit=0 case.
# Exhaustive overlap/identity/readiness cases for all authored rows, including
# same-column l/n fallback when v is empty or belongs to a recycled package.
m=Model()
for r in range(5):
    prefix=f'r{r}'
    for v_ready in (0,1):
        for v_package in (m.v[prefix+'Package'], 'stale.package'):
            for v_count in (0,1,4,15):
                for left,right in ((4,4),(5,5),(4,5),(6,7),(19,19),(20,21)):
                    for source,off,count in (('v',5,v_count),('l',left,1),('n',right,1)):
                        name=prefix+source
                        m.v.update({name+'CacheOffset':off,name+'CacheCount':count,
                                    name+'Ready':v_ready if source=='v' else 1,
                                    name+'CachePackage':v_package if source=='v' else m.v[prefix+'Package']})
                    assert_cache_owners(m)
print('PASS: actual XML coordinates across stride/reversal and motionTick; v > l > n absolute-column ownership, asynchronous callback permutations and limit=0 retained neighbours.')

# Fourth row actually exposed by drag is upgraded from neighbour to visible immediately.
m=Model();outer='r'+str((m.v['appIndex']+2)%5)+'v'
assert m.v[outer+'Priority']==1
m.down();m.move(m.v['touch_x'],m.v['touch_y']-.5*m.v['rowH'])
assert m.v[outer+'Priority']==0 and any(q[0]==outer and q[4]==0 for q in m.queries)
# Three-row single MOVE commits all crossings now; no cold-cache lock, page queries at each outer edge.
m=Model();m.down();x,y=m.v['touch_x'],m.v['touch_y'];q=len(m.queries)
m.move(x,y-3.2*m.v['rowH']);assert m.v['appIndex']==13 and abs(m.v['dy']+.2*m.v['rowH'])<1e-6
assert len([n for n in m.queries[q:] if n[0]=='apps'])==3
before=m.v['appIndex'];m.publish_apps(offset=8);assert m.v['appIndex']==before  # stale directory rejected
m.publish_apps();m.publish_all();m.move(x,y-1.2*m.v['rowH']);assert m.v['appIndex']==12  # reverse without UP
m.event('cancel');m.finish();assert m.v['appIndex']==12
# Horizontal multistep promotion and per-app ring memory.
m=Model();m.down();x,y=m.v['touch_x'],m.v['touch_y'];m.move(x-3.2*m.v['stride'],y,dt=400)
assert m.v['currentWidgetIndex']==3 and abs(m.v['dx']+.2*m.v['stride'])<1e-6
m.event('cancel');m.finish();m.down();x,y=m.v['touch_x'],m.v['touch_y'];m.move(x,y-1.05*m.v['rowH'],dt=400);m.publish_apps();m.publish_all()
assert m.v['currentWidgetIndex']==0
m.event('cancel');m.finish();m.down();x,y=m.v['touch_x'],m.v['touch_y'];m.move(x,y+1.05*m.v['rowH'],dt=400);m.publish_apps();m.publish_all()
assert m.v['currentWidgetIndex']==3
# Same-identity callbacks are accepted in motion, stale package/offset/error/empty are not.
m=Model();n='r0v';m.down();m.move(m.v['touch_x']-20,m.v['touch_y'])
m.publish(n);old=m.v[n+'Bitmap'];assert old
for kwargs in [{'package':'bad.pkg'},{'offset':99},{'error':'cancelled'},{'loading':1},{'count':0}]:
    m.publish(n,**kwargs);assert m.v[n+'Bitmap'] is old
# DOWN interrupts nonzero settling at its current interpolated point and clears double-tap eligibility.
m=Model();m.down();x,y=m.v['touch_x'],m.v['touch_y'];m.move(x-100,y);m.up();m.tick(50)
assert m.v['settling']
# DOWN integrates through its own event timestamp before taking ownership.
import copy
expected=copy.deepcopy(m);expected.tick(16);old=expected.v['dx'];idx=expected.v['currentWidgetIndex'];m.down(double=True)
assert not m.v['settling'] and m.v['dragging'] and not m.v['doubleArmed'] and abs(m.v['dx']-old)<1e-6
m.tick(500);assert m.v['currentWidgetIndex']==idx  # no old end callback
m.event('cancel');m.finish()
# Legal double commits selected first, then nonpersistent, identity-bound request exactly once.
m=Model();m.down();m.up();m.down(double=True);assert not m.saved;m.up()
assert m.saved==[('rw_provider','pkg.10/Provider0'),('rw_package','pkg.10'),('rw_selected',1)]
assert m.v['rw_user_request']=='card-A|pkg.10/Provider0|1'
m.tick();assert m.v['rw_user_request']=='card-A|pkg.10/Provider0|1'  # const Var cannot erase it at next tick.
m.run('confirmSelection');assert len(m.saved)==3 and m.v['requestSequence']==1
for name,node in variables.items():
    if node.get('expression') is not None:m.v[name]=m.eval(node.get('expression'), text_attribute(node, 'expression'))
assert not m.v['rw_user_request'] and m.v['rw_selected']==1 and m.v['rw_provider']=='pkg.10/Provider0'
m.commands(screen.find("ExternalCommands/Trigger[@action='init']"))
assert not m.v['rw_user_request'] and m.v['rw_instance']=='card-A'
for action in ['cancel','drag','mismatch','error']:
    m=Model();m.down();m.up();m.down(double=True)
    if action=='cancel':m.event('cancel')
    elif action=='drag':m.move(m.v['touch_x']+40,m.v['touch_y']);m.up()
    elif action=='mismatch':m.publish('r0v',provider='pkg.10/Other');m.up()
    else:m.publish('r0v',error='cancelled');m.up()
    assert not m.saved and not m.v['rw_user_request']
m=Model();m.v.update(rw_selected=1,rw_provider='restored/Provider',rw_instance='restored-key',rw_user_request='stale')
m.run('initializePicker');assert m.v['rw_selected']==1 and m.v['rw_provider']=='restored/Provider' and m.v['rw_instance']=='restored-key' and not m.v['rw_user_request']
# 100 traversals keep five rows, bounded state, no image from another package and no indices inherited.
m=Model(widgets=500)
for i in range(100):
    m.down();x,y=m.v['touch_x'],m.v['touch_y'];m.move(x,y+(-1 if i%2==0 else 1)*1.1*m.v['rowH'],dt=400)
    m.publish_apps();m.publish_all();m.event('cancel');m.finish()
    assert len([n for n in m.v if re.fullmatch(r'mem\d+Package',n)])==32
    for r in range(5):
        for s in 'vln':
            n=f'r{r}{s}'
            if m.v[n+'Ready']:assert m.v[n+'CachePackage']==m.v[f'r{r}Package']
# Conservative three distinct bounded owners: published Binder snapshots, grouped theme cache,
# and native Image references during exchange. Shared duplicates count once in production.
for left in range(0,1025,16):
    budget=Model(left=left)
    size=budget.v['previewSize'];slots=5*budget.v['columnBudget']
    assert 0<size<=256 and slots*size*size*4*3 <= 8*1024*1024
assert Model().v['previewSize'] >= 152
assert variables['rw_user_request'].get('expression') == "''"
assert variables['rw_user_request'].get('const') == 'true'
assert len(variables['bootstrap'].findall('Trigger')) == 1
assert screen.find("ExternalCommands/Trigger[@action='init']/FunctionCommand").get('target') == 'initializePicker'
assert not m.v['rw_user_request']
print('PASS: catalogue shrink/deletion requery, stale post-clamp page rejection, same-page identity replacement.')
print('PASS: actual XML model: physical viewport/all visible horizontal cells, fourth-row priority promotion,')
print('multi-crossing MOVE/rebase/reversal, in-motion grouped caches, stale/error cancellation, independent indices,')
print('interruptible uptime settling/no old end, legal double-UP request-after-persist, restore/no request, 100 bounded traversals.')
print('Bounds: 5 ring rows, <=15 visible +2 neighbour columns/row, 32 app index memories, <=32 hops/event, <=2 inertia strides.')
print('Not tested: native/device frame pacing, actual bitmap quality, touch bridge/lifecycle and hardware rendering.')
