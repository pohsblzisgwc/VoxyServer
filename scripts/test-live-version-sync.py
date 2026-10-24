#!/usr/bin/env python3
"""Minecraft 26.3 / protocol 6 loopback regression endpoint (Python standard library).

Run ONLY against an offline-mode disposable server copy with protocol 6 installed:
  python3 scripts/test-live-version-sync.py PORT SECONDS RADIUS NAME [--inject-failure]

This validates version tables, selected LOD0 payloads, BUSY, retries and duplicate ACKs.
ACK SUCCESS means in-memory protocol validation, NOT native installation or GPU rendering.
The endpoint joins as a player, so normal server chunk lifecycle events can occur.
It repeats up to three audits immediately to exercise dedup; real clients wait 90-150s.
Packet IDs below are pinned to Minecraft wire protocol 777; no external dependencies.
"""
import socket, struct, zlib, time, json, uuid, sys, select
MAP = {'play_serverbound': {'SERVERBOUND_ACCEPT_TELEPORTATION': 0, 'SERVERBOUND_CHUNK_BATCH_RECEIVED': 11, 'SERVERBOUND_CLIENT_INFORMATION': 14, 'SERVERBOUND_CUSTOM_PAYLOAD': 22, 'SERVERBOUND_KEEP_ALIVE': 28, 'SERVERBOUND_PLAYER_LOADED': 44}, 'play_clientbound': {'CLIENTBOUND_CHUNK_BATCH_FINISHED': 11, 'CLIENTBOUND_CUSTOM_PAYLOAD': 24, 'CLIENTBOUND_DISCONNECT': 32, 'CLIENTBOUND_KEEP_ALIVE': 45, 'CLIENTBOUND_PLAYER_POSITION': 73}, 'configuration_serverbound': {'SERVERBOUND_CLIENT_INFORMATION': 0, 'SERVERBOUND_CUSTOM_PAYLOAD': 2, 'SERVERBOUND_FINISH_CONFIGURATION': 3, 'SERVERBOUND_KEEP_ALIVE': 4, 'SERVERBOUND_SELECT_KNOWN_PACKS': 7}, 'configuration_clientbound': {'CLIENTBOUND_CUSTOM_PAYLOAD': 1, 'CLIENTBOUND_DISCONNECT': 2, 'CLIENTBOUND_FINISH_CONFIGURATION': 3, 'CLIENTBOUND_KEEP_ALIVE': 4, 'CLIENTBOUND_SELECT_KNOWN_PACKS': 15}, 'login_serverbound': {'SERVERBOUND_HELLO': 0, 'SERVERBOUND_CUSTOM_QUERY_ANSWER': 2, 'SERVERBOUND_LOGIN_ACKNOWLEDGED': 3}, 'login_clientbound': {'CLIENTBOUND_LOGIN_DISCONNECT': 0, 'CLIENTBOUND_HELLO': 1, 'CLIENTBOUND_LOGIN_FINISHED': 2, 'CLIENTBOUND_LOGIN_COMPRESSION': 3}}

def vi(n):
    n &= (1 << 64) - 1
    b = bytearray()
    while n > 127:
        b.append(n & 127 | 128)
        n >>= 7
    b.append(n)
    return bytes(b)

def st(s):
    b = s.encode()
    return vi(len(b)) + b

def lng(n):
    return struct.pack('>Q', n & (1 << 64) - 1)

class R:

    def __init__(self, b):
        self.b = b
        self.i = 0

    def n(self, l):
        v = self.b[self.i:self.i + l]
        self.i += l
        return v

    def v(self):
        v = 0
        for i in range(10):
            c = self.n(1)[0]
            v |= (c & 127) << 7 * i
            if c < 128:
                return v
        raise ValueError('bad varint')

    def s(self):
        return self.n(self.v()).decode()

    def l(self):
        return struct.unpack('>Q', self.n(8))[0]

class Bot:

    def __init__(self, port, duration=45):
        self.s = socket.create_connection(('127.0.0.1', port))
        self.s.settimeout(1)
        self.buf = b''
        self.compression = False
        self.state = 'login'
        self.epoch = 0
        self.groups = {}
        self.seq = 0
        self.duration = duration
        self.stats = {'batches': 0, 'nodes': 0, 'bytes': 0}
        self.start = time.monotonic()
        self.last_report = self.start

    def send(self, id, body=b''):
        b = vi(id) + body
        if self.compression:
            b = vi(len(b)) + zlib.compress(b) if len(b) >= 256 else b'\x00' + b
        self.s.sendall(vi(len(b)) + b)

    def packet(self, name, b=b''):
        self.send(MAP[self.state + '_serverbound']['SERVERBOUND_' + name], b)

    def custom(self, ch, b):
        self.packet('CUSTOM_PAYLOAD', st(ch) + b)

    def read(self):
        while True:
            r = R(self.buf)
            try:
                size = r.v()
            except IndexError:
                size = None
            if size is not None and len(self.buf) - r.i >= size:
                b = r.n(size)
                self.buf = self.buf[r.i:]
                if self.compression:
                    q = R(b)
                    expanded = q.v()
                    b = zlib.decompress(q.n(len(b) - q.i)) if expanded else q.n(len(b) - q.i)
                return R(b)
            try:
                b = self.s.recv(65536)
            except socket.timeout:
                return None
            if not b:
                raise EOFError('server closed')
            self.buf += b
MASK = (1 << 64) - 1

def key(l, x, y, z):
    return l << 60 | (y & 255) << 52 | (z & 16777215) << 28 | (x & 16777215) << 4

def coords(k):
    x = k >> 4 & 16777215
    z = k >> 28 & 16777215
    y = k >> 52 & 255
    return (x - (1 << 24) if x & 1 << 23 else x, y - 256 if y & 128 else y, z - (1 << 24) if z & 1 << 23 else z)

def digest(values):
    h = 14695981039346656037
    any = False
    for i, v in enumerate(values):
        if v > 0:
            any = True
            h = (h ^ i) * 1099511628211 & MASK
            h = (h ^ v) * 1099511628211 & MASK
    if not any:
        return 0
    h ^= h >> 33
    h = h * 18397679294719823053 & MASK
    h ^= h >> 33
    return h or 1

class Probe(Bot):

    def __init__(self, port, duration, radius, name):
        super().__init__(port, duration)
        self.radius = radius
        self.name = name
        self.pos = None
        self.last_session = 0
        self.roundid = 0
        self.local = {}
        self.active = False
        self.tables = {}
        self.roots = []
        self.scope = None
        self.selected = []
        self.page = 0
        self.busy_sent = False
        self.stats.update(completed=0, busy=0, failed=0, matched_rounds=0, table_pages=0, only_lod0=True, first_data_seconds=None, sessions=0, first_node_y=None)

    def register(self):
        channels = ['voxyserver:lod_protocol', 'voxyserver:lod_server_settings', 'voxyserver:version_session_v6', 'voxyserver:version_scope_v6', 'voxyserver:version_table_v6', 'voxyserver:version_status_v6', 'voxyserver:lod_preserialized']
        self.custom('minecraft:register', b'\x00'.join((x.encode() for x in channels)))

    def inside(self, k):
        x, y, z = coords(k)
        cx, cz = self.scope
        return x * 32 <= cx + self.radius * 16 and x * 32 + 32 > cx - self.radius * 16 and (z * 32 <= cz + self.radius * 16) and (z * 32 + 32 > cz - self.radius * 16) and (y * 32 < self.height[1]) and (y * 32 + 32 > self.height[0])

    def summaries(self, roundid=None):
        rid = self.roundid if roundid is None else roundid
        cx, cz = self.scope
        values = {}
        for k, v in self.local.items():
            if not self.inside(k):
                continue
            x, y, z = coords(k)
            root = key(4, x >> 4, y >> 4, z >> 4)
            slot = x & 15 | (z & 15) << 4 | (y & 15) << 8
            values.setdefault(root, [0] * 4096)[slot] = v
        ds = [digest(values.get(root, [])) for root in self.roots]
        pages = (len(ds) + 255) // 256
        for page in range(pages):
            chunk = ds[page * 256:(page + 1) * 256]
            b = st('minecraft:overworld') + vi(self.epoch) + vi(rid) + struct.pack('>ii', cx, cz) + vi(self.radius) + struct.pack('>iii', *self.height, self.focus_y) + vi(page) + vi(pages) + vi(len(chunk)) + b''.join((lng(h) for h in chunk))
            self.custom('voxyserver:version_summary_v6', b)

    def begin(self):
        self.roundid += 1
        self.active = True
        self.tables = {}
        self.custom('voxyserver:version_begin_v6', vi(self.epoch) + vi(self.roundid) + vi(self.radius))
        self.custom('voxyserver:version_begin_v6', vi(self.epoch) + vi(self.roundid + 100000) + vi(self.radius))
        self.busy_sent = True

    def delivery_order(self, k):
        x, y, z = coords(k)
        cx, cz = self.scope

        def d(f, s):
            return max(s - f, f - s - 32, 0)
        return (d(cx, x * 32) ** 2 + d(cz, z * 32) ** 2 + 4 * d(self.focus_y, y * 32) ** 2, k)

    def selection(self):
        pages = max(1, (len(self.selected) + 255) // 256)
        items = self.selected[self.page * 256:(self.page + 1) * 256]
        body = vi(self.epoch) + vi(self.roundid) + vi(self.page) + vi(pages) + vi(len(items)) + b''.join((lng(k) + struct.pack('>i', v) for k, v in items))
        self.custom('voxyserver:version_select_v6', body)

    def custom_in(self, r):
        ch = r.s()
        if ch == 'voxyserver:version_session_v6':
            dim = r.s()
            self.epoch = r.v()
            r.n(32)
            self.active = False
            self.last_session = time.monotonic()
            self.stats['sessions'] += 1
            print('session protocol6', dim, flush=True)
        elif ch == 'voxyserver:lod_server_settings':
            self.custom('voxyserver:lod_preferences', b'\x01' + vi(self.radius) + vi(100))
        elif ch == 'voxyserver:version_scope_v6':
            epoch = r.v()
            rid = r.v()
            dim = r.s()
            cx, cz = struct.unpack('>ii', r.n(8))
            radius = r.v()
            miny, maxy, focus_y = struct.unpack('>iii', r.n(12))
            if epoch != self.epoch or rid != self.roundid:
                return
            assert radius <= self.radius and dim == 'minecraft:overworld'
            self.radius = radius
            self.scope = (cx, cz)
            self.height = (miny, maxy)
            self.focus_y = focus_y
            self.roots = [key(4, x, y, z) for y in range(miny // 512, (maxy - 1) // 512 + 1) for z in range((cz - self.radius * 16) // 512, (cz + self.radius * 16) // 512 + 1) for x in range((cx - self.radius * 16) // 512, (cx + self.radius * 16) // 512 + 1)]
            self.summaries()
        elif ch == 'voxyserver:version_table_v6':
            epoch = r.v()
            rid = r.v()
            root = r.l()
            h = r.l()
            page = r.v()
            pages = r.v()
            n = r.v()
            items = [(r.l(), struct.unpack('>i', r.n(4))[0]) for _ in range(n)]
            if epoch != self.epoch or rid != self.roundid:
                return
            assert root in self.roots and all((v > 0 and k >> 60 == 0 and self.inside(k) for k, v in items))
            self.tables.setdefault(root, {'h': h, 'pages': pages, 'parts': {}})['parts'][page] = items
            self.stats['table_pages'] += 1
        elif ch == 'voxyserver:version_status_v6':
            epoch = r.v()
            rid = r.v()
            status = r.n(1)[0]
            page = r.v()
            count = r.v()
            if status == 3:
                self.stats['busy'] += 1
                return
            if epoch != self.epoch or rid != self.roundid:
                return
            if status == 0:
                assert len(self.tables) == count
                remote = {}
                for root, t in self.tables.items():
                    assert len(t['parts']) == t['pages']
                    values = [0] * 4096
                    for p in range(t['pages']):
                        for k, v in t['parts'][p]:
                            x, y, z = coords(k)
                            values[x & 15 | (z & 15) << 4 | (y & 15) << 8] = v
                            remote[k] = v
                    assert digest(values) == t['h'], 'version digest mismatch'
                self.selected = sorted(((k, v) for k, v in remote.items() if self.local.get(k, 0) != v), key=lambda kv: self.delivery_order(kv[0]))
                self.page = 0
                if not self.selected:
                    self.stats['matched_rounds'] += 1
                self.selection()
            elif status == 1:
                assert page == self.page
                self.page += 1
                self.selection()
            elif status == 2:
                self.stats['completed'] += 1
                self.active = False
            elif status in [4, 5]:
                self.stats['failed'] += 1
                self.active = False
                print('ROUND_FAILED', status, flush=True)
        elif ch == 'voxyserver:lod_preserialized':
            epoch = r.v()
            batch = r.v()
            reserved = r.l()
            rid = r.v()
            count = r.v()
            length = r.v()
            data = r.n(length)
            if epoch != self.epoch or rid != self.roundid:
                self.custom('voxyserver:lod_batch_result', vi(epoch) + vi(batch) + b'\x03')
                return
            q = R(data)
            n = q.v()
            assert n == count and 1 <= count <= 16
            decoded = []
            allowed = dict(self.selected[self.page * 256:(self.page + 1) * 256])
            for _ in range(n):
                k = q.l()
                version = struct.unpack('>i', q.n(4))[0]
                q.n(1)
                lut = q.v()
                assert 1 <= lut <= 32768 and k >> 60 == 0 and (k in allowed) and (version >= allowed[k])
                for i in range(lut):
                    assert q.v() >= 0 and q.v() >= 0
                    q.n(1)
                bits = q.n(1)[0]
                assert bits == max(1, (lut - 1).bit_length())
                per = 64 // bits
                mask = (1 << bits) - 1
                left = 32768
                while left:
                    packed = q.l()
                    for i in range(min(per, left)):
                        assert packed >> i * bits & mask < lut
                    left -= min(per, left)
                decoded.append((k, version))
            assert q.i == len(data)
            for k, v in decoded:
                self.local[k] = v
            self.stats['batches'] += 1
            self.stats['nodes'] += count
            self.stats['bytes'] += length
            if self.stats['first_data_seconds'] is None:
                self.stats['first_data_seconds'] = round(time.monotonic() - self.start, 3)
                self.stats['first_node_y'] = coords(decoded[0][0])[1] * 32
            if '--inject-failure' in sys.argv and (not hasattr(self, 'failed_once')):
                self.failed_once = batch
                self.custom('voxyserver:lod_batch_result', vi(epoch) + vi(batch) + b'\x01')
            else:
                if hasattr(self, 'failed_once'):
                    self.custom('voxyserver:lod_batch_result', vi(epoch) + vi(self.failed_once) + b'\x00')
                self.custom('voxyserver:lod_batch_result', vi(epoch) + vi(batch) + b'\x00')

    def update(self):
        if self.epoch and self.pos and (not self.active) and (time.monotonic() - self.last_session > 0.5) and (self.stats['completed'] < 3) and (not self.stats['failed']):
            self.begin()

    def run(self):
        self.send(0, vi(777) + st('127.0.0.1') + struct.pack('>H', self.s.getpeername()[1]) + vi(2))
        self.packet('HELLO', st(self.name) + uuid.uuid4().bytes)
        while time.monotonic() - self.start < self.duration:
            r = self.read()
            if r is not None:
                id = r.v()
                name = next((k for k, v in MAP[self.state + '_clientbound'].items() if v == id), '?')
                if 'DISCONNECT' in name:
                    raise RuntimeError('disconnect ' + repr(r.n(300)))
                if self.state == 'login':
                    if name.endswith('COMPRESSION'):
                        r.v()
                        self.compression = True
                    elif name.endswith('FINISHED'):
                        self.packet('LOGIN_ACKNOWLEDGED')
                        self.state = 'configuration'
                        self.register()
                    elif name.endswith('CUSTOM_QUERY'):
                        self.packet('CUSTOM_QUERY_ANSWER', vi(r.v()) + b'\x00')
                elif self.state == 'configuration':
                    if name.endswith('SELECT_KNOWN_PACKS'):
                        self.packet('SELECT_KNOWN_PACKS', b'\x00')
                    elif name.endswith('KEEP_ALIVE'):
                        self.packet('KEEP_ALIVE', r.n(8))
                    elif name.endswith('FINISH_CONFIGURATION'):
                        self.packet('FINISH_CONFIGURATION')
                        self.state = 'play'
                        self.register()
                        self.packet('CLIENT_INFORMATION', st('en_us') + b'\x02\x00\x01\x7f\x01\x00\x00\x00')
                        self.custom('voxyserver:lod_handshake', vi(6))
                        self.custom('voxyserver:lod_preferences', b'\x01' + vi(self.radius) + vi(100))
                elif name.endswith('CUSTOM_PAYLOAD'):
                    self.custom_in(r)
                elif name.endswith('KEEP_ALIVE'):
                    self.packet('KEEP_ALIVE', r.n(8))
                elif name.endswith('PLAYER_POSITION'):
                    tid = r.v()
                    pos = r.n(24)
                    self.pos = struct.unpack('>ddd', pos)
                    r.n(24)
                    angle = r.n(8)
                    self.packet('ACCEPT_TELEPORTATION', vi(tid) + pos + angle)
                    self.packet('PLAYER_LOADED')
                elif name.endswith('CHUNK_BATCH_FINISHED'):
                    self.packet('CHUNK_BATCH_RECEIVED', struct.pack('>f', 20))
            self.update()
            now = time.monotonic()
            if now - self.last_report >= 10:
                print(round(now - self.start, 1), self.stats, flush=True)
                self.last_report = now
        print('RESULT', json.dumps(self.stats), flush=True)
        self.s.close()
        assert self.stats['batches'] > 0, 'no LOD0 data received'
        assert self.stats['failed'] == 0, 'round failed'
        assert self.stats['sessions'] == 1, 'unexpected session replacement'
if __name__ == '__main__':
    if len(sys.argv) < 5:
        raise SystemExit(__doc__)
    assert 0 < int(sys.argv[1]) < 65536 and int(sys.argv[2]) > 0 and (1 <= int(sys.argv[3]) <= 4096) and (1 <= len(sys.argv[4]) <= 16)
    Probe(int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]).run()
