#!/usr/bin/env python3
"""传音（MicYou 兼容协议）本机回环测试工具：模拟 MicYou 电脑端（接收）或手机发送端。

用法：
  python3 tools/micyou_sim.py desktop [--port 8554] [--seconds 10] [--wav out.wav]
      模拟 MicYou 电脑端：监听 TCP 8554 / UDP 8555，握手、每 500 ms ping、每秒下发 stats，统计收到的音频包。
  python3 tools/micyou_sim.py phone HOST [--port 8554] [--seconds 5] [--tcp] [--drop 0.0] [--reorder 0.0] [--opus]
      模拟发送端：握手 + connect，10 ms 一包发 440 Hz 正弦（48k 单声道 PCM16），回 pong。--drop/--reorder 按比例丢包/交换相邻包。

依赖：pip install protobuf grpcio-tools（运行时用 protoc 编译下面内嵌的 .proto，消息与字段号取自
MicYou 的 tauri-app/crates/micyou-protocol/proto/network.proto，Copyright (C) 2026 LanRhyme，GPL-3.0-or-later + MicYou Plugin Exception）。
用官方 protobuf 运行时编解码，便于核对 MicMask 手写编解码与 MicYou（prost / kotlinx-serialization-protobuf）线上格式一致。
"""
import argparse, math, os, random, socket, struct, sys, tempfile, threading, time, wave, importlib

PROTO = r'''
syntax = "proto3";
package micyou;
message MessageWrapper {
    AudioPacketMessageOrdered audioPacket = 1;
    ConnectMessage connect = 2;
    MuteMessage mute = 3;
    reserved 4;
    PingMessage ping = 5;
    PongMessage pong = 6;
    PluginMessage pluginMessage = 7;
    StatsMessage stats = 8;
}
message StatsMessage { int32 rttMs = 1; float jitterMs = 2; float packetLoss = 3; int32 latencyMs = 4; }
message PluginMessage { string source = 1; string target = 2; string topic = 3; bytes payload = 4; uint64 correlationId = 5; bool isResponse = 6; int32 errorCode = 7; string errorMessage = 8; }
message ConnectMessage { int64 sessionId = 1; string platform = 2; string transport = 3; }
message MuteMessage { optional bool isMuted = 1; }
message PingMessage { int64 timestamp = 1; }
message PongMessage { int64 timestamp = 1; }
message AudioPacketMessageOrdered { int32 sequenceNumber = 1; AudioPacketMessage audioPacket = 2; int64 timestamp = 3; bytes fecBuffer = 4; int32 fecSequenceNumber = 5; int64 sessionId = 6; repeated uint32 fecPacketLengths = 7; }
message AudioPacketMessage { bytes buffer = 1; int32 sampleRate = 2; int32 channelCount = 3; int32 audioFormat = 4; int32 codec = 5; }
'''

PACKET_MAGIC = 0x4D696359
UDP_MAGIC = 0x4D696355
HS_C = b"MicYouCheck1"
HS_S = b"MicYouCheck2"


def load_pb():
    d = tempfile.mkdtemp(prefix="micyou_pb_")
    with open(os.path.join(d, "micyou_net.proto"), "w") as f:
        f.write(PROTO)
    from grpc_tools import protoc
    if protoc.main(["protoc", "-I" + d, "--python_out=" + d, os.path.join(d, "micyou_net.proto")]) != 0:
        sys.exit("protoc 失败")
    sys.path.insert(0, d)
    return importlib.import_module("micyou_net_pb2")


pb = load_pb()
now_ms = lambda: int(time.time() * 1000)


def recv_exact(s, n):
    b = b""
    while len(b) < n:
        c = s.recv(n - len(b))
        if not c:
            raise ConnectionError("对端关闭")
        b += c
    return b


def read_frame(s):
    h = recv_exact(s, 8)
    magic, n = struct.unpack(">ii", h)
    if magic != PACKET_MAGIC or n < 0 or n > 1 << 20:
        raise ValueError("帧头不合法 %x %d" % (magic, n))
    w = pb.MessageWrapper()
    w.ParseFromString(recv_exact(s, n))
    return w


def write_frame(s, w, lock):
    p = w.SerializeToString()
    with lock:
        s.sendall(struct.pack(">ii", PACKET_MAGIC, len(p)) + p)


# ---------------------------------------------------------------- 模拟电脑端

def desktop(a):
    st = {"pk": 0, "bytes": 0, "gaps": 0, "dup": 0, "last": None, "rtt": -1, "sid": None, "peer": None, "pcm": bytearray(), "opus": 0, "bad": 0, "fmt": None}
    us = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); us.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    us.bind(("0.0.0.0", a.port + 1)); us.settimeout(0.2)
    ts = socket.socket(); ts.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); ts.bind(("0.0.0.0", a.port)); ts.listen(4)
    print("[desktop] 监听 TCP %d / UDP %d" % (a.port, a.port + 1), flush=True)
    stop = time.time() + a.seconds
    lock = threading.Lock()

    def on_audio(ap, via):
        if st["sid"] is None or ap.sessionId != st["sid"]:
            st["bad"] += 1; return
        au = ap.audioPacket
        if au.codec == 1:
            st["opus"] += 1
        st["fmt"] = (au.sampleRate, au.channelCount, au.audioFormat, au.codec)
        if st["last"] is not None:
            if ap.sequenceNumber == st["last"]:
                st["dup"] += 1
            elif ap.sequenceNumber != st["last"] + 1:
                st["gaps"] += 1
        st["last"] = ap.sequenceNumber
        st["pk"] += 1; st["bytes"] += len(au.buffer)
        if au.codec == 0:
            st["pcm"] += au.buffer

    def conn(c, addr):
        c.settimeout(10)
        if recv_exact(c, 12) != HS_C:
            print("[desktop] 握手串不对"); c.close(); return
        c.sendall(HS_S)
        w = read_frame(c)
        if not w.HasField("connect"):
            print("[desktop] 首帧没有 connect（旧客户端）")
        st["sid"] = w.connect.sessionId; st["peer"] = addr[0]
        print("[desktop] 握手成功 %s platform=%s transport=%s session=%d" % (addr[0], w.connect.platform, w.connect.transport, w.connect.sessionId), flush=True)

        def pinger():
            i = 0
            while time.time() < stop:
                time.sleep(0.5); i += 1
                try:
                    m = pb.MessageWrapper(); m.ping.timestamp = now_ms(); write_frame(c, m, lock)
                    if i % 2 == 0:
                        m = pb.MessageWrapper(); m.stats.rttMs = max(st["rtt"], 0); m.stats.jitterMs = 1.0; m.stats.packetLoss = 0.0; m.stats.latencyMs = 100 + max(st["rtt"], 0)
                        write_frame(c, m, lock)
                except OSError:
                    return
        threading.Thread(target=pinger, daemon=True).start()
        try:
            while time.time() < stop:
                m = read_frame(c)
                if m.HasField("ping"):
                    r = pb.MessageWrapper(); r.pong.timestamp = m.ping.timestamp; write_frame(c, r, lock)
                if m.HasField("pong"):
                    st["rtt"] = now_ms() - m.pong.timestamp
                if m.HasField("audioPacket"):
                    on_audio(m.audioPacket, "tcp")
        except Exception as e:
            print("[desktop] 连接结束：%s" % e, flush=True)
        c.close()

    def acc():
        ts.settimeout(0.5)
        while time.time() < stop:
            try:
                c, addr = ts.accept()
            except socket.timeout:
                continue
            threading.Thread(target=conn, args=(c, addr), daemon=True).start()
    threading.Thread(target=acc, daemon=True).start()
    while time.time() < stop:
        try:
            d, addr = us.recvfrom(65535)
        except socket.timeout:
            continue
        if len(d) < 8:
            continue
        magic, n = struct.unpack(">ii", d[:8])
        if magic != UDP_MAGIC or n < 0 or 8 + n > len(d):
            st["bad"] += 1; continue
        if len(d) > 1472:
            print("[desktop] 警告：数据报 %d 字节 > 1472" % len(d))
        w = pb.MessageWrapper(); w.ParseFromString(d[8:8 + n])
        if w.HasField("audioPacket"):
            if addr[0] != st["peer"]:
                st["bad"] += 1; continue
            on_audio(w.audioPacket, "udp")
    pcm = bytes(st["pcm"])
    rms = 0.0
    if pcm:
        n = len(pcm) // 2
        smp = struct.unpack("<%dh" % n, pcm[:n * 2])
        rms = math.sqrt(sum(x * x for x in smp) / n) / 32768
    print("[desktop] RESULT packets=%d bytes=%d gaps=%d dup=%d bad=%d opus=%d rtt=%dms fmt=%s pcm_seconds=%.2f rms=%.3f" % (
        st["pk"], st["bytes"], st["gaps"], st["dup"], st["bad"], st["opus"], st["rtt"], st["fmt"], len(pcm) / 96000, rms), flush=True)
    if a.wav and pcm:
        with wave.open(a.wav, "wb") as wf:
            wf.setnchannels(1); wf.setsampwidth(2); wf.setframerate(48000); wf.writeframes(pcm)


# ---------------------------------------------------------------- 模拟发送端

def phone(a):
    s = socket.create_connection((a.host, a.port), timeout=3)
    s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    s.sendall(HS_C)
    if recv_exact(s, 12) != HS_S:
        sys.exit("握手失败")
    sid = random.randint(1, 1 << 62)
    lock = threading.Lock()
    w = pb.MessageWrapper(); w.connect.sessionId = sid; w.connect.platform = "android"; w.connect.transport = "tcp" if a.tcp else "both"
    write_frame(s, w, lock)
    print("[phone] 握手成功 session=%d" % sid, flush=True)
    st = {"pings": 0, "stats": None, "rtt": -1}

    def rd():
        try:
            while True:
                m = read_frame(s)
                if m.HasField("ping"):
                    st["pings"] += 1
                    r = pb.MessageWrapper(); r.pong.timestamp = m.ping.timestamp; write_frame(s, r, lock)
                if m.HasField("pong"):
                    st["rtt"] = now_ms() - m.pong.timestamp
                if m.HasField("stats"):
                    st["stats"] = (m.stats.rttMs, round(m.stats.jitterMs, 2), round(m.stats.packetLoss, 3), m.stats.latencyMs)
        except Exception:
            pass
    threading.Thread(target=rd, daemon=True).start()
    us = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    t0 = time.time(); seq = 0; held = None; sent = 0; dropped = 0
    ph = 0.0
    n_frames = int(a.seconds * 100)
    for i in range(n_frames):
        smp = []
        for _ in range(480):
            smp.append(int(0.3 * 32767 * math.sin(ph))); ph += 2 * math.pi * 440 / 48000
        m = pb.MessageWrapper(); ap = m.audioPacket
        ap.sequenceNumber = seq; ap.timestamp = now_ms(); ap.sessionId = sid
        ap.audioPacket.buffer = struct.pack("<480h", *smp) if not a.opus else b"\xf8\xff\xfe"
        ap.audioPacket.sampleRate = 48000; ap.audioPacket.channelCount = 1; ap.audioPacket.audioFormat = 2; ap.audioPacket.codec = 1 if a.opus else 0
        seq += 1
        if i % 100 == 0:
            p = pb.MessageWrapper(); p.ping.timestamp = now_ms(); write_frame(s, p, lock)
        payload = m.SerializeToString()
        if random.random() < a.drop:
            dropped += 1
        else:
            pkt = struct.pack(">ii", UDP_MAGIC, len(payload)) + payload
            if not a.tcp and held is None and random.random() < a.reorder:
                held = pkt
            else:
                if a.tcp:
                    with lock:
                        s.sendall(struct.pack(">ii", PACKET_MAGIC, len(payload)) + payload)
                else:
                    us.sendto(pkt, (a.host, a.port + 1))
                    if held is not None:
                        us.sendto(held, (a.host, a.port + 1)); held = None
                sent += 1
        dt = t0 + (i + 1) * 0.01 - time.time()
        if dt > 0:
            time.sleep(dt)
    time.sleep(0.5)
    print("[phone] RESULT sent=%d dropped=%d pings_from_peer=%d rtt=%dms peer_stats=%s" % (sent, dropped, st["pings"], st["rtt"], st["stats"]), flush=True)
    s.close()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    d = sub.add_parser("desktop"); d.add_argument("--port", type=int, default=8554); d.add_argument("--seconds", type=float, default=10); d.add_argument("--wav")
    p = sub.add_parser("phone"); p.add_argument("host"); p.add_argument("--port", type=int, default=8554); p.add_argument("--seconds", type=float, default=5)
    p.add_argument("--tcp", action="store_true"); p.add_argument("--drop", type=float, default=0.0); p.add_argument("--reorder", type=float, default=0.0); p.add_argument("--opus", action="store_true")
    a = ap.parse_args()
    desktop(a) if a.cmd == "desktop" else phone(a)
