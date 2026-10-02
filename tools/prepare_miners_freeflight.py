"""Configure the offline test world's player for free creative flight."""
import pathlib, gzip, io, shutil
exec(pathlib.Path('tools/create_miners_dream.py').read_text().split('source = pathlib.Path')[0])
world = pathlib.Path('D:/ex-twins-captures/miners-dream-run/saves/MinersDream')
files = [world/'level.dat'] + list((world/'playerdata').glob('*.dat'))
for path in files:
    stream = io.BytesIO(gzip.decompress(path.read_bytes()))
    kind, name = read('B'), string()
    root = payload(kind)
    data = root['Data'][1] if path.name == 'level.dat' else root
    if path.name == 'level.dat':
        data['GameType'] = (3,1)
        data['allowCommands'] = (1,1)
        player = data.get('Player', (10,{}))[1]
    else: player = data
    if player:
        player['playerGameType'] = (3,1)
        abilities = player.setdefault('abilities',(10,{}))[1]
        abilities.update(mayfly=(1,1), flying=(1,1), instabuild=(1,1), invulnerable=(1,1))
        if 'Pos' in player:
            player['Pos'][1][1][1] = 603.0
        player['Rotation'] = (9,(5,[0.0,90.0]))
    backup = path.with_suffix('.pre-freeflight.dat')
    if not backup.exists(): shutil.copy2(path, backup)
    path.write_bytes(gzip.compress(pack('B',kind)+text(name)+encode(kind,root)))
options = world.parent.parent/'options.txt'
lines = options.read_text().splitlines()
settings = {'renderDistance':'32','hideGui':'false','simulationDistance':'12'}
options.write_text('\n'.join(key+':'+settings.get(key,value) for key,value in (line.split(':',1) for line in lines if ':' in line))+'\n')
print('PASS: creative free flight, pitch 90, Y603, renderDistance32; original player files backed up')
