// Dual Earbuds Pro - draws the app icon once (WPF) and exports it for the window, tray and .ico files.
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
                double r = px * 0.26;
                var grad = new LinearGradientBrush(Color.FromRgb(0x14, 0xE0, 0xC2), Color.FromRgb(0x2F, 0x7B, 0xFF), 45);
                dc.DrawRoundedRectangle(grad, null, new Rect(0, 0, px, px), r, r);
                var geo = Geometry.Parse(Icons.LogoMark).Clone();
                // fit the mark's real bounds into the tile with padding, centered
                Rect bnd = geo.Bounds;
                double pad = px * 0.2, scale = (px - pad * 2) / Math.Max(bnd.Width, bnd.Height);
                var tg = new TransformGroup();
                tg.Children.Add(new TranslateTransform(-bnd.X - bnd.Width / 2, -bnd.Y - bnd.Height / 2));
                tg.Children.Add(new ScaleTransform(scale, scale));
                tg.Children.Add(new TranslateTransform(px / 2.0, px / 2.0));
                geo.Transform = tg;
                var pen = new Pen(new SolidColorBrush(Color.FromRgb(0x04, 0x20, 0x1B)), Math.Max(1.2, px / 11.0))
                {
                    StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round, LineJoin = PenLineJoin.Round
                };
                dc.DrawGeometry(null, pen, geo);
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
