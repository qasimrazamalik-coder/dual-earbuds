// Dual Earbuds - draws the app icon once (WPF) and exports it for the window, tray and .ico files.
using System;
using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace DualEarbuds
{
    public static class IconFactory
    {
        // Renders the logo tile at an exact pixel size.
        public static BitmapSource Render(int px)
        {
            var dv = new DrawingVisual();
            using (var dc = dv.RenderOpen())
            {
                double r = px * 0.24;
                dc.DrawRoundedRectangle(new SolidColorBrush(Color.FromRgb(0x0B, 0x12, 0x20)), null, new Rect(0, 0, px, px), r, r);
                // the 24x24 mark fills the tile minus a margin (smaller margin at tiny sizes so it stays readable)
                double pad = px <= 24 ? px * 0.06 : px * 0.14, scale = (px - pad * 2) / 24.0;
                dc.PushTransform(new TranslateTransform(pad, pad));
                dc.PushTransform(new ScaleTransform(scale, scale));
                for (int i = 0; i < Icons.Mark.Length; i++)
                    dc.DrawGeometry(Icons.MarkBrush(Icons.Mark[i][0]), null, Geometry.Parse(Icons.Mark[i][1]));
                dc.Pop();
                dc.Pop();
            }
            var bmp = new RenderTargetBitmap(px, px, 96, 96, PixelFormats.Pbgra32);
            bmp.Render(dv);
            bmp.Freeze();
            return bmp;
        }

        public static byte[] Png(BitmapSource src)
        {
            var enc = new PngBitmapEncoder();
            enc.Frames.Add(BitmapFrame.Create(src));
            using (var ms = new MemoryStream()) { enc.Save(ms); return ms.ToArray(); }
        }

        public static ImageSource WindowIcon() { return Render(64); }

        public static System.Drawing.Icon TrayIcon()
        {
            int px = 32;
            try
            {
                double dpi = System.Windows.SystemParameters.SmallIconWidth;
                if (dpi >= 24) px = 48;
            }
            catch { }
            using (var ms = new MemoryStream(Png(Render(px))))
            using (var bmp = new System.Drawing.Bitmap(ms))
                return System.Drawing.Icon.FromHandle(bmp.GetHicon());
        }

        // Multi-size .ico with PNG-compressed entries (Vista+ format).
        public static void WriteIco(string path)
        {
            int[] sizes = { 16, 20, 24, 32, 40, 48, 64, 128, 256 };
            var images = new byte[sizes.Length][];
            for (int i = 0; i < sizes.Length; i++) images[i] = Png(Render(sizes[i]));
            using (var fs = new FileStream(path, FileMode.Create))
            using (var w = new BinaryWriter(fs))
            {
                w.Write((short)0);
                w.Write((short)1);
                w.Write((short)sizes.Length);
                int offset = 6 + 16 * sizes.Length;
                for (int i = 0; i < sizes.Length; i++)
                {
                    w.Write((byte)(sizes[i] >= 256 ? 0 : sizes[i]));
                    w.Write((byte)(sizes[i] >= 256 ? 0 : sizes[i]));
                    w.Write((byte)0);
                    w.Write((byte)0);
                    w.Write((short)1);
                    w.Write((short)32);
                    w.Write(images[i].Length);
                    w.Write(offset);
                    offset += images[i].Length;
                }
                foreach (var img in images) w.Write(img);
            }
        }
    }
}
