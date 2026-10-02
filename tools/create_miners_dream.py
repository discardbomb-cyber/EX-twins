"""Create a fresh world using Minecraft 1.21.1's tunnelers_dream preset."""
import gzip, io, struct, pathlib, time
stream = io.BytesIO()
def read(fmt):
    return struct.unpack('>' + fmt, stream.read(struct.calcsize('>' + fmt)))[0]
def string():
    return stream.read(read('H')).decode('utf-8')
def payload(t):
    if t in range(1, 7): return read({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[t])
    if t == 7: return stream.read(read('i'))
    if t == 8: return string()
    if t == 9:
        child, count = read('B'), read('i')
        return child, [payload(child) for _ in range(count)]
    if t == 10:
        result = {}
        while (child := read('B')):
            name = string()
            result[name] = child, payload(child)
        return result
    if t in (11, 12): return [read('i' if t == 11 else 'q') for _ in range(read('i'))]
    raise ValueError(t)
def pack(fmt, v): return struct.pack('>' + fmt, v)
def text(v):
    raw = v.encode('utf-8')
    return pack('H', len(raw)) + raw
def encode(t, v):
    if t in range(1, 7): return pack({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[t], v)
    if t == 7: return pack('i', len(v)) + v
    if t == 8: return text(v)
    if t == 9: return pack('B',v[0])+pack('i',len(v[1]))+b''.join(encode(v[0], x) for x in v[1])
    if t == 10: return b''.join(pack('B',k)+text(n)+encode(k,x) for n,(k,x) in v.items())+b'\0'
    if t in (11,12): return pack('i',len(v))+b''.join(pack('i' if t == 11 else 'q',x) for x in v)
source = pathlib.Path('run-scenario/saves/Scenario/level.dat')
stream = io.BytesIO(gzip.decompress(source.read_bytes()))
root_type, root_name = read('B'), string()
root = payload(root_type)
data = root['Data'][1]
data.pop('Player', None)
data.update(LevelName=(8,'Мечта шахтёра'), GameType=(3,1), allowCommands=(1,1), initialized=(1,0),
            SpawnX=(3,0), SpawnY=(3,173), SpawnZ=(3,0), Time=(4,0), DayTime=(4,1000), LastPlayed=(4,int(time.time()*1000)))
settings = data['WorldGenSettings'][1]
settings['seed'] = (4, 20261002)
settings['generate_features'] = (1,1)
settings['dimensions'][1]['minecraft:overworld'][1]['generator'] = (10, {
    'type': (8,'minecraft:flat'),
    'settings': (10, {'biome': (8,'minecraft:windswept_hills'), 'features':(1,1), 'lakes':(1,0),
        'structure_overrides':(9,(8,['minecraft:mineshafts','minecraft:strongholds'])),
        'layers':(9,(10,[{'height':(3,h),'block':(8,'minecraft:'+b)} for h,b in [(1,'bedrock'),(230,'stone'),(5,'dirt'),(1,'grass_block')]]))})})
destination = pathlib.Path('D:/ex-twins-captures/miners-dream-run/saves/MinersDream')
destination.mkdir(parents=True,exist_ok=True)
target = destination/'level.dat'
if target.exists(): raise FileExistsError('Preserving existing world: '+str(target))
target.write_bytes(gzip.compress(pack('B',root_type)+text(root_name)+encode(root_type,root)))
print('Created fresh MinersDream: tunnelers_dream, creative, surface Y=173')
