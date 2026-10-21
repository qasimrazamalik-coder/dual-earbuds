// QR code encoder (ISO/IEC 18004): byte mode, error correction level M, versions 1-10.
// Written for C# 5 (.NET Framework csc 4.0) - uses only System and System.Text.
using System;
using System.Text;

namespace DualEarbuds
{
    public static class QrCode
    {
        // Index 0 unused; values for error correction level M.
        private static readonly int[] EccPerBlock = { 0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26 };
        private static readonly int[] NumBlocks = { 0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5 };
        private static readonly int[][] AlignPos = new int[][] {
            new int[0], new int[0],
            new int[] { 6, 18 }, new int[] { 6, 22 }, new int[] { 6, 26 }, new int[] { 6, 30 },
            new int[] { 6, 34 }, new int[] { 6, 22, 38 }, new int[] { 6, 24, 42 },
            new int[] { 6, 26, 46 }, new int[] { 6, 28, 50 }
        };

        public static bool[,] Encode(string text)
        {
            if (text == null) throw new ArgumentNullException("text");
            byte[] data = Encoding.UTF8.GetBytes(text);

            int version = -1;
            for (int v = 1; v <= 10; v++)
            {
                int countBits = v <= 9 ? 8 : 16;
                int needBits = 4 + countBits + data.Length * 8;
                if (needBits <= DataCodewords(v) * 8) { version = v; break; }
            }
            if (version < 0)
                throw new ArgumentException("Text is too long for a version 10-M QR code (max 213 UTF-8 bytes).", "text");

            byte[] codewords = BuildDataCodewords(data, version);
            byte[] allCodewords = AddEccAndInterleave(codewords, version);

            int size = version * 4 + 17;
            bool[,] modules = new bool[size, size];
            bool[,] isFunction = new bool[size, size];
            DrawFunctionPatterns(modules, isFunction, version);
            DrawCodewords(modules, isFunction, allCodewords);

            int bestMask = 0;
            int bestPenalty = int.MaxValue;
            for (int mask = 0; mask < 8; mask++)
            {
                ApplyMask(modules, isFunction, mask);
                DrawFormatBits(modules, isFunction, mask);
                int p = Penalty(modules);
                if (p < bestPenalty) { bestPenalty = p; bestMask = mask; }
                ApplyMask(modules, isFunction, mask); // XOR undo
            }
            ApplyMask(modules, isFunction, bestMask);
            DrawFormatBits(modules, isFunction, bestMask);
            return modules;
        }

        // ---------- capacity ----------

        private static int RawDataModules(int ver)
        {
            int result = (16 * ver + 128) * ver + 64;
            if (ver >= 2)
            {
                int numAlign = ver / 7 + 2;
                result -= (25 * numAlign - 10) * numAlign - 55;
                if (ver >= 7) result -= 36;
            }
            return result;
        }

        private static int TotalCodewords(int ver) { return RawDataModules(ver) / 8; }

        private static int DataCodewords(int ver)
        {
            return TotalCodewords(ver) - EccPerBlock[ver] * NumBlocks[ver];
        }

        // ---------- data encoding ----------

        private static byte[] BuildDataCodewords(byte[] data, int version)
        {
            int capacity = DataCodewords(version);
            BitBuffer bb = new BitBuffer(capacity);
            bb.Append(4, 4); // byte mode 0100
            bb.Append(data.Length, version <= 9 ? 8 : 16);
            for (int i = 0; i < data.Length; i++) bb.Append(data[i], 8);
            int capBits = capacity * 8;
            int term = Math.Min(4, capBits - bb.Length);
            bb.Append(0, term);
            bb.Append(0, (8 - bb.Length % 8) % 8);
            for (int pad = 0xEC; bb.Length < capBits; pad ^= 0xEC ^ 0x11) bb.Append(pad, 8);
            return bb.ToBytes();
        }

        private sealed class BitBuffer
        {
            private readonly byte[] buf;
            private int count;
            public BitBuffer(int capacityBytes) { buf = new byte[capacityBytes]; count = 0; }
            public int Length { get { return count; } }
            public void Append(int val, int len)
            {
                for (int i = len - 1; i >= 0; i--)
                {
                    if (((val >> i) & 1) != 0) buf[count >> 3] |= (byte)(0x80 >> (count & 7));
                    count++;
                }
            }
            public byte[] ToBytes()
            {
                byte[] r = new byte[count / 8];
                Array.Copy(buf, r, r.Length);
                return r;
            }
        }

        // ---------- Reed-Solomon ----------

        private static int GfMul(int x, int y)
        {
            int z = 0;
            for (int i = 7; i >= 0; i--)
            {
                z = (z << 1) ^ ((z >> 7) * 0x11D);
                z ^= ((y >> i) & 1) * x;
            }
            return z & 0xFF;
        }

        private static byte[] RsDivisor(int degree)
        {
            byte[] result = new byte[degree];
            result[degree - 1] = 1;
            int root = 1;
            for (int i = 0; i < degree; i++)
            {
                for (int j = 0; j < degree; j++)
                {
                    result[j] = (byte)GfMul(result[j], root);
                    if (j + 1 < degree) result[j] ^= result[j + 1];
                }
                root = GfMul(root, 0x02);
            }
            return result;
        }

        private static byte[] RsRemainder(byte[] data, int offset, int len, byte[] divisor)
        {
            byte[] result = new byte[divisor.Length];
            for (int k = 0; k < len; k++)
            {
                int factor = (data[offset + k] ^ result[0]) & 0xFF;
                Array.Copy(result, 1, result, 0, result.Length - 1);
                result[result.Length - 1] = 0;
                for (int i = 0; i < result.Length; i++)
                    result[i] ^= (byte)GfMul(divisor[i], factor);
            }
            return result;
        }

        private static byte[] AddEccAndInterleave(byte[] data, int version)
        {
            int numBlocks = NumBlocks[version];
            int eccLen = EccPerBlock[version];
            int rawCodewords = TotalCodewords(version);
            int numShortBlocks = numBlocks - rawCodewords % numBlocks;
            int shortBlockLen = rawCodewords / numBlocks; // includes ecc
            byte[] divisor = RsDivisor(eccLen);

            byte[][] blocks = new byte[numBlocks][];
            int k = 0;
            for (int i = 0; i < numBlocks; i++)
            {
                int datLen = shortBlockLen - eccLen + (i < numShortBlocks ? 0 : 1);
                byte[] ecc = RsRemainder(data, k, datLen, divisor);
                byte[] block = new byte[shortBlockLen + 1];
                Array.Copy(data, k, block, 0, datLen);
                // short blocks keep a gap at index datLen (skipped during interleave)
                Array.Copy(ecc, 0, block, shortBlockLen + 1 - eccLen, eccLen);
                k += datLen;
                blocks[i] = block;
            }

            byte[] result = new byte[rawCodewords];
            int idx = 0;
            for (int i = 0; i < shortBlockLen + 1; i++)
            {
                for (int j = 0; j < numBlocks; j++)
                {
                    if (i != shortBlockLen - eccLen || j >= numShortBlocks)
                        result[idx++] = blocks[j][i];
                }
            }
            return result;
        }

        // ---------- function patterns ----------

        private static void SetFn(bool[,] m, bool[,] f, int x, int y, bool dark)
        {
            m[y, x] = dark;
            f[y, x] = true;
        }

        private static void DrawFunctionPatterns(bool[,] m, bool[,] f, int version)
        {
            int size = m.GetLength(0);
            for (int i = 0; i < size; i++)
            {
                SetFn(m, f, 6, i, i % 2 == 0);
                SetFn(m, f, i, 6, i % 2 == 0);
            }
            DrawFinder(m, f, 3, 3);
            DrawFinder(m, f, size - 4, 3);
            DrawFinder(m, f, 3, size - 4);

            int[] ap = AlignPos[version];
            int n = ap.Length;
            for (int i = 0; i < n; i++)
            {
                for (int j = 0; j < n; j++)
                {
                    if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue;
                    for (int dy = -2; dy <= 2; dy++)
                        for (int dx = -2; dx <= 2; dx++)
                            SetFn(m, f, ap[i] + dx, ap[j] + dy, Math.Max(Math.Abs(dx), Math.Abs(dy)) != 1);
                }
            }

            DrawFormatBits(m, f, 0); // reserve format area (overwritten later)
            DrawVersion(m, f, version);
        }

        private static void DrawFinder(bool[,] m, bool[,] f, int cx, int cy)
        {
            int size = m.GetLength(0);
            for (int dy = -4; dy <= 4; dy++)
            {
                for (int dx = -4; dx <= 4; dx++)
                {
                    int d = Math.Max(Math.Abs(dx), Math.Abs(dy));
                    int x = cx + dx, y = cy + dy;
                    if (x >= 0 && x < size && y >= 0 && y < size)
                        SetFn(m, f, x, y, d != 2 && d != 4);
                }
            }
        }

        private static void DrawFormatBits(bool[,] m, bool[,] f, int mask)
        {
            int size = m.GetLength(0);
            int data = (0 << 3) | mask; // ECC level M format bits = 00
            int rem = data;
            for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >> 9) * 0x537);
            int bits = ((data << 10) | rem) ^ 0x5412;

            for (int i = 0; i <= 5; i++) SetFn(m, f, 8, i, Bit(bits, i));
            SetFn(m, f, 8, 7, Bit(bits, 6));
            SetFn(m, f, 8, 8, Bit(bits, 7));
            SetFn(m, f, 7, 8, Bit(bits, 8));
            for (int i = 9; i < 15; i++) SetFn(m, f, 14 - i, 8, Bit(bits, i));

            for (int i = 0; i < 8; i++) SetFn(m, f, size - 1 - i, 8, Bit(bits, i));
            for (int i = 8; i < 15; i++) SetFn(m, f, 8, size - 15 + i, Bit(bits, i));
            SetFn(m, f, 8, size - 8, true); // dark module
        }

        private static void DrawVersion(bool[,] m, bool[,] f, int version)
        {
            if (version < 7) return;
            int size = m.GetLength(0);
            int rem = version;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >> 11) * 0x1F25);
            int bits = (version << 12) | rem;
            for (int i = 0; i < 18; i++)
            {
                bool b = Bit(bits, i);
                int a = size - 11 + i % 3;
                int c = i / 3;
                SetFn(m, f, a, c, b);
                SetFn(m, f, c, a, b);
            }
        }

        private static bool Bit(int x, int i) { return ((x >> i) & 1) != 0; }

        // ---------- data placement & masking ----------

        private static void DrawCodewords(bool[,] m, bool[,] f, byte[] data)
        {
            int size = m.GetLength(0);
            int i = 0;
            for (int right = size - 1; right >= 1; right -= 2)
            {
                if (right == 6) right = 5;
                for (int vert = 0; vert < size; vert++)
                {
                    for (int j = 0; j < 2; j++)
                    {
                        int x = right - j;
                        bool upward = ((right + 1) & 2) == 0;
                        int y = upward ? size - 1 - vert : vert;
                        if (!f[y, x] && i < data.Length * 8)
                        {
                            m[y, x] = Bit(data[i >> 3], 7 - (i & 7));
                            i++;
                        }
                        // remaining (remainder) bits stay light
                    }
                }
            }
        }

        private static void ApplyMask(bool[,] m, bool[,] f, int mask)
        {
            int size = m.GetLength(0);
            for (int y = 0; y < size; y++)
            {
                for (int x = 0; x < size; x++)
                {
                    if (f[y, x]) continue;
                    bool invert;
                    switch (mask)
                    {
                        case 0: invert = (x + y) % 2 == 0; break;
                        case 1: invert = y % 2 == 0; break;
                        case 2: invert = x % 3 == 0; break;
                        case 3: invert = (x + y) % 3 == 0; break;
                        case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                        case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                        case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                        default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                    }
                    if (invert) m[y, x] = !m[y, x];
                }
            }
        }

        // ---------- penalty (ISO/IEC 18004 rules N1..N4) ----------

        private static int Penalty(bool[,] m)
        {
            int size = m.GetLength(0);
            int result = 0;

            // N1: runs of >= 5 same-colour modules in rows/columns: 3 + (len - 5)
            for (int pass = 0; pass < 2; pass++)
            {
                for (int a = 0; a < size; a++)
                {
                    int run = 1;
                    bool prev = Get(m, pass, a, 0);
                    for (int b = 1; b < size; b++)
                    {
                        bool c = Get(m, pass, a, b);
                        if (c == prev) run++;
                        else
                        {
                            if (run >= 5) result += 3 + (run - 5);
                            run = 1; prev = c;
                        }
                    }
                    if (run >= 5) result += 3 + (run - 5);
                }
            }

            // N2: 2x2 blocks of same colour: 3 each
            for (int y = 0; y < size - 1; y++)
                for (int x = 0; x < size - 1; x++)
                {
                    bool c = m[y, x];
                    if (c == m[y, x + 1] && c == m[y + 1, x] && c == m[y + 1, x + 1]) result += 3;
                }

            // N3: 1:1:3:1:1 finder-like pattern with 4 light modules on either side: 40 each
            // Modules outside the symbol are treated as light.
            // Run-length based, so scaled patterns (n:n:3n:n:n) are found too.
            int[] hist = new int[7];
            for (int pass = 0; pass < 2; pass++)
            {
                for (int a = 0; a < size; a++)
                {
                    Array.Clear(hist, 0, 7);
                    bool runColor = false;
                    int runLen = 0;
                    for (int b = 0; b < size; b++)
                    {
                        bool c = Get(m, pass, a, b);
                        if (c == runColor) runLen++;
                        else
                        {
                            AddHistory(runLen, hist, size);
                            if (!runColor) result += 40 * CountFinderPatterns(hist);
                            runColor = c;
                            runLen = 1;
                        }
                    }
                    if (runColor) { AddHistory(runLen, hist, size); runLen = 0; }
                    runLen += size; // light border after the symbol
                    AddHistory(runLen, hist, size);
                    result += 40 * CountFinderPatterns(hist);
                }
            }

            // N4: dark proportion deviation: 10 per 5% step away from 50%
            int dark = 0;
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++)
                    if (m[y, x]) dark++;
            int total = size * size;
            int kk = (Math.Abs(dark * 20 - total * 10) + total - 1) / total - 1;
            if (kk < 0) kk = 0;
            result += kk * 10;
            return result;
        }

        // hist[0] is the most recent run; the first run of a line is light and gets the border added.
        private static void AddHistory(int runLen, int[] hist, int size)
        {
            if (hist[0] == 0) runLen += size;
            Array.Copy(hist, 0, hist, 1, 6);
            hist[0] = runLen;
        }

        // hist holds light,dark,light,dark,light,dark,light runs ending with a light run.
        private static int CountFinderPatterns(int[] hist)
        {
            int n = hist[1];
            bool core = n > 0 && hist[2] == n && hist[3] == n * 3 && hist[4] == n && hist[5] == n;
            int count = 0;
            if (core && hist[0] >= n * 4 && hist[6] >= n) count++;
            if (core && hist[6] >= n * 4 && hist[0] >= n) count++;
            return count;
        }

        private static bool Get(bool[,] m, int pass, int a, int b)
        {
            return pass == 0 ? m[a, b] : m[b, a];
        }
    }
}
