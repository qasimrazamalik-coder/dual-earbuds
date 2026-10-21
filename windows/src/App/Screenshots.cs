// Dual Earbuds - offscreen screenshots, demo GIF and a UI smoke test (demo data, no audio, no window).
using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace DualEarbuds
{
    public static class Screenshots
    {
        const double W = 1180, H = 760;

        static MainView Build(bool running, string theme)
        {
            Theme.Apply(Application.Current.Resources, theme);
            var ctl = Controller.CreateDemo(running);
            var view = new MainView(ctl);
            view.RefreshAll();   // footer status matches the demo state
            return view;
        }

        static void Layout(FrameworkElement e)
        {
            e.Measure(new Size(W, H));
            e.Arrange(new Rect(0, 0, W, H));
            e.UpdateLayout();
            Dispatcher.CurrentDispatcher.Invoke(new Action(() => { }), DispatcherPriority.Render);
            e.UpdateLayout();
            Dispatcher.CurrentDispatcher.Invoke(new Action(() => { }), DispatcherPriority.ApplicationIdle);
        }

        static BitmapSource Snap(FrameworkElement e, double scale)
        {
            Layout(e);
            var bmp = new RenderTargetBitmap((int)(W * scale), (int)(H * scale), 96 * scale, 96 * scale, PixelFormats.Pbgra32);
            bmp.Render(e);
            bmp.Freeze();
            return bmp;
        }

        static void SavePng(BitmapSource b, string path)
        {
            var enc = new PngBitmapEncoder();
            enc.Frames.Add(BitmapFrame.Create(b));
            using (var fs = File.Create(path)) enc.Save(fs);
        }

        public static int Run(string dir)
        {
            Directory.CreateDirectory(dir);
            string[] pages = { "home", "phones", "listen", "settings", "diagnostics", "about" };
            foreach (var theme in new[] { "dark", "light" })
            {
                foreach (var page in pages)
                {
                    var v = Build(true, theme);
                    v.Navigate(page);
                    v.Animate();
                    SavePng(Snap(v, 1.5), Path.Combine(dir, page + "-" + theme + ".png"));
                }
            }
            foreach (var theme in new[] { "dark", "light" })
            {
                foreach (var shot in Extras)
                {
                    var v = BuildExtra(shot, theme);
                    SavePng(Snap(v, 1.5), Path.Combine(dir, shot + "-" + theme + ".png"));
                }
            }
            var pub = Build(true, "dark");
            pub.Ctl.DemoNetwork = "Public";
            pub.Navigate("phones");
            SavePng(Snap(pub, 1.5), Path.Combine(dir, "phones-public-network-dark.png"));
            var stopped = Build(false, "dark");
            SavePng(Snap(stopped, 1.5), Path.Combine(dir, "home-stopped-dark.png"));
            MakeDemoGif(Path.Combine(dir, "demo.gif"));
            Theme.Apply(Application.Current.Resources, "dark");
            return 0;
        }

        static readonly string[] Extras = { "phones-pin", "listen-to-device", "settings-appearance", "settings-smart" };

        // The newer screens with the demo data they need.
        static MainView BuildExtra(string shot, string theme)
        {
            var v = Build(true, theme);
            var c = v.Ctl;
            switch (shot)
            {
                case "phones-pin":
                    c.Settings.Party = true;
                    c.Settings.PinOn = true;
                    c.Settings.RemoteControl = true;
                    c.Phones.Add(new PhoneInfo { Id = "2", Name = "Sara's iPhone", Client = "web", Volume = 90, BufferMs = 180, Playing = true, RttMs = 21, Synced = true });
                    c.Phones.Add(new PhoneInfo { Id = "3", Name = "Office laptop", Client = "windows", Earbuds = "Headphones (WH-1000XM4)", Volume = 100, Playing = true, RttMs = 4, Synced = true });
                    c.UpdateStates();
                    v.Navigate("phones");
                    break;
                case "listen-to-device":
                    c.WantRunning = false;
                    c.StartBrowsing();
                    c.JoinFound(c.FoundHosts[0], null);
                    c.UpdateStates();
                    v.Navigate("listen");
                    break;
                case "settings-appearance":
                    v.Navigate("settings");
                    Layout(v);
                    v.ScrollTo("anchor_appearance");
                    break;
                case "settings-smart":
                    c.Settings.SmartEq = "both";
                    c.Settings.Dialogue = "light";
                    v.Navigate("settings");
                    Layout(v);
                    v.ScrollTo("anchor_smart");
                    break;
            }
            v.RefreshAll();
            v.Animate();
            return v;
        }

        // A short looping demo: stopped -> start -> sound levels moving -> phone page -> light theme.
        static void MakeDemoGif(string path)
        {
            var frames = new List<BitmapSource>();
            var delays = new List<int>();
            double scale = 0.75;

            var v = Build(false, "dark");
            frames.Add(Snap(v, scale)); delays.Add(140);

            v = Build(true, "dark");
            var rnd = new Random(7);
            for (int i = 0; i < 10; i++)
            {
                v.Ctl.Level = 0.45f + (float)rnd.NextDouble() * 0.4f;
                foreach (var o in v.Ctl.Outputs) if (o.Enabled) o.Level = 0.4f + (float)rnd.NextDouble() * 0.45f;
                v.Animate();
                frames.Add(Snap(v, scale)); delays.Add(12);
            }
            frames[frames.Count - 1] = frames[frames.Count - 1]; delays[delays.Count - 1] = 100;

            v.Navigate("phones");
            frames.Add(Snap(v, scale)); delays.Add(220);

            v = Build(true, "light");
            v.Animate();
            frames.Add(Snap(v, scale)); delays.Add(180);

            Gif.Write(path, frames, delays);
            Theme.Apply(Application.Current.Resources, "dark");
        }

        // Builds every page in both themes and checks for layout problems. Writes a report; returns 0 when clean.
        public static int UiTest(string path)
        {
            var sb = new StringBuilder();
            int problems = 0;
            foreach (var theme in new[] { "dark", "light" })
            {
                foreach (var page in new[] { "home", "phones", "listen", "settings", "diagnostics", "about" })
                {
                    try
                    {
                        var v = Build(true, theme);
                        v.Navigate(page);
                        Layout(v);
                        int count = 0, zero = 0;
                        Walk(v, e => { count++; if (e is Control && e.IsVisible && (e.ActualWidth < 1 || e.ActualHeight < 1)) zero++; });
                        sb.AppendLine(string.Format("{0}/{1}: {2} elements, {3} zero-size visible controls", theme, page, count, zero));
                        if (count < 20) { problems++; sb.AppendLine("  PROBLEM: page looks empty"); }
                        if (zero > 0) { problems++; sb.AppendLine("  PROBLEM: some controls have no size"); }
                    }
                    catch (Exception ex) { problems++; sb.AppendLine(theme + "/" + page + ": EXCEPTION " + ex); }
                }
            }
            foreach (var theme in new[] { "dark", "light" })
            {
                foreach (var shot in Extras)
                {
                    try
                    {
                        var v = BuildExtra(shot, theme);
                        Layout(v);
                        int zero = 0;
                        Walk(v, e => { if (e is Control && e.IsVisible && (e.ActualWidth < 1 || e.ActualHeight < 1)) zero++; });
                        sb.AppendLine(string.Format("{0}/{1}: {2} zero-size visible controls", theme, shot, zero));
                        if (zero > 0) { problems++; sb.AppendLine("  PROBLEM: some controls have no size"); }
                    }
                    catch (Exception ex) { problems++; sb.AppendLine(theme + "/" + shot + ": EXCEPTION " + ex); }
                }
            }
            try
            {
                var d = Controller.CreateDemo(true);
                d.Settings.Party = true;
                d.SetPinOn(true);
                if (!d.PinActive || !RoomPin.IsValid(d.Settings.Pin)) { problems++; sb.AppendLine("  PROBLEM: room PIN"); }
                if (d.JoinLink("not a link", null) || d.ListenProblem == null) { problems++; sb.AppendLine("  PROBLEM: bad link accepted"); }
                if (!d.JoinLink("192.168.1.31/?k=m4tq7zpa", null) || !d.Listening) { problems++; sb.AppendLine("  PROBLEM: link join"); }
                d.StopListening();
                if (d.Listening) { problems++; sb.AppendLine("  PROBLEM: stop listening"); }
                d.SetOutputEq(d.Outputs[1], "movie");
                if (d.Outputs[1].Effects.EffectivePreset != "movie") { problems++; sb.AppendLine("  PROBLEM: output EQ"); }
                if (d.DiagnosticsText().Contains(d.Settings.LinkKey) || d.DiagnosticsText().Contains("4711")) { problems++; sb.AppendLine("  PROBLEM: diagnostics leak a key or PIN"); }
                sb.AppendLine("demo listener / PIN / EQ / diagnostics checks done");
            }
            catch (Exception ex) { problems++; sb.AppendLine("features: EXCEPTION " + ex); }
            try
            {
                var c = Controller.CreateDemo(true);
                c.Toggle();
                sb.AppendLine("demo toggle -> running=" + c.Running);
                if (c.Running) { problems++; sb.AppendLine("  PROBLEM: toggle did not stop"); }
                c.Toggle();
                if (!c.Running) { problems++; sb.AppendLine("  PROBLEM: toggle did not start"); }
                var o = c.Outputs[1];
                c.SetDelay(o, 2000);
                c.SetVolume(o, -5);
                if (o.DelayMs != 1000 || o.Volume != 0) { problems++; sb.AppendLine("  PROBLEM: clamping"); }
                var img = Ui.Qr("http://192.168.1.16:8790/?k=Q7RK2MXD", 228);
                if (img.Source == null) { problems++; sb.AppendLine("  PROBLEM: QR"); }
                IconFactory.Render(256);
            }
            catch (Exception ex) { problems++; sb.AppendLine("controller: EXCEPTION " + ex); }
            sb.AppendLine(problems == 0 ? "UI TEST PASSED" : "UI TEST FAILED: " + problems + " problem(s)");
            File.WriteAllText(path, sb.ToString());
            return problems == 0 ? 0 : 1;
        }

        static void Walk(DependencyObject d, Action<FrameworkElement> f)
        {
            var fe = d as FrameworkElement;
            if (fe != null) f(fe);
            int n = VisualTreeHelper.GetChildrenCount(d);
            for (int i = 0; i < n; i++) Walk(VisualTreeHelper.GetChild(d, i), f);
        }
    }

    // Animated GIF writer: WPF encodes the frames, then the file is rewritten with per-frame delays and looping.
    public static class Gif
    {
        public static void Write(string path, List<BitmapSource> frames, List<int> delaysCs)
        {
            var enc = new GifBitmapEncoder();
            foreach (var f in frames) enc.Frames.Add(BitmapFrame.Create(f));
            byte[] raw;
            using (var ms = new MemoryStream()) { enc.Save(ms); raw = ms.ToArray(); }
            File.WriteAllBytes(path, Rewrite(raw, delaysCs));
        }

        public static byte[] Rewrite(byte[] g, List<int> delaysCs)
        {
            var o = new MemoryStream();
            int p = 0;
            o.Write(g, 0, 13);                                     // header + logical screen descriptor
            byte packed = g[10];
            p = 13;
            if ((packed & 0x80) != 0)
            {
                int gct = 3 * (1 << ((packed & 7) + 1));
                o.Write(g, p, gct);
                p += gct;
            }
            // NETSCAPE2.0 loop forever
            o.Write(new byte[] { 0x21, 0xFF, 0x0B }, 0, 3);
            byte[] ns = Encoding.ASCII.GetBytes("NETSCAPE2.0");
            o.Write(ns, 0, ns.Length);
            o.Write(new byte[] { 0x03, 0x01, 0x00, 0x00, 0x00 }, 0, 5);

            int frame = 0;
            while (p < g.Length)
            {
                byte b = g[p];
                if (b == 0x3B) { o.WriteByte(0x3B); break; }
                if (b == 0x21)
                {
                    int start = p;
                    byte label = g[p + 1];
                    p += 2;
                    while (g[p] != 0) p += g[p] + 1;
                    p++;
                    bool skip = label == 0xF9 || label == 0xFF;   // our own GCE and loop block replace these
                    if (!skip) o.Write(g, start, p - start);
                    continue;
                }
                if (b == 0x2C)
                {
                    int delay = frame < delaysCs.Count ? delaysCs[frame] : 10;
                    o.Write(new byte[] { 0x21, 0xF9, 0x04, 0x04, (byte)(delay & 0xFF), (byte)(delay >> 8), 0x00, 0x00 }, 0, 8);
                    int start = p;
                    byte ipacked = g[p + 9];
                    p += 10;
                    if ((ipacked & 0x80) != 0) p += 3 * (1 << ((ipacked & 7) + 1));
                    p++;                                           // LZW minimum code size
                    while (g[p] != 0) p += g[p] + 1;
                    p++;
                    o.Write(g, start, p - start);
                    frame++;
                    continue;
                }
                throw new InvalidDataException("Unexpected GIF block 0x" + b.ToString("X2") + " at " + p);
            }
            return o.ToArray();
        }
    }
}
