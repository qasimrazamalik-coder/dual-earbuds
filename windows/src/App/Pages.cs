// Dual Earbuds Pro - pages.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Shapes;
using Path = System.Windows.Shapes.Path;

namespace DualEarbuds
{
    public interface IPage
    {
        FrameworkElement Root { get; }
        void Rebuild();   // device or phone list changed
        void Refresh();   // states changed
        void Animate();   // ~20 times a second (meters)
    }

    static class PageKit
    {
        public static StackPanel Column()
        {
            return new StackPanel { Margin = new Thickness(28, 8, 28, 28), MaxWidth = 980, HorizontalAlignment = HorizontalAlignment.Stretch };
        }

        public static StackPanel Header(string title, string subtitle)
        {
            var sp = new StackPanel { Margin = new Thickness(0, 4, 0, 18) };
            sp.Children.Add(Ui.Heading(title, 28));
            var s = Ui.Text(subtitle, 14, FontWeights.Normal, "Muted");
            s.Margin = new Thickness(0, 4, 0, 0);
            sp.Children.Add(s);
            return sp;
        }

        public static TextBlock Section(string text)
        {
            var t = Ui.Text(text.ToUpperInvariant(), 12, FontWeights.SemiBold, "Muted");
            t.Margin = new Thickness(4, 22, 0, 10);
            return t;
        }

        public static Grid Row(string title, string description, FrameworkElement control)
        {
            var g = new Grid { Margin = new Thickness(0, 6, 0, 6) };
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var left = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 20, 0) };
            left.Children.Add(Ui.Text(title, 14, FontWeights.SemiBold, "Text"));
            if (!string.IsNullOrEmpty(description))
            {
                var d = Ui.Text(description, 12.5, FontWeights.Normal, "Muted");
                d.Margin = new Thickness(0, 2, 0, 0);
                left.Children.Add(d);
            }
            control.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(control, 1);
            g.Children.Add(left);
            g.Children.Add(control);
            return g;
        }

        public static Border Divider()
        {
            var b = new Border { Height = 1, Margin = new Thickness(0, 8, 0, 8) };
            b.SetResourceReference(Border.BackgroundProperty, "Line");
            return b;
        }

        public static Border Segmented(string group, string[] labels, int selected, Action<int> onPick)
        {
            var sp = new StackPanel { Orientation = Orientation.Horizontal };
            for (int i = 0; i < labels.Length; i++)
            {
                int idx = i;
                var r = new RadioButton { Content = labels[i], GroupName = group, IsChecked = i == selected };
                r.SetResourceReference(FrameworkElement.StyleProperty, "Segment");
                r.Checked += (s, e) => onPick(idx);
                sp.Children.Add(r);
            }
            var b = new Border { CornerRadius = new CornerRadius(10), Padding = new Thickness(2), Child = sp };
            b.SetResourceReference(Border.BackgroundProperty, "Surface2");
            return b;
        }

        public static void OpenFolder(string dir)
        {
            try { Directory.CreateDirectory(dir); Process.Start("explorer.exe", "\"" + dir + "\""); }
            catch (Exception ex) { Log.Warn("Open folder: " + ex.Message); }
        }
    }

    // ---------------------------------------------------------------------- Home
    class OutputCard
    {
        public readonly Border Root;
        readonly OutputVm vm;
        readonly Controller ctl;
        readonly CheckBox sw;
        readonly TextBlock name, chipText, delayVal, volVal, error;
        readonly Ellipse chipDot;
        readonly StackPanel battery;
        readonly TextBlock batteryText;
        readonly Path batteryIcon, deviceIcon;
        readonly Meter meter;
        readonly Slider delay, volume;
        readonly Button test;
        bool updating;

        public string Id { get { return vm.Id; } }

        public OutputCard(OutputVm vm, Controller ctl)
        {
            this.vm = vm;
            this.ctl = ctl;
            var root = new StackPanel();

            var top = new Grid();
            top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            top.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            sw = Ui.Switch("", vm.Enabled);
            sw.Checked += (s, e) => { if (!updating) ctl.SetEnabled(vm, true); };
            sw.Unchecked += (s, e) => { if (!updating) ctl.SetEnabled(vm, false); };
            top.Children.Add(sw);

            var nameRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(6, 0, 12, 0), VerticalAlignment = VerticalAlignment.Center };
            deviceIcon = Ui.Icon(vm.Name.StartsWith("Speaker", StringComparison.OrdinalIgnoreCase) ? Icons.Speaker : Icons.Headphones, 18, "Muted", false);
            deviceIcon.Margin = new Thickness(0, 0, 10, 0);
            name = Ui.Heading(vm.Name, 16);
            name.TextTrimming = TextTrimming.CharacterEllipsis;
            name.TextWrapping = TextWrapping.NoWrap;
            nameRow.Children.Add(deviceIcon);
            nameRow.Children.Add(name);
            Grid.SetColumn(nameRow, 1);
            top.Children.Add(nameRow);

            battery = Ui.Battery(out batteryText, out batteryIcon);
            battery.Margin = new Thickness(0, 0, 12, 0);
            Grid.SetColumn(battery, 2);
            top.Children.Add(battery);

            var chip = Ui.Chip(vm.State, Ui.KindBrush(vm.Kind), out chipText, out chipDot);
            chip.Margin = new Thickness(0, 0, 10, 0);
            Grid.SetColumn(chip, 3);
            top.Children.Add(chip);

            test = Ui.Button("Test", "Ghost", (s, e) => ctl.Test(vm));
            test.ToolTip = "Play a short beep on this device";
            Grid.SetColumn(test, 4);
            top.Children.Add(test);
            root.Children.Add(top);

            meter = new Meter { Margin = new Thickness(0, 14, 0, 12) };
            root.Children.Add(meter);

            var sliders = new Grid();
            sliders.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            sliders.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(28) });
            sliders.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var d = Ui.LabeledSlider("Delay", 0, 500, vm.DelayMs, " ms", out delay, out delayVal);
            var v = Ui.LabeledSlider("Volume", 0, 150, vm.Volume, "%", out volume, out volVal);
            delay.ToolTip = "Raise this on the pair that plays ahead until both match";
            delay.ValueChanged += (s, e) =>
            {
                int ms = (int)(Math.Round(delay.Value / 5) * 5);
                delayVal.Text = ms + " ms";
                if (!updating) ctl.SetDelay(vm, ms);
            };
            volume.ValueChanged += (s, e) =>
            {
                int vol = (int)Math.Round(volume.Value);
                volVal.Text = vol + "%";
                if (!updating) ctl.SetVolume(vm, vol);
            };
            Grid.SetColumn(v, 2);
            sliders.Children.Add(d);
            sliders.Children.Add(v);
            root.Children.Add(sliders);

            error = Ui.Text("", 12.5, FontWeights.Normal, "Bad");
            error.Margin = new Thickness(0, 10, 0, 0);
            error.Visibility = Visibility.Collapsed;
            root.Children.Add(error);

            Root = Ui.Card(root);
            Root.Margin = new Thickness(0, 0, 0, 12);
            Refresh();
        }

        public void Refresh()
        {
            updating = true;
            sw.IsChecked = vm.Enabled;
            sw.IsEnabled = !vm.IsSource;
            sw.ToolTip = vm.IsSource ? "This is where the sound comes from" : null;
            name.Text = vm.Name;
            Ui.SetChip(chipText, chipDot, vm.State, Ui.KindBrush(vm.Kind));
            Ui.SetBattery(battery, batteryText, batteryIcon, vm.Battery);
            bool active = vm.Enabled && !vm.IsSource;
            delay.IsEnabled = active;
            volume.IsEnabled = active;
            test.IsEnabled = !vm.IsSource;
            Root.Opacity = vm.Enabled || vm.IsSource ? 1.0 : 0.72;
            if (vm.Error != null) { error.Text = vm.Error; error.Visibility = Visibility.Visible; }
            else error.Visibility = Visibility.Collapsed;
            updating = false;
        }

        public void Animate() { meter.Level = vm.IsSource ? ctl.Level : vm.Level; }
    }

    class HomePage : IPage
    {
        readonly Controller ctl;
        readonly Action<string> navigate;
        readonly StackPanel root;
        readonly Button startBtn;
        readonly TextBlock statusText, heroTitle, phonesText;
        readonly Ellipse statusDot;
        readonly Meter heroMeter;
        readonly ComboBox source;
        readonly StackPanel cards;
        readonly List<OutputCard> cardList = new List<OutputCard>();
        bool suppress;

        public FrameworkElement Root { get { return root; } }

        public HomePage(Controller ctl, Action<string> navigate)
        {
            this.ctl = ctl;
            this.navigate = navigate;
            root = PageKit.Column();
            root.Children.Add(PageKit.Header("Home", "Play your laptop's sound on two (or more) pairs of earbuds at once."));

            // hero
            var hero = new Grid();
            hero.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            hero.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var left = new StackPanel { Margin = new Thickness(0, 0, 24, 0) };
            var titleRow = new StackPanel { Orientation = Orientation.Horizontal };
            statusDot = new Ellipse { Width = 10, Height = 10, Margin = new Thickness(0, 0, 10, 0), VerticalAlignment = VerticalAlignment.Center };
            heroTitle = Ui.Heading("Stopped", 22);
            titleRow.Children.Add(statusDot);
            titleRow.Children.Add(heroTitle);
            left.Children.Add(titleRow);
            statusText = Ui.Text("", 13.5, FontWeights.Normal, "Muted");
            statusText.Margin = new Thickness(0, 6, 0, 14);
            left.Children.Add(statusText);
            heroMeter = new Meter { Height = 8, Margin = new Thickness(0, 0, 0, 16) };
            left.Children.Add(heroMeter);
            var srcLabel = Ui.Text("Sound comes from", 12.5, FontWeights.SemiBold, "Muted");
            srcLabel.Margin = new Thickness(0, 0, 0, 6);
            left.Children.Add(srcLabel);
            source = new ComboBox();
            source.SelectionChanged += (s, e) =>
            {
                if (suppress) return;
                var it = source.SelectedItem as ComboBoxItem;
                ctl.SetSource(it == null ? "" : (string)it.Tag);
            };
            left.Children.Add(source);
            hero.Children.Add(left);

            var right = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            startBtn = Ui.Button("Start mirroring", "Primary", (s, e) => ctl.Toggle());
            startBtn.Width = 200;
            startBtn.Height = 54;
            startBtn.FontSize = 16;
            right.Children.Add(startBtn);
            var hint = Ui.Text("Shortcut: Ctrl + Alt + M", 12, FontWeights.Normal, "Muted");
            hint.TextAlignment = TextAlignment.Center;
            hint.Margin = new Thickness(0, 10, 0, 0);
            right.Children.Add(hint);
            Grid.SetColumn(right, 1);
            hero.Children.Add(right);
            root.Children.Add(Ui.Card(hero));

            root.Children.Add(PageKit.Section("Play on"));
            cards = new StackPanel();
            root.Children.Add(cards);

            // phones summary
            var ph = new Grid();
            ph.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            ph.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            ph.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var phIcon = Ui.Icon(Icons.Phone, 22, "Accent", false);
            phIcon.Margin = new Thickness(0, 0, 16, 0);
            ph.Children.Add(phIcon);
            var phText = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            phText.Children.Add(Ui.Heading("Earbuds your laptop can't reach?", 15));
            phonesText = Ui.Text("", 13, FontWeights.Normal, "Muted");
            phonesText.Margin = new Thickness(0, 3, 0, 0);
            phText.Children.Add(phonesText);
            Grid.SetColumn(phText, 1);
            ph.Children.Add(phText);
            var phBtn = Ui.Button("Phone link", "Ghost", (s, e) => navigate("phones"));
            Grid.SetColumn(phBtn, 2);
            ph.Children.Add(phBtn);
            root.Children.Add(PageKit.Section("Phones"));
            root.Children.Add(Ui.Card(ph));

            Rebuild();
        }

        public void Rebuild()
        {
            suppress = true;
            source.Items.Clear();
            var def = new ComboBoxItem { Content = "Windows default output  (now: " + ctl.NameOf(ctl.DefaultId) + ")", Tag = "" };
            source.Items.Add(def);
            source.SelectedItem = def;
            foreach (var o in ctl.Outputs)
            {
                var it = new ComboBoxItem { Content = o.Name, Tag = o.Id };
                source.Items.Add(it);
                if (o.Id == ctl.Settings.SourceId) source.SelectedItem = it;
            }
            suppress = false;

            cards.Children.Clear();
            cardList.Clear();
            foreach (var o in ctl.Outputs)
            {
                var c = new OutputCard(o, ctl);
                cardList.Add(c);
                cards.Children.Add(c.Root);
            }
            if (ctl.Outputs.Count <= 1)
            {
                var t = Ui.Text("No other earbuds found. Connect them in Windows Bluetooth settings - they appear here by themselves. If Windows can only play to one pair, use the phone link below.", 13.5, FontWeights.Normal, "Muted");
                cards.Children.Add(Ui.Card(t));
            }
            Refresh();
        }

        public void Refresh()
        {
            bool on = ctl.Running || ctl.WantRunning;
            startBtn.Content = on ? "Stop mirroring" : "Start mirroring";
            startBtn.SetResourceReference(FrameworkElement.StyleProperty, on ? "Ghost" : "Primary");
            startBtn.FontSize = 16;
            heroTitle.Text = ctl.Running ? "Mirroring" : (ctl.WantRunning ? "Waiting for sound source" : "Stopped");
            string key = Ui.KindBrush(ctl.StatusKind);
            statusDot.SetResourceReference(Shape.FillProperty, ctl.Running ? (ctl.StatusKind == StateKind.Problem ? "Warn" : "Good") : key);
            statusText.Text = ctl.StatusText;
            foreach (var c in cardList) c.Refresh();
            int n = ctl.Phones.Count;
            phonesText.Text = n > 0
                ? n + (n == 1 ? " phone is" : " phones are") + " listening over Wi-Fi."
                : "Pair them with your phone and stream over Wi-Fi - no cables, no extra hardware.";
        }

        public void Animate()
        {
            heroMeter.Level = ctl.Level;
            foreach (var c in cardList) c.Animate();
        }
    }

    // ---------------------------------------------------------------------- Phones
    class PhonesPage : IPage
    {
        readonly Controller ctl;
        readonly StackPanel root;
        readonly Border qrHost;
        readonly TextBox link;
        readonly TextBlock qrHint, linkState;
        readonly CheckBox enable;
        readonly TextBox port;
        readonly StackPanel phoneList;
        readonly Border publicNote;
        string shownUrl = "\0";
        bool updating;

        public FrameworkElement Root { get { return root; } }

        public PhonesPage(Controller ctl)
        {
            this.ctl = ctl;
            root = PageKit.Column();
            root.Children.Add(PageKit.Header("Phones", "Stream your laptop's sound over Wi-Fi to a phone, and listen with the earbuds paired to it."));

            var grid = new Grid();
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(320) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(20) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            // QR card
            var qrCol = new StackPanel();
            qrHost = new Border { Width = 240, Height = 240, CornerRadius = new CornerRadius(16), HorizontalAlignment = HorizontalAlignment.Center, Padding = new Thickness(6) };
            qrHost.SetResourceReference(Border.BackgroundProperty, "QrBg");
            qrCol.Children.Add(qrHost);
            qrHint = Ui.Text("Scan with the Dual Earbuds Android app or your phone's camera.", 12.5, FontWeights.Normal, "Muted");
            qrHint.TextAlignment = TextAlignment.Center;
            qrHint.Margin = new Thickness(0, 14, 0, 12);
            qrCol.Children.Add(qrHint);
            link = new TextBox { IsReadOnly = true, FontFamily = Theme.Mono, FontSize = 12.5, TextWrapping = TextWrapping.Wrap };
            qrCol.Children.Add(link);
            var btns = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 12, 0, 0) };
            var copy = Ui.Button("Copy link", "Ghost", (s, e) => { try { if (!string.IsNullOrEmpty(link.Text)) Clipboard.SetText(link.Text); } catch { } });
            copy.Margin = new Thickness(0, 0, 8, 0);
            var reset = Ui.Button("New link", "Ghost", (s, e) =>
            {
                var r = MessageBox.Show("Make a new link? Phones using the old link are disconnected and need to scan again.", "Dual Earbuds", MessageBoxButton.OKCancel, MessageBoxImage.Question);
                if (r == MessageBoxResult.OK) { ctl.ResetLinkKey(); Refresh(); }
            });
            reset.ToolTip = "Creates a new secret key - use it if someone else has your link";
            btns.Children.Add(copy);
            btns.Children.Add(reset);
            qrCol.Children.Add(btns);
            grid.Children.Add(Ui.Card(qrCol));

            // right column
            var right = new StackPanel();

            // shown only on public networks (cafe / hotel / university Wi-Fi)
            var noteBody = new StackPanel();
            noteBody.Children.Add(Ui.Heading("You're on a public network", 15));
            var nt1 = Ui.Text("Like cafe, hotel or university Wi-Fi. Phones can still connect with the link above.", 13, FontWeights.Normal, "Text");
            nt1.Margin = new Thickness(0, 4, 0, 6);
            noteBody.Children.Add(nt1);
            noteBody.Children.Add(Ui.Text("If a phone can't open the link, this Wi-Fi blocks devices from reaching each other. Use a hotspot instead: " +
                "turn on the phone's hotspot and connect the laptop to it, or turn on Mobile hotspot on the laptop and connect the phone. " +
                "The sound goes straight between them and doesn't use mobile data.", 12.5, FontWeights.Normal, "Muted"));
            var noteRow = new Grid();
            noteRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            noteRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var wifi = Ui.Icon(Icons.Wifi, 22, "Warn", false);
            wifi.Margin = new Thickness(0, 2, 14, 0);
            wifi.VerticalAlignment = VerticalAlignment.Top;
            noteRow.Children.Add(wifi);
            Grid.SetColumn(noteBody, 1);
            noteRow.Children.Add(noteBody);
            publicNote = Ui.Card(noteRow);
            publicNote.Margin = new Thickness(0, 0, 0, 14);
            publicNote.SetResourceReference(Border.BorderBrushProperty, "Warn");
            publicNote.Visibility = Visibility.Collapsed;
            right.Children.Add(publicNote);

            var settingsCard = new StackPanel();
            enable = Ui.Switch("", ctl.Settings.PhoneLink);
            enable.Checked += (s, e) => { if (!updating) { ctl.SetPhoneLink(true); Refresh(); } };
            enable.Unchecked += (s, e) => { if (!updating) { ctl.SetPhoneLink(false); Refresh(); } };
            settingsCard.Children.Add(PageKit.Row("Phone link", "Lets phones on the same Wi-Fi or hotspot listen - at home or on public Wi-Fi. Each link has a secret key, so only phones with the link can connect.", enable));
            settingsCard.Children.Add(PageKit.Divider());
            var portRow = new StackPanel { Orientation = Orientation.Horizontal };
            port = new TextBox { Width = 90, Text = ctl.Settings.PhonePort.ToString(), FontFamily = Theme.Mono };
            var apply = Ui.Button("Apply", "Ghost", (s, e) => ApplyPort());
            apply.Margin = new Thickness(8, 0, 0, 0);
            port.KeyDown += (s, e) => { if (e.Key == Key.Enter) ApplyPort(); };
            portRow.Children.Add(port);
            portRow.Children.Add(apply);
            settingsCard.Children.Add(PageKit.Row("Port", "Change only if another app already uses this port.", portRow));
            linkState = Ui.Text("", 12.5, FontWeights.Normal, "Muted");
            linkState.Margin = new Thickness(0, 6, 0, 0);
            settingsCard.Children.Add(linkState);
            right.Children.Add(Ui.Card(settingsCard));

            right.Children.Add(PageKit.Section("Connected phones"));
            phoneList = new StackPanel();
            right.Children.Add(phoneList);

            right.Children.Add(PageKit.Section("How to connect"));
            var steps = new StackPanel();
            string[] s3 = {
                "Pair your second earbuds with your phone, and connect the phone to the same Wi-Fi as this laptop.",
                "Scan the QR code with the Dual Earbuds Android app (best: keeps playing with the screen off), or with the camera to open it in Chrome.",
                "Tap Start on the phone. If the phone's earbuds play behind the laptop's, raise Delay on the laptop's earbuds on the Home page." };
            for (int i = 0; i < s3.Length; i++)
            {
                var row = new Grid { Margin = new Thickness(0, 4, 0, 4) };
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(34) });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                var num = new Border { Width = 24, Height = 24, CornerRadius = new CornerRadius(12), VerticalAlignment = VerticalAlignment.Top };
                num.SetResourceReference(Border.BackgroundProperty, "AccentSoft");
                var nt = Ui.Text((i + 1).ToString(), 12, FontWeights.Bold, "Accent");
                nt.HorizontalAlignment = HorizontalAlignment.Center;
                nt.VerticalAlignment = VerticalAlignment.Center;
                num.Child = nt;
                var st = Ui.Text(s3[i], 13.5, FontWeights.Normal, "Text");
                Grid.SetColumn(st, 1);
                row.Children.Add(num);
                row.Children.Add(st);
                steps.Children.Add(row);
            }
            right.Children.Add(Ui.Card(steps));
            Grid.SetColumn(right, 2);
            grid.Children.Add(right);
            root.Children.Add(grid);
            Rebuild();
        }

        void ApplyPort()
        {
            int p;
            if (!int.TryParse(port.Text.Trim(), out p) || !ctl.SetPort(p))
            {
                MessageBox.Show("Use a port number between 1024 and 65535.", "Dual Earbuds", MessageBoxButton.OK, MessageBoxImage.Warning);
                port.Text = ctl.Settings.PhonePort.ToString();
            }
            Refresh();
        }

        public void Rebuild()
        {
            phoneList.Children.Clear();
            if (ctl.Phones.Count == 0)
            {
                phoneList.Children.Add(Ui.Card(Ui.Text("No phones connected yet.", 13.5, FontWeights.Normal, "Muted")));
            }
            foreach (var p in ctl.Phones)
            {
                var g = new Grid();
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                var ic = Ui.Icon(Icons.Phone, 22, "Accent", false);
                ic.Margin = new Thickness(0, 0, 14, 0);
                g.Children.Add(ic);
                var mid = new StackPanel();
                mid.Children.Add(Ui.Heading(p.Name, 15));
                string detail = (p.Client == "android" ? "Android app" : "Browser") +
                                (string.IsNullOrEmpty(p.Earbuds) ? "" : " · " + p.Earbuds) +
                                (p.RttMs >= 0 ? " · " + p.RttMs + " ms" : "") +
                                (p.BufferMs > 0 ? " · buffer " + p.BufferMs + " ms" : "") +
                                " · volume " + p.Volume + "%";
                var dt = Ui.Text(detail, 12.5, FontWeights.Normal, "Muted");
                dt.Margin = new Thickness(0, 3, 0, 0);
                mid.Children.Add(dt);
                Grid.SetColumn(mid, 1);
                g.Children.Add(mid);
                TextBlock bt; Path bi;
                var bat = Ui.Battery(out bt, out bi);
                Ui.SetBattery(bat, bt, bi, p.Battery);
                Grid.SetColumn(bat, 2);
                g.Children.Add(bat);
                var card = Ui.Card(g);
                card.Margin = new Thickness(0, 0, 0, 10);
                phoneList.Children.Add(card);
            }
            Refresh();
        }

        public void Refresh()
        {
            updating = true;
            enable.IsChecked = ctl.Settings.PhoneLink;
            updating = false;
            string url = ctl.Settings.PhoneLink ? ctl.LinkUrl : null;
            if (url != shownUrl)
            {
                shownUrl = url;
                if (url == null)
                {
                    qrHost.Child = Ui.Text(ctl.Settings.PhoneLink ? "Connect this laptop to Wi-Fi to get a link." : "The phone link is off.", 13.5, FontWeights.Normal, "Muted");
                    ((TextBlock)qrHost.Child).Foreground = Brushes.Gray;
                    ((TextBlock)qrHost.Child).TextAlignment = TextAlignment.Center;
                    ((TextBlock)qrHost.Child).VerticalAlignment = VerticalAlignment.Center;
                    link.Text = "";
                }
                else
                {
                    qrHost.Child = Ui.Qr(url, 228);
                    link.Text = url;
                }
            }
            publicNote.Visibility = ctl.Settings.PhoneLink && ctl.NetworkCategory == "Public" ? Visibility.Visible : Visibility.Collapsed;
            if (!ctl.Settings.PhoneLink) linkState.Text = "Off.";
            else if (ctl.Phone == null && !ctl.Demo) linkState.Text = "Not running - port " + ctl.Settings.PhonePort + " may be in use by another app (is the old Dual Earbuds still open?).";
            else
            {
                var a = ctl.Addresses;
                linkState.Text = "Listening on port " + ctl.Settings.PhonePort + (a.Count > 1 ? " · other addresses: " + string.Join(", ", a.GetRange(1, a.Count - 1).ToArray()) : "");
            }
        }

        public void Animate() { }
    }

    // ---------------------------------------------------------------------- Settings
    class SettingsPage : IPage
    {
        readonly StackPanel root;
        public FrameworkElement Root { get { return root; } }

        public SettingsPage(Controller ctl, Action<string> onTheme, Action onHotkeyChanged, Action onReset)
        {
            root = PageKit.Column();
            root.Children.Add(PageKit.Header("Settings", "Make Dual Earbuds work the way you like."));
            var s = ctl.Settings;

            root.Children.Add(PageKit.Section("Appearance"));
            var ap = new StackPanel();
            string[] modes = { "system", "dark", "light" };
            int mi = Array.IndexOf(modes, s.Theme);
            ap.Children.Add(PageKit.Row("Theme", "Follow Windows, or always dark or light.",
                PageKit.Segmented("theme", new[] { "System", "Dark", "Light" }, mi < 0 ? 0 : mi, i => { s.Theme = modes[i]; ctl.MarkDirty(); onTheme(modes[i]); })));
            root.Children.Add(Ui.Card(ap));

            root.Children.Add(PageKit.Section("Window"));
            var win = new StackPanel();
            win.Children.Add(PageKit.Row("When I close the window", "Keep running near the clock so the sound keeps playing, or exit completely.",
                PageKit.Segmented("close", new[] { "Keep running", "Exit" }, s.CloseToTray ? 0 : 1, i => { s.CloseToTray = i == 0; ctl.MarkDirty(); })));
            win.Children.Add(PageKit.Divider());
            var minSw = Ui.Switch("", s.StartMinimized);
            minSw.Checked += (o, e) => { s.StartMinimized = true; ctl.MarkDirty(); };
            minSw.Unchecked += (o, e) => { s.StartMinimized = false; ctl.MarkDirty(); };
            win.Children.Add(PageKit.Row("Start minimized", "Open straight to the tray icon.", minSw));
            root.Children.Add(Ui.Card(win));

            root.Children.Add(PageKit.Section("Startup"));
            var st = new StackPanel();
            var boot = Ui.Switch("", StartupReg.Get());
            boot.Checked += (o, e) => SetBoot(true);
            boot.Unchecked += (o, e) => SetBoot(false);
            st.Children.Add(PageKit.Row("Run when Windows starts", "Starts quietly in the tray after you sign in.", boot));
            st.Children.Add(PageKit.Divider());
            var auto = Ui.Switch("", s.AutoStart);
            auto.Checked += (o, e) => { s.AutoStart = true; ctl.MarkDirty(); };
            auto.Unchecked += (o, e) => { s.AutoStart = false; ctl.MarkDirty(); };
            st.Children.Add(PageKit.Row("Start mirroring automatically", "Begin as soon as the app opens.", auto));
            root.Children.Add(Ui.Card(st));

            root.Children.Add(PageKit.Section("Shortcut"));
            var hk = Ui.Switch("", s.Hotkey);
            hk.Checked += (o, e) => { s.Hotkey = true; ctl.MarkDirty(); onHotkeyChanged(); };
            hk.Unchecked += (o, e) => { s.Hotkey = false; ctl.MarkDirty(); onHotkeyChanged(); };
            root.Children.Add(Ui.Card(PageKit.Row("Ctrl + Alt + M", "Start or stop mirroring from anywhere.", hk)));

            root.Children.Add(PageKit.Section("Data"));
            var data = new StackPanel();
            var open = Ui.Button("Open folder", "Ghost", (o, e) => PageKit.OpenFolder(AppInfo.DataDir));
            data.Children.Add(PageKit.Row("Settings and logs", AppInfo.DataDir, open));
            data.Children.Add(PageKit.Divider());
            var reset = Ui.Button("Reset", "Ghost", (o, e) =>
            {
                var r = MessageBox.Show("Reset all settings to defaults? Your phone link key will change too.", "Dual Earbuds", MessageBoxButton.OKCancel, MessageBoxImage.Warning);
                if (r == MessageBoxResult.OK) onReset();
            });
            data.Children.Add(PageKit.Row("Reset all settings", "Back to how the app was on first start.", reset));
            root.Children.Add(Ui.Card(data));
        }

        static void SetBoot(bool on)
        {
            try { StartupReg.Set(on, System.Reflection.Assembly.GetEntryAssembly().Location); }
            catch (Exception ex) { MessageBox.Show("Could not change the startup setting: " + ex.Message, "Dual Earbuds"); }
        }

        public void Rebuild() { }
        public void Refresh() { }
        public void Animate() { }
    }

    // ---------------------------------------------------------------------- Diagnostics
    class DiagnosticsPage : IPage
    {
        readonly StackPanel root;
        readonly TextBox report;
        readonly Button run;
        public FrameworkElement Root { get { return root; } }

        public DiagnosticsPage(Controller ctl)
        {
            root = PageKit.Column();
            root.Children.Add(PageKit.Header("Diagnostics", "Check what Windows allows on each output. Nothing is played while checking."));
            var bar = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 14) };
            run = Ui.Button("Run check", "Primary", null);
            run.Click += (s, e) =>
            {
                run.IsEnabled = false;
                report.Text = "Checking outputs...";
                var t = new Thread(() =>
                {
                    string text;
                    try { text = Diagnostics.BuildReport(ctl.Engine, ctl.Phone, ctl.Settings); }
                    catch (Exception ex) { text = "The check failed: " + ex.Message; }
                    root.Dispatcher.BeginInvoke(new Action(() => { report.Text = text; run.IsEnabled = true; }));
                });
                t.IsBackground = true;
                t.SetApartmentState(ApartmentState.MTA);
                t.Start();
            };
            var copy = Ui.Button("Copy", "Ghost", (s, e) => { try { if (report.Text.Length > 0) Clipboard.SetText(report.Text); } catch { } });
            var save = Ui.Button("Save report", "Ghost", (s, e) => Save());
            var logs = Ui.Button("Open logs", "Ghost", (s, e) => PageKit.OpenFolder(Log.Dir));
            foreach (var b in new[] { run, copy, save, logs }) { b.Margin = new Thickness(0, 0, 8, 0); bar.Children.Add(b); }
            root.Children.Add(bar);
            report = new TextBox
            {
                IsReadOnly = true, FontFamily = Theme.Mono, FontSize = 12, MinHeight = 360, TextWrapping = TextWrapping.NoWrap,
                HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                Text = "Press Run check. Send the report when asking for help - your phone link key is not included."
            };
            root.Children.Add(report);
        }

        void Save()
        {
            var dlg = new Microsoft.Win32.SaveFileDialog { FileName = "dual-earbuds-report.txt", Filter = "Text file|*.txt" };
            if (dlg.ShowDialog() == true)
            {
                try { File.WriteAllText(dlg.FileName, report.Text); }
                catch (Exception ex) { MessageBox.Show("Could not save: " + ex.Message, "Dual Earbuds"); }
            }
        }

        public void Rebuild() { }
        public void Refresh() { }
        public void Animate() { }
    }

    // ---------------------------------------------------------------------- About
    class AboutPage : IPage
    {
        readonly StackPanel root;
        public FrameworkElement Root { get { return root; } }

        public AboutPage()
        {
            root = PageKit.Column();
            var head = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 4, 0, 24) };
            head.Children.Add(Ui.Logo(64));
            var t = new StackPanel { Margin = new Thickness(18, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            t.Children.Add(Ui.Heading("Dual Earbuds", 28));
            t.Children.Add(Ui.Text("Version " + AppInfo.Version + " · free and open source (MIT)", 13.5, FontWeights.Normal, "Muted"));
            head.Children.Add(t);
            root.Children.Add(head);

            root.Children.Add(PageKit.Section("How it works"));
            var g = new UniformGrid3();
            g.Add("1  Capture", "Dual Earbuds listens to what Windows plays on your main output - silently, with Windows' own audio engine.");
            g.Add("2  Copy", "It plays the same sound on your other earbuds and speakers, each with its own delay and volume.");
            g.Add("3  Stream", "Earbuds the laptop can't reach? It streams over Wi-Fi to a phone they're paired with.");
            root.Children.Add(g.Root);

            root.Children.Add(PageKit.Section("Credits"));
            root.Children.Add(Ui.Card(Ui.Text("Made by Qasim Raza Malik. Built with Windows Core Audio (WASAPI), WPF and a small built-in web server. No data leaves your network.", 13.5, FontWeights.Normal, "Text")));
        }

        public void Rebuild() { }
        public void Refresh() { }
        public void Animate() { }

        class UniformGrid3
        {
            public readonly Grid Root = new Grid();
            int n;
            public UniformGrid3()
            {
                for (int i = 0; i < 3; i++)
                {
                    Root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                    if (i < 2) Root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(14) });
                }
            }
            public void Add(string title, string body)
            {
                var sp = new StackPanel();
                sp.Children.Add(Ui.Heading(title, 16));
                var b = Ui.Text(body, 13, FontWeights.Normal, "Muted");
                b.Margin = new Thickness(0, 6, 0, 0);
                sp.Children.Add(b);
                var card = Ui.Card(sp);
                Grid.SetColumn(card, n * 2);
                Root.Children.Add(card);
                n++;
            }
        }
    }
}
