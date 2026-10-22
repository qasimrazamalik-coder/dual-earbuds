// Dual Earbuds - UDP audio for native listeners (protocol v3.2). TCP holds the whole stream back to retransmit
// one lost Wi-Fi frame ("buffering"); UDP just loses it, and every datagram also carries the previous packet,
// so a single loss costs nothing. The WebSocket stays for control and as the fallback.
using System;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;

namespace DualEarbuds
{
    // One parsed audio block inside a datagram (fields reused, no allocation per packet).
    public struct UdpBlock
    {
        public uint Seq;
        public int Rate;
        public int PcmOffset;   // where the s16le stereo PCM starts in the datagram
        public int Frames;
    }

    public static class UdpAudio
    {
        public const int TokenBytes = 16;
        public const byte KeepaliveType = 1;
        public const int KeepaliveLength = 4 + 1 + TokenBytes;
        public const int KeepaliveEveryMs = 1000, HostTimeoutMs = 5000, FallbackAfterMs = 2000;
        static readonly byte[] AudioMagic = Encoding.ASCII.GetBytes("DE3A");
        static readonly byte[] KeepMagic = Encoding.ASCII.GetBytes("DE3U");

        // A fresh random session token, as the 32 hex characters hello carries.
        public static string NewToken()
        {
            var b = new byte[TokenBytes];
            using (var rng = new RNGCryptoServiceProvider()) rng.GetBytes(b);
            return ToHex(b);
        }

        public static string ToHex(byte[] b)
        {
            var sb = new StringBuilder(b.Length * 2);
            foreach (var x in b) sb.Append(x.ToString("x2", CultureInfo.InvariantCulture));
            return sb.ToString();
        }

        // 32 hex characters -> 16 bytes, or null.
        public static byte[] FromHex(string s)
        {
            if (s == null || s.Length != TokenBytes * 2) return null;
            var b = new byte[TokenBytes];
            for (int i = 0; i < TokenBytes; i++)
            {
                int hi = Nibble(s[2 * i]), lo = Nibble(s[2 * i + 1]);
                if (hi < 0 || lo < 0) return null;
                b[i] = (byte)(hi * 16 + lo);
            }
            return b;
        }

        static int Nibble(char c)
        {
            if (c >= '0' && c <= '9') return c - '0';
            if (c >= 'a' && c <= 'f') return c - 'a' + 10;
            if (c >= 'A' && c <= 'F') return c - 'A' + 10;
            return -1;
        }

        public static byte[] BuildKeepalive(byte[] token)
        {
            var b = new byte[KeepaliveLength];
            Buffer.BlockCopy(KeepMagic, 0, b, 0, 4);
            b[4] = KeepaliveType;
            Buffer.BlockCopy(token, 0, b, 5, TokenBytes);
            return b;
        }

        // The token of a keepalive datagram as hex (the host's lookup key), or null for anything else.
        public static string ParseKeepalive(byte[] b, int len)
        {
            if (b == null || len != KeepaliveLength || !Starts(b, KeepMagic) || b[4] != KeepaliveType) return null;
            var t = new byte[TokenBytes];
            Buffer.BlockCopy(b, 5, t, 0, TokenBytes);
            return ToHex(t);
        }

        // "DE3A" + [seq][rate][pcm] of this packet + [seq-1][rate][pcm] of the previous one (when there is one).
        // body = [rate][seq][pcm] exactly as in the WebSocket packet (bodyOffset into a WS frame works too).
        public static byte[] BuildDatagram(byte[] body, int bodyOffset, int bodyLength, byte[] prevBody, int prevOffset, int prevLength)
        {
            int n = 4 + bodyLength + (prevBody != null ? prevLength : 0);
            var d = new byte[n];
            Buffer.BlockCopy(AudioMagic, 0, d, 0, 4);
            WriteBlock(d, 4, body, bodyOffset, bodyLength);
            if (prevBody != null) WriteBlock(d, 4 + bodyLength, prevBody, prevOffset, prevLength);
            return d;
        }

        // WS body order is rate, seq; the UDP block order is seq, rate (protocol v3.2).
        static void WriteBlock(byte[] d, int at, byte[] body, int off, int len)
        {
            Buffer.BlockCopy(body, off + 4, d, at, 4);       // seq
            Buffer.BlockCopy(body, off, d, at + 4, 4);       // rate
            Buffer.BlockCopy(body, off + 8, d, at + 8, len - 8);
        }

        // Parses an audio datagram. Returns false for anything that isn't one. hasPrev: the redundant copy of
        // the previous packet is there (accepted with or without its own "DE3A" in front of it).
        public static bool TryParse(byte[] b, int len, out UdpBlock cur, out bool hasPrev, out UdpBlock prev)
        {
            cur = new UdpBlock();
            prev = new UdpBlock();
            hasPrev = false;
            if (b == null || len < 12 || !Starts(b, AudioMagic)) return false;
            if (!ReadBlock(b, 4, len, out cur)) return false;
            int at = 4 + 8 + cur.Frames * 4;
            if (at == len) return true;
            if (len - at >= 4 && b[at] == AudioMagic[0] && b[at + 1] == AudioMagic[1] && b[at + 2] == AudioMagic[2] && b[at + 3] == AudioMagic[3]
                && (len - at - 4) == 8 + cur.Frames * 4) at += 4;
            UdpBlock p;
            if (ReadBlock(b, at, len, out p) && at + 8 + p.Frames * 4 == len && p.Rate == cur.Rate) { prev = p; hasPrev = true; }
            return true;
        }

        // A block's length follows from its rate: exactly 10 ms of stereo s16 (the packet size of the protocol).
        static bool ReadBlock(byte[] b, int at, int len, out UdpBlock blk)
        {
            blk = new UdpBlock();
            if (len - at < 8) return false;
            blk.Seq = (uint)(b[at] | (b[at + 1] << 8) | (b[at + 2] << 16) | (b[at + 3] << 24));
            blk.Rate = b[at + 4] | (b[at + 5] << 8) | (b[at + 6] << 16) | (b[at + 7] << 24);
            if (blk.Rate < 8000 || blk.Rate > 384000) return false;
            int frames = PhoneLink.PacketFrames(blk.Rate);
            if (len - at - 8 < frames * 4) return false;
            blk.Frames = frames;
            blk.PcmOffset = at + 8;
            return true;
        }

        static bool Starts(byte[] b, byte[] magic)
        {
            return b.Length >= 4 && b[0] == magic[0] && b[1] == magic[1] && b[2] == magic[2] && b[3] == magic[3];
        }
    }

    public enum UdpAction { None, SendOkFalse }

    // Listener side: is UDP audio arriving, or should the host go back to WebSocket audio?
    // "No UDP within 2 s of hello" is only judged while the host is actually producing sound (WebSocket audio
    // arriving, or the clock message's seq moving on): a host that plays nothing sends nothing on either path.
    // Once UDP worked, it is given up only when the host has produced 1.5 s more than UDP delivered (the host's
    // sequence is the last clock message's, moved on by the time since; so the verdict doesn't wait for the next one).
    public class UdpHealth
    {
        public const int BehindPackets = 150;

        bool offered, refused, haveUdp;
        long helloAt, lastUdpAt = long.MinValue / 2, lastWsAt = long.MinValue / 2, firstWsAt = long.MinValue / 2;
        uint lastUdpSeq, clockSeq, clockBase;
        bool haveClock;
        long clockAt;

        public bool Offered { get { return offered; } }
        public bool Refused { get { return refused; } }

        public bool Active(long now) { return offered && !refused && haveUdp && now - lastUdpAt < UdpAudio.FallbackAfterMs; }

        public void OnHello(long now, bool udpOffered)
        {
            offered = udpOffered;
            refused = false;
            haveUdp = false;
            helloAt = now;
            haveClock = false;
            firstWsAt = long.MinValue / 2;
        }

        public void OnUdp(uint seq, long now)
        {
            if (!haveUdp || unchecked((int)(seq - lastUdpSeq)) > 0) lastUdpSeq = seq;
            haveUdp = true;
            lastUdpAt = now;
        }

        public void OnWsAudio(long now)
        {
            if (now - lastWsAt > 1000) firstWsAt = now;   // start of a run of WebSocket audio
            lastWsAt = now;
        }

        public void OnClock(uint seq, long now)
        {
            if (!haveClock) clockBase = seq;
            clockSeq = seq;
            clockAt = now;
            haveClock = true;
        }

        public UdpAction Tick(long now)
        {
            if (!offered || refused) return UdpAction.None;
            if (haveUdp)
            {
                bool clockFresh = haveClock && now - clockAt < 1500;
                uint hostSeq = unchecked(clockSeq + (uint)((now - clockAt) / PhoneLink.PacketMs));
                if (clockFresh && now - lastUdpAt >= 1000 && unchecked((int)(hostSeq - lastUdpSeq)) > BehindPackets)
                {
                    refused = true;
                    return UdpAction.SendOkFalse;
                }
                return UdpAction.None;
            }
            if (now - helloAt < UdpAudio.FallbackAfterMs) return UdpAction.None;
            // the host keeps sending WebSocket audio (it never got our keepalives), or produces audio we never see
            bool wsRunning = now - lastWsAt < 500 && now - firstWsAt >= 1000;
            bool producing = haveClock && now - clockAt < 1500 && unchecked((int)(clockSeq - clockBase)) >= 100;
            if (wsRunning || producing)
            {
                refused = true;
                return UdpAction.SendOkFalse;
            }
            return UdpAction.None;
        }
    }
}
