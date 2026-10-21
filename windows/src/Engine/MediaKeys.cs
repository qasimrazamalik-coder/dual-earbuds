// Dual Earbuds - remote control (protocol v3.1): a listener's play/pause/next/... becomes the matching
// Windows media key, exactly as if the user pressed it on the keyboard. Only when the host allows it.
using System;
using System.Runtime.InteropServices;

namespace DualEarbuds
{
    public static class MediaKeys
    {
        public static readonly string[] Actions = { "playpause", "next", "prev", "volup", "voldown" };

        public static bool IsAction(string a) { return VirtualKey(a) != 0; }

        public static ushort VirtualKey(string action)
        {
            switch (action)
            {
                case "playpause": return 0xB3;   // VK_MEDIA_PLAY_PAUSE
                case "next": return 0xB0;        // VK_MEDIA_NEXT_TRACK
                case "prev": return 0xB1;        // VK_MEDIA_PREV_TRACK
                case "volup": return 0xAF;       // VK_VOLUME_UP
                case "voldown": return 0xAE;     // VK_VOLUME_DOWN
                default: return 0;
            }
        }

        [StructLayout(LayoutKind.Sequential)]
        struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }

        // INPUT is a union; its largest member (MOUSEINPUT) sets the size, so pad the keyboard variant.
        [StructLayout(LayoutKind.Sequential)]
        struct INPUT { public uint type; public KEYBDINPUT ki; public uint pad1; public uint pad2; }

        const uint INPUT_KEYBOARD = 1, KEYEVENTF_EXTENDEDKEY = 0x1, KEYEVENTF_KEYUP = 0x2;

        [DllImport("user32.dll", SetLastError = true)]
        static extern uint SendInput(uint count, INPUT[] inputs, int size);

        // Presses and releases the key. Returns false when Windows refused (e.g. a secure desktop is shown).
        public static bool Press(string action)
        {
            ushort vk = VirtualKey(action);
            if (vk == 0) return false;
            try
            {
                var inputs = new INPUT[2];
                inputs[0].type = INPUT_KEYBOARD;
                inputs[0].ki.wVk = vk;
                inputs[0].ki.dwFlags = KEYEVENTF_EXTENDEDKEY;
                inputs[1].type = INPUT_KEYBOARD;
                inputs[1].ki.wVk = vk;
                inputs[1].ki.dwFlags = KEYEVENTF_EXTENDEDKEY | KEYEVENTF_KEYUP;
                return SendInput(2, inputs, Marshal.SizeOf(typeof(INPUT))) == 2;
            }
            catch (Exception ex)
            {
                Log.Warn("Media key: " + ex.Message);
                return false;
            }
        }
    }
}
