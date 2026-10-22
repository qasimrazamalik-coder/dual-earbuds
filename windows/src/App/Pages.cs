// Dual Earbuds - pages, built from the "instrument" kit (Kit.cs): a header bar above (MainView), then
// sections with a hairline, rows inside panels, one primary action per view.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
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
        void Rebuild();   // device or listener list changed
        void Refresh();   // states changed
        void Animate();   // ~20 times a second (meters, scopes)
    }

    // Small helpers kept from the first design; they now produce the kit's look.
    static class PageKit
    {
        public static FrameworkElement Divider() { return new RowPanel.Divider(); }

        public static Border Segmented(string group, string[] labels, int selected, Action<int> onPick) { return Kit.Segmented(group, labels, selected, onPick); }

        public static Border Segmented(string group, string[] labels, int selected, Action<int> onPick, out RadioButton[] buttons) { return Kit.Segmented(group, labels, selected, onPick, out buttons); }

        public static ComboBox Combo(double width) { return Kit.Combo(width); }

        public static void SelectTag(ComboBox c, string tag)
        {
            foreach (var o in c.Items) { var it = o as ComboBoxItem; if (it != null && (string)it.Tag == tag) { c.SelectedItem = it; return; } }
            if (c.Items.Count > 0) c.SelectedIndex = 0;
        }

        public static string Tag(ComboBox c)
        {
            var it = c.SelectedItem as ComboBoxItem;
            return it == null ? null : it.Tag as string;
        }

        public static void OpenFolder(string dir)
        {
            try { Directory.CreateDirectory(dir); Process.Start("explorer.exe", "\"" + dir + "\""); }
            catch (Exception ex) { Log.Warn("Open folder: " + ex.Message); }
        }

        // The state block at the top of a view: one 20 px headline, one muted line, an action on the right.
        public static Grid StateRow(out TextBlock headline, out TextBlock line, out Ellipse dot, FrameworkElement action)
        {
            var g = new Grid { Margin = new Thickness(16, 14, 16, 14) };
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var left = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            var top = new StackPanel { Orientation = Orientation.Horizontal };
            dot = new Ellipse { Width = 8, Height = 8, Margin = new Thickness(0, 0, 10, 0), VerticalAlignment = VerticalAlignment.Center };
            top.Children.Add(dot);
            headline = Ui.Text("", 20, FontWeights.SemiBold, "Text");
            headline.TextWrapping = TextWrapping.NoWrap;
            headline.TextTrimming = TextTrimming.CharacterEllipsis;
            top.Children.Add(headline);
            left.Children.Add(top);
            line = Ui.Text("", 12.5, FontWeights.Normal, "Muted");
            line.Margin = new Thickness(18, 4, 0, 0);
            left.Children.Add(line);
            g.Children.Add(left);
            if (action != null)
            {
                action.VerticalAlignment = VerticalAlignment.Center;
                action.Margin = new Thickness(16, 0, 0, 0);
                Grid.SetColumn(action, 1);
                g.Children.Add(action);
            }
            return g;
        }

        public static UIElement EmptyRow(string text)
        {
            var t = Ui.Text(text, 13, FontWeights.Normal, "Muted");
            t.Margin = new Thickness(16, 16, 16, 16);
            return t;
        }
    }

    // ---------------------------------------------------------------------- Home
    // One local output: its ear row (switch, name, mono detail, sync scope) and, while it is on, one line of
    // adjustments (Delay, Volume, EQ, hearing profile).
    class OutputRow
    {
        public readonly StackPanel Root = new StackPanel();
        readonly OutputVm vm;
        readonly Controller ctl;
        readonly EarRow ear;
        readonly CheckBox sw;
        readonly StackPanel battery;
        readonly TextBlock batteryText, error, note, delayVal, volVal;
        readonly Path batteryIcon;
        readonly Slider delay, volume;
        readonly Button test, calibrate;
        readonly ComboBox eq, hearing;
        readonly WrapPanel adjust;
        readonly Meter meter;
        string comboSig;
        bool updating;

        public string Id { get { return vm.Id; } }

        public OutputRow(OutputVm vm, Controller ctl)
        {
            this.vm = vm;
            this.ctl = ctl;
            ear = new EarRow(vm.Name.StartsWith("Speaker", StringComparison.OrdinalIgnoreCase) ? Icons.Speaker : Icons.Headphones, vm.Name);
            sw = Ui.Switch("", vm.Enabled);
            sw.Checked += (s, e) => { if (!updating) ctl.SetEnabled(vm, true); };
            sw.Unchecked += (s, e) => { if (!updating) ctl.SetEnabled(vm, false); };
            ear.Lead.Children.Add(sw);
            var right = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            battery = Ui.Battery(out batteryText, out batteryIcon);
            battery.Margin = new Thickness(0, 0, 8, 0);
            right.Children.Add(battery);
            test = Kit.Quiet("Test", (s, e) => ctl.Test(vm));
            test.ToolTip = "Play a short beep on this output";
            right.Children.Add(test);
            calibrate = Kit.Quiet("Calibrate", (s, e) => Calibrate());
            calibrate.ToolTip = "Measure this output's delay with the laptop microphone";
            var forget = new MenuItem { Header = "Forget the measurement" };
            forget.Click += (s, e) => { ctl.ForgetCalibration(vm); note.Visibility = Visibility.Collapsed; Refresh(); };
            calibrate.ContextMenu = new ContextMenu();
            calibrate.ContextMenu.Items.Add(forget);
            right.Children.Add(calibrate);
            ear.Extra.Children.Add(right);
            Root.Children.Add(ear.Root);
            meter = new Meter { Height = 2, Margin = new Thickness(62, -6, 16, 6), Opacity = 0.9 };
            Root.Children.Add(meter);

            // adjustments: fixed widths so every row lines up; they wrap on a narrow window
            adjust = new WrapPanel { Margin = new Thickness(62, 0, 16, 8) };
            var d = Ui.LabeledSlider("Delay", 0, 500, vm.DelayMs, " ms", out delay, out delayVal);
            d.Width = 230;
            d.Margin = new Thickness(0, 0, 24, 4);
            delay.ToolTip = "Extra delay on top of the shared deadline. Usually 0: calibrate instead.";
            delay.ValueChanged += (s, e) =>
            {
                int ms = (int)(Math.Round(delay.Value / 5) * 5);
                delayVal.Text = ms + " ms";
                if (!updating) ctl.SetDelay(vm, ms);
            };
            adjust.Children.Add(d);
            var v = Ui.LabeledSlider("Volume", 0, 150, vm.Volume, "%", out volume, out volVal);
            v.Width = 230;
            v.Margin = new Thickness(0, 0, 24, 4);
            volume.ValueChanged += (s, e) =>
            {
                int vol = (int)Math.Round(volume.Value);
                volVal.Text = vol + "%";
                if (!updating) ctl.SetVolume(vm, vol);
            };
            adjust.Children.Add(v);
            eq = Kit.Combo(128);
            eq.Margin = new Thickness(0, 0, 8, 4);
            eq.ToolTip = "Equalizer for this output";
            eq.SelectionChanged += (s, e) => { if (!updating) { string t = PageKit.Tag(eq); if (t != null) ctl.SetOutputEq(vm, t); } };
            adjust.Children.Add(eq);
            hearing = Kit.Combo(150);
            hearing.Margin = new Thickness(0, 0, 0, 4);
            hearing.ToolTip = "Hearing profile of the person wearing these";
            hearing.SelectionChanged += (s, e) => { if (!updating) ctl.SetOutputProfile(vm, PageKit.Tag(hearing) ?? ""); };
            adjust.Children.Add(hearing);
            Root.Children.Add(adjust);

            note = Ui.Text("", 12, FontWeights.Normal, "Muted");
            note.Margin = new Thickness(62, 0, 16, 12);
            note.Visibility = Visibility.Collapsed;
            Root.Children.Add(note);
            error = Ui.Text("", 12, FontWeights.Normal, "Bad");
            error.Margin = new Thickness(62, 0, 16, 12);
            error.Visibility = Visibility.Collapsed;
            Root.Children.Add(error);
            Refresh();
        }

        void Calibrate()
        {
            var r = MessageBox.Show("Hold one earbud of " + vm.Name + " against the laptop's microphone, in a quiet room.\n\n" +
                                    "Press OK and keep it still. 8 clicks play over about 7 seconds; the measured delay lines this pair up with every other ear.",
                                    "Calibrate", MessageBoxButton.OKCancel, MessageBoxImage.Information);
            if (r != MessageBoxResult.OK) return;
            note.Visibility = Visibility.Collapsed;
            ctl.Calibrate(vm, res =>
            {
                note.Text = res.Ok ? "Measured " + Math.Round(res.LatencyMs) + " ms (" + res.Agreeing + " of 8 clicks agreed)." : res.Problem;
                note.SetResourceReference(TextBlock.ForegroundProperty, res.Ok ? "Muted" : "Warn");
                note.Visibility = Visibility.Visible;
                Refresh();
            });
            Refresh();
        }

        void FillCombos()
        {
            var profiles = ctl.Settings.Profiles;
            string sig = ctl.SmartEqOn + "|" + string.Join(",", profiles.ConvertAll(p => p.Name).ToArray());
            if (sig == comboSig) return;
            comboSig = sig;
            eq.Items.Clear();
            if (ctl.SmartEqOn) eq.Items.Add(new ComboBoxItem { Content = "EQ auto", Tag = "auto" });
            for (int i = 0; i < EqPresets.Names.Length; i++) eq.Items.Add(new ComboBoxItem { Content = "EQ " + EqPresets.Labels[i].ToLowerInvariant(), Tag = EqPresets.Names[i] });
            hearing.Items.Clear();
            hearing.Items.Add(new ComboBoxItem { Content = "No hearing profile", Tag = "" });
            foreach (var p in profiles) hearing.Items.Add(new ComboBoxItem { Content = "Hearing: " + p.Name, Tag = p.Name });
            hearing.Visibility = profiles.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        }

        public void Refresh()
        {
            updating = true;
            FillCombos();
            var os = ctl.Settings.For(vm.Id);
            PageKit.SelectTag(eq, os.EqAuto && ctl.SmartEqOn ? "auto" : os.Eq);
            PageKit.SelectTag(hearing, os.Profile);
            bool active = vm.Enabled && !vm.IsSource;
            sw.IsChecked = vm.Enabled;
            sw.IsEnabled = !vm.IsSource;
            sw.ToolTip = vm.IsSource ? "The sound comes from here" : null;
            calibrate.IsEnabled = !vm.IsSource && !vm.Calibrating;
            calibrate.Content = vm.Calibrating ? "Listening" : vm.LatencyMeasured ? "Recalibrate" : "Calibrate";
            test.IsEnabled = !vm.IsSource;
            Ui.SetBattery(battery, batteryText, batteryIcon, vm.Battery);
            adjust.Visibility = active ? Visibility.Visible : Visibility.Collapsed;
            meter.Visibility = vm.Kind == StateKind.Playing || vm.IsSource ? Visibility.Visible : Visibility.Hidden;
            string sub;
            if (vm.IsSource) sub = "source · plays without delay";
            else if (vm.Calibrating) sub = "listening for the clicks";
            else
            {
                string lat = vm.LatencyMeasured ? Kit.Ms(vm.LatencyMs) + " measured" : "~" + Kit.Ms(vm.EffectiveLatencyMs) + " estimated";
                string state = vm.Kind == StateKind.Playing ? "playing" : vm.State.ToLowerInvariant();
                sub = state + " · " + lat + (active ? " · +" + vm.ExtraDelayMs + " ms to P" : "") + (vm.DelayMs > 0 ? " · delay " + vm.DelayMs + " ms" : "") +
                      (os.EqAuto && ctl.SmartEqOn && active ? " · EQ " + vm.Effects.EffectivePreset : "");
            }
            ear.Update(vm.Name, sub, ctl.LocalOffsetMs(vm));
            ear.Dim(!vm.Enabled && !vm.IsSource);
            if (vm.Error != null) { error.Text = vm.Error; error.Visibility = Visibility.Visible; }
            else error.Visibility = Visibility.Collapsed;
            updating = false;
        }

        public void Animate() { meter.Level = vm.IsSource ? ctl.Level : vm.Level; }
    }

    // A remote listener as an ear (Home shows it read-only; Listeners adds volume and Remove).
    class ListenerRow
    {
        public readonly StackPanel Root = new StackPanel();
        public readonly string Id;
        readonly EarRow ear;
        readonly StackPanel bat, controls;
        readonly TextBlock batText, volVal;
        readonly Path batIcon;
        readonly Slider volume;
        readonly bool editable;
        DateTime userChangedAt = DateTime.MinValue;
        bool updating;

        public ListenerRow(PhoneInfo p, Controller ctl, Action<string> remove, bool editable)
        {
            Id = p.Id;
            this.editable = editable;
            ear = new EarRow(IconFor(p), p.Name);
            var right = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            bat = Ui.Battery(out batText, out batIcon);
            bat.Margin = new Thickness(0, 0, 8, 0);
            right.Children.Add(bat);
            controls = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(62, -4, 16, 12) };
            if (editable)
            {
                var removeBtn = Kit.Quiet("Remove", (s, e) => remove(Id));
                removeBtn.ToolTip = "Disconnect this listener. It won't rejoin by itself.";
                right.Children.Add(removeBtn);
                // Party mode: a volume per listener, on its own line so every row lines up
                var vs = Ui.LabeledSlider("Volume", 0, PhoneLink.MaxListenerVolume, p.Volume, "%", out volume, out volVal);
                vs.Width = 230;
                volume.ToolTip = "This listener's volume (sent to the device)";
                volume.ValueChanged += (s, e) =>
                {
                    int v = (int)Math.Round(volume.Value);
                    volVal.Text = v + "%";
                    if (updating) return;
                    userChangedAt = DateTime.Now;
                    ctl.SetPhoneVolume(Id, v);
                };
                controls.Children.Add(vs);
            }
            ear.Extra.Children.Add(right);
            Root.Children.Add(ear.Root);
            if (editable) Root.Children.Add(controls);
            Update(p, ctl.Party);
        }

        public static string IconFor(PhoneInfo p) { return p.Client == "windows" ? Icons.Laptop : p.Client == "android" ? Icons.Phone : Icons.Globe; }

        public static string Describe(PhoneInfo p, bool withVolume)
        {
            var parts = new List<string>();
            parts.Add(p.Client == "android" ? "phone" : p.Client == "windows" ? "laptop" : "browser");
            parts.Add(p.Transport == "udp" ? "UDP" : "WebSocket");
            if (p.RttMs >= 0) parts.Add("rtt " + p.RttMs + " ms");
            if (p.NeedMs > 0) parts.Add("needs " + p.NeedMs + " ms");
            else if (p.BufferMs > 0) parts.Add("buffer " + p.BufferMs + " ms");
            if (!p.Synced) parts.Add("not synced");
            if (p.Underruns > 0) parts.Add(p.Underruns + (p.Underruns == 1 ? " dropout" : " dropouts"));
            if (!string.IsNullOrEmpty(p.Earbuds)) parts.Add(p.Earbuds);
            if (withVolume && p.Volume != 100) parts.Add("vol " + p.Volume + "%");
            return string.Join(" · ", parts.ToArray());
        }

        public void Update(PhoneInfo p, bool party)
        {
            updating = true;
            ear.SetIcon(IconFor(p));
            ear.Update(p.Name, Describe(p, !(editable && party)), Controller.PhoneOffsetMs(p));
            Ui.SetBattery(bat, batText, batIcon, p.Battery);
            if (editable)
            {
                // volume only in Party mode; Remove always
                controls.Visibility = party ? Visibility.Visible : Visibility.Collapsed;
                bool dragging = volume.IsMouseCaptureWithin || (DateTime.Now - userChangedAt).TotalSeconds < 3;
                if (!dragging && Math.Abs(volume.Value - p.Volume) >= 1) volume.Value = p.Volume;
            }
            updating = false;
        }
    }

    class HomePage : IPage
    {
        readonly Controller ctl;
        readonly Action<string> navigate;
        readonly StackPanel root;
        readonly Button startBtn;
        readonly TextBlock headline, statusLine, outCount, lisCount;
        readonly Ellipse statusDot;
        readonly Meter heroMeter;
        readonly ComboBox source, apps;
        readonly Grid appRow, sourceRow;
        readonly TextBlock appNote;
        readonly RadioButton[] modeButtons;
        readonly RowPanel outputs = new RowPanel(), listeners = new RowPanel();
        readonly List<OutputRow> outRows = new List<OutputRow>();
        readonly Dictionary<string, ListenerRow> lisRows = new Dictionary<string, ListenerRow>();
        bool suppress;

        public FrameworkElement Root { get { return root; } }

        public HomePage(Controller ctl, Action<string> navigate)
        {
            this.ctl = ctl;
            this.navigate = navigate;
            root = Kit.Page();

            // state
            var state = new RowPanel();
            startBtn = Kit.Primary("Start sharing", (s, e) => ctl.Toggle());
            startBtn.MinWidth = 120;
            startBtn.ToolTip = "Ctrl + Alt + M";
            var top = PageKit.StateRow(out headline, out statusLine, out statusDot, startBtn);
            var stateBody = new StackPanel();
            stateBody.Children.Add(top);
            heroMeter = new Meter { Margin = new Thickness(34, -4, 16, 14) };
            stateBody.Children.Add(heroMeter);
            state.Add(stateBody);
            state.Root.Margin = new Thickness(0, 20, 0, 0);
            root.Children.Add(state.Root);

            // source
            root.Children.Add(Kit.Section("Source"));
            var src = new RowPanel();
            var modeSeg = Kit.Segmented("srcmode", new[] { "Whole laptop", "One app" }, ctl.Settings.SourceMode == "app" && ctl.AppSupported ? 1 : 0, i =>
            {
                if (suppress) return;
                if (i == 1) { ctl.RefreshApps(FillApps); ctl.SetSourceMode("app", null); }
                else ctl.SetSourceMode("all", null);
                Refresh();
            }, out modeButtons);
            if (!ctl.AppSupported) { modeButtons[1].IsEnabled = false; modeButtons[1].ToolTip = AppAudio.WhyNot; }
            src.Add(Kit.Row("Share", null, modeSeg));
            source = Kit.Combo(340);
            source.SelectionChanged += (s, e) =>
            {
                if (suppress) return;
                var it = source.SelectedItem as ComboBoxItem;
                ctl.SetSource(it == null ? "" : (string)it.Tag);
            };
            sourceRow = Kit.Row("From", "What this output plays is captured. It plays without the shared delay.", source);
            src.Add(sourceRow);
            var appCtl = new StackPanel { Orientation = Orientation.Horizontal };
            apps = Kit.Combo(300);
            apps.SelectionChanged += (s, e) =>
            {
                if (suppress) return;
                string t = PageKit.Tag(apps);
                if (!string.IsNullOrEmpty(t) && t != ctl.Settings.SourceApp) ctl.SetSourceMode("app", t);
            };
            appCtl.Children.Add(apps);
            var refreshApps = Kit.IconButton(Icons.Refresh, "Look again for apps playing sound", (s, e) => ctl.RefreshApps(FillApps));
            refreshApps.Margin = new Thickness(4, 0, 0, 0);
            appCtl.Children.Add(refreshApps);
            appRow = Kit.Row("App", "", appCtl, out appNote);
            src.Add(appRow);
            root.Children.Add(src.Root);

            // outputs
            root.Children.Add(Kit.Section("Outputs", out outCount, null));
            root.Children.Add(outputs.Root);

            // listeners
            root.Children.Add(Kit.Section("Listeners", out lisCount, Kit.Quiet("Link and QR code", (s, e) => navigate("phones"))));
            root.Children.Add(listeners.Root);

            Rebuild();
            if (ctl.Settings.SourceMode == "app" && ctl.AppSupported) ctl.RefreshApps(FillApps);
        }

        void FillApps()
        {
            suppress = true;
            apps.Items.Clear();
            string cur = ctl.Settings.SourceApp;
            bool found = false;
            foreach (var a in ctl.Apps)
            {
                var it = new ComboBoxItem { Content = a.Title + (a.Active ? "  · playing" : ""), Tag = a.Process };
                apps.Items.Add(it);
                if (string.Equals(a.Process, cur, StringComparison.OrdinalIgnoreCase)) { apps.SelectedItem = it; found = true; }
            }
            if (!found && cur != "")
            {
                var it = new ComboBoxItem { Content = AppAudio.Pretty(cur) + "  · not playing", Tag = cur };
                apps.Items.Insert(0, it);
                apps.SelectedItem = it;
            }
            if (apps.Items.Count == 0) apps.Items.Add(new ComboBoxItem { Content = "No app plays sound. Play something, then refresh.", Tag = "", IsEnabled = false });
            suppress = false;
            Refresh();
        }

        public void Rebuild()
        {
            suppress = true;
            source.Items.Clear();
            var def = new ComboBoxItem { Content = "Windows default (" + ctl.NameOf(ctl.DefaultId) + ")", Tag = "" };
            source.Items.Add(def);
            source.SelectedItem = def;
            foreach (var o in ctl.Outputs)
            {
                var it = new ComboBoxItem { Content = o.Name, Tag = o.Id };
                source.Items.Add(it);
                if (o.Id == ctl.Settings.SourceId) source.SelectedItem = it;
            }
            suppress = false;

            outputs.Clear();
            outRows.Clear();
            foreach (var o in ctl.Outputs)
            {
                var r = new OutputRow(o, ctl);
                outRows.Add(r);
                outputs.Add(r.Root);
            }
            if (ctl.Outputs.Count <= 1)
                outputs.Add(PageKit.EmptyRow("No other earbuds on this laptop. Pair them in Windows Bluetooth settings and they appear here. If Windows plays to only one pair, the second pair can listen through a phone."));
            RebuildListeners();
            Refresh();
        }

        void RebuildListeners()
        {
            listeners.Clear();
            lisRows.Clear();
            foreach (var p in ctl.Phones)
            {
                var r = new ListenerRow(p, ctl, null, false);
                lisRows[p.Id ?? ""] = r;
                listeners.Add(r.Root);
            }
            if (ctl.Phones.Count == 0) listeners.Add(PageKit.EmptyRow(ctl.Settings.PhoneLink ? "No one is listening. Phones, laptops and browsers join with the link on the Listeners page." : "The link is off, so no one can listen."));
        }

        public void Refresh()
        {
            bool on = ctl.Running || ctl.WantRunning;
            startBtn.Content = on ? "Stop sharing" : "Start sharing";
            startBtn.SetResourceReference(FrameworkElement.StyleProperty, on ? "Ghost" : "Primary");
            startBtn.IsEnabled = !ctl.Listening;
            int ears = ctl.EarCount;
            headline.Text = ctl.Listening ? "Listening to " + ctl.ListenHostName
                : ctl.Running ? (ears == 0 ? "Sharing, no ears yet" : "Sharing to " + ears + (ears == 1 ? " ear" : " ears"))
                : ctl.WantRunning ? "Waiting for sound" : "Not sharing";
            statusDot.SetResourceReference(Shape.FillProperty, ctl.Running ? (ctl.StatusKind == StateKind.Problem ? "Warn" : "Accent") : ctl.WantRunning ? "Warn" : "Muted");
            statusLine.Text = ctl.Listening ? "This laptop plays another device's sound. Stop listening on the Listen page to share again." : ctl.StatusText;

            bool appMode = ctl.Settings.SourceMode == "app" && ctl.AppSupported;
            suppress = true;
            modeButtons[appMode ? 1 : 0].IsChecked = true;
            suppress = false;
            sourceRow.Visibility = appMode ? Visibility.Collapsed : Visibility.Visible;
            appRow.Visibility = appMode ? Visibility.Visible : Visibility.Collapsed;
            string note = !ctl.AppSupported ? AppAudio.WhyNot
                : ctl.Settings.SourceApp == "" ? "Pick the app. Until then the whole laptop is shared."
                : "Only " + ctl.SourceDescription + " is shared, with its child windows. Calls and notifications stay here.";
            appNote.Text = note;
            appNote.Visibility = Visibility.Visible;

            int onCount = 0, total = 0;
            foreach (var o in ctl.Outputs) { if (o.IsSource) continue; total++; if (o.Enabled) onCount++; }
            outCount.Text = onCount + " of " + total + " on";
            foreach (var r in outRows) r.Refresh();

            // listener rows are kept by id and updated in place
            bool same = ctl.Phones.Count == lisRows.Count;
            if (same) foreach (var p in ctl.Phones) if (!lisRows.ContainsKey(p.Id ?? "")) { same = false; break; }
            if (!same) RebuildListeners();
            else foreach (var p in ctl.Phones) lisRows[p.Id ?? ""].Update(p, ctl.Party);
            lisCount.Text = ctl.Phones.Count + (ctl.Phones.Count > 0 ? " · P " + ctl.PlayoutMs + " ms" : "");
        }

        public void Animate()
        {
            heroMeter.Level = ctl.Level;
            foreach (var r in outRows) r.Animate();
        }
    }

    // ---------------------------------------------------------------------- Listeners (the link)
    class PhonesPage : IPage
    {
        readonly Controller ctl;
        readonly StackPanel root;
        readonly ColumnDefinition qrColumn;
        readonly Border qrHost;
        readonly TextBox link;
        readonly TextBlock linkState, listCount, pinBig, talkHint, hotTitle, hotText;
        readonly StackPanel pinShow;
        readonly CheckBox enable, party, pinSwitch, remoteSw;
        readonly TextBox port, pinBox;
        readonly Grid pinRow, talkRow;
        readonly Border hotspot;
        readonly Button talkBtn;
        readonly ComboBox suggest;
        readonly RowPanel list = new RowPanel();
        readonly Dictionary<string, ListenerRow> rows = new Dictionary<string, ListenerRow>();
        UIElement empty;
        bool built, talkHeld;
        string shownUrl = "\0";
        bool shownParty;
        bool updating;

        const double QrNormal = 216, QrParty = 264;

        public FrameworkElement Root { get { return root; } }

        public PhonesPage(Controller ctl)
        {
            this.ctl = ctl;
            root = Kit.Page();

            var grid = new Grid { Margin = new Thickness(0, 20, 0, 0) };
            qrColumn = new ColumnDefinition { Width = new GridLength(268) };
            grid.ColumnDefinitions.Add(qrColumn);
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(20) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            // the link: QR, the URL in mono, Copy link (the one primary action here)
            var qrCol = new StackPanel { Margin = new Thickness(16) };
            var cap = Ui.Text("Scan or open the link", 12, FontWeights.SemiBold, "Text");
            cap.Margin = new Thickness(0, 0, 0, 10);
            qrCol.Children.Add(cap);
            qrHost = new Border { Width = QrNormal, Height = QrNormal, CornerRadius = new CornerRadius(6), HorizontalAlignment = HorizontalAlignment.Left, Padding = new Thickness(4) };
            qrHost.SetResourceReference(Border.BackgroundProperty, "QrBg");
            qrCol.Children.Add(qrHost);
            link = new TextBox { IsReadOnly = true, FontFamily = Theme.Mono, FontSize = 12, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 12, 0, 0) };
            qrCol.Children.Add(link);
            var btns = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 10, 0, 0) };
            var copy = Kit.Primary("Copy link", (s, e) => { try { if (!string.IsNullOrEmpty(link.Text)) Clipboard.SetText(link.Text); } catch { } });
            btns.Children.Add(copy);
            var reset = Kit.Quiet("New link", (s, e) =>
            {
                var r = MessageBox.Show("Make a new link? Everyone on the old link is disconnected and has to join again.", "Dual Earbuds", MessageBoxButton.OKCancel, MessageBoxImage.Question);
                if (r == MessageBoxResult.OK) { ctl.ResetLinkKey(); Rebuild(); }
            });
            reset.ToolTip = "A new secret key, for when someone else has your link";
            reset.Margin = new Thickness(6, 0, 0, 0);
            btns.Children.Add(reset);
            qrCol.Children.Add(btns);
            pinShow = new StackPanel { Margin = new Thickness(0, 16, 0, 0), Visibility = Visibility.Collapsed };
            pinShow.Children.Add(Ui.Text("Room PIN", 12, FontWeights.SemiBold, "Text"));
            pinBig = Ui.Mono("", 30, "Text");
            pinBig.FontWeight = FontWeights.SemiBold;
            pinShow.Children.Add(pinBig);
            pinShow.Children.Add(Kit.Hint("Say it out loud. It isn't in the link or the QR code."));
            qrCol.Children.Add(pinShow);
            var qrPanel = new RowPanel();
            qrPanel.Add(qrCol);
            qrPanel.Root.VerticalAlignment = VerticalAlignment.Top;
            grid.Children.Add(qrPanel.Root);

            var right = new StackPanel();
            // hotspot help: on public networks, or when no one joined within 60 s. The app changes no system setting.
            var hotPanel = new RowPanel();
            var hotBody = new StackPanel { Margin = new Thickness(16, 12, 16, 12) };
            var ht = new StackPanel { Orientation = Orientation.Horizontal };
            var hd = new Ellipse { Width = 6, Height = 6, Margin = new Thickness(0, 0, 8, 0), VerticalAlignment = VerticalAlignment.Center };
            hd.SetResourceReference(Shape.FillProperty, "Warn");
            ht.Children.Add(hd);
            hotTitle = Ui.Text("", 14, FontWeights.SemiBold, "Text");
            ht.Children.Add(hotTitle);
            hotBody.Children.Add(ht);
            hotText = Kit.Hint("");
            hotText.Margin = new Thickness(14, 4, 0, 8);
            hotBody.Children.Add(hotText);
            var hotBtn = Kit.Secondary("Mobile hotspot settings", (s1, e1) => Controller.OpenHotspotSettings());
            hotBtn.HorizontalAlignment = HorizontalAlignment.Left;
            hotBtn.Margin = new Thickness(14, 0, 0, 0);
            hotBody.Children.Add(hotBtn);
            hotPanel.Add(hotBody);
            hotspot = hotPanel.Root;
            hotspot.Margin = new Thickness(0, 0, 0, 4);
            hotspot.Visibility = Visibility.Collapsed;
            right.Children.Add(hotspot);

            var listHead = Kit.Section("Listening", out listCount, null);
            listHead.Margin = new Thickness(0, 0, 0, 8);
            right.Children.Add(listHead);
            right.Children.Add(list.Root);

            right.Children.Add(Kit.Section("Link"));
            var set = new RowPanel();
            enable = Ui.Switch("", ctl.Settings.PhoneLink);
            enable.Checked += (s, e) => { if (!updating) { ctl.SetPhoneLink(true); Rebuild(); } };
            enable.Unchecked += (s, e) => { if (!updating) { ctl.SetPhoneLink(false); Rebuild(); } };
            set.Add(Kit.Row("Let devices listen", "Phones, laptops and browsers on this Wi-Fi or hotspot. The link's secret key keeps others out.", enable));
            party = Ui.Switch("", ctl.Party);
            party.Checked += (s, e) => { if (!updating) { ctl.SetParty(true); Rebuild(); } };
            party.Unchecked += (s, e) => { if (!updating) { ctl.SetParty(false); Rebuild(); } };
            set.Add(Kit.Row("Party mode", "16 listeners instead of 8, a bigger QR code and a volume per listener.", party));
            pinSwitch = Ui.Switch("", ctl.Settings.PinOn);
            pinSwitch.Checked += (s1, e1) => { if (!updating) { ctl.SetPinOn(true); Refresh(); } };
            pinSwitch.Unchecked += (s1, e1) => { if (!updating) { ctl.SetPinOn(false); Refresh(); } };
            var pinCtl = new StackPanel { Orientation = Orientation.Horizontal };
            pinBox = new TextBox { Width = 76, FontFamily = Theme.Mono, MaxLength = 6, Text = ctl.Settings.Pin, ToolTip = "4 to 6 digits" };
            pinBox.KeyDown += (s1, e1) => { if (e1.Key == Key.Enter) ApplyPin(); };
            pinCtl.Children.Add(pinBox);
            var pinApply = Kit.Quiet("Set", (s1, e1) => ApplyPin());
            pinApply.Margin = new Thickness(4, 0, 0, 0);
            pinCtl.Children.Add(pinApply);
            var pinNew = Kit.Quiet("New", (s1, e1) => { ctl.NewPin(); pinBox.Text = ctl.Settings.Pin; Refresh(); });
            pinNew.Margin = new Thickness(0, 0, 12, 0);
            pinCtl.Children.Add(pinNew);
            pinCtl.Children.Add(pinSwitch);
            pinRow = Kit.Row("Require a PIN", "Guests also type a 4-6 digit PIN. Wrong guesses lock that device out for 5 minutes.", pinCtl);
            set.Add(pinRow);
            var portRow = new StackPanel { Orientation = Orientation.Horizontal };
            port = new TextBox { Width = 76, Text = ctl.Settings.PhonePort.ToString(CultureInfo.InvariantCulture), FontFamily = Theme.Mono };
            port.KeyDown += (s, e) => { if (e.Key == Key.Enter) ApplyPort(); };
            portRow.Children.Add(port);
            var apply = Kit.Quiet("Apply", (s, e) => ApplyPort());
            apply.Margin = new Thickness(4, 0, 0, 0);
            portRow.Children.Add(apply);
            TextBlock portHint;
            set.Add(Kit.Row("Port", "", portRow, out portHint));
            linkState = portHint;
            remoteSw = Ui.Switch("", ctl.Settings.RemoteControl);
            remoteSw.Checked += (s1, e1) => { if (!updating) ctl.SetRemoteControl(true); };
            remoteSw.Unchecked += (s1, e1) => { if (!updating) ctl.SetRemoteControl(false); };
            set.Add(Kit.Row("Listeners control playback", "Play/pause, next, previous and volume from their device press the media keys here.", remoteSw));
            suggest = Kit.Combo(150);
            suggest.Items.Add(new ComboBoxItem { Content = "No suggestion", Tag = "" });
            for (int i = 0; i < EqPresets.Names.Length; i++) suggest.Items.Add(new ComboBoxItem { Content = EqPresets.Labels[i], Tag = EqPresets.Names[i] });
            PageKit.SelectTag(suggest, ctl.Settings.SuggestEq);
            suggest.SelectionChanged += (s1, e1) => { if (!updating) ctl.SetSuggestEq(PageKit.Tag(suggest)); };
            set.Add(Kit.Row("Suggest an EQ", "Listeners see it and choose whether to use it.", suggest));
            right.Children.Add(set.Root);

            // talk-over
            right.Children.Add(Kit.Section("Talk over the sound"));
            var talk = new RowPanel();
            var talkCtl = new StackPanel { Orientation = Orientation.Horizontal };
            var talkMode = Kit.Segmented("talkmode", new[] { "Hold", "Tap" }, ctl.Settings.TalkMode == "toggle" ? 1 : 0, i =>
            {
                if (ctl.Talking) ctl.SetTalking(false);
                ctl.Settings.TalkMode = i == 1 ? "toggle" : "hold";
                ctl.MarkDirty();
                Refresh();
            });
            talkMode.Margin = new Thickness(0, 0, 8, 0);
            talkCtl.Children.Add(talkMode);
            talkBtn = Kit.Secondary("Hold to talk", null);
            talkBtn.MinWidth = 110;
            talkBtn.PreviewMouseLeftButtonDown += (s1, e1) => { if (ctl.Settings.TalkMode == "hold") { talkHeld = true; talkBtn.CaptureMouse(); ctl.SetTalking(true); e1.Handled = true; Refresh(); } };
            talkBtn.PreviewMouseLeftButtonUp += (s1, e1) => { if (talkHeld) { talkHeld = false; talkBtn.ReleaseMouseCapture(); ctl.SetTalking(false); e1.Handled = true; Refresh(); } };
            talkBtn.LostMouseCapture += (s1, e1) => { if (talkHeld) { talkHeld = false; ctl.SetTalking(false); Refresh(); } };
            // mouse presses in hold mode are handled above; this is a tap in toggle mode, or Space/Enter in either
            talkBtn.Click += (s1, e1) => { ctl.SetTalking(!ctl.Talking); Refresh(); };
            talkCtl.Children.Add(talkBtn);
            talkRow = Kit.Row("Microphone", "", talkCtl, out talkHint);
            talk.Add(talkRow);
            right.Children.Add(talk.Root);

            right.Children.Add(Kit.Section("How to join"));
            var steps = new RowPanel();
            string[] s3 = {
                "Connect the device to the same Wi-Fi as this laptop, or to its hotspot.",
                "Scan the QR code with the Dual Earbuds app (keeps playing with the screen off) or the camera, or open the link in any browser.",
                "Press Start on the device. It joins the shared deadline by itself; calibrate laptop earbuds on Home for exact sync." };
            for (int i = 0; i < s3.Length; i++)
            {
                var row = new Grid { Margin = new Thickness(16, 10, 16, 10) };
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(24) });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                row.Children.Add(Ui.Mono((i + 1).ToString(CultureInfo.InvariantCulture), 12, "Muted"));
                var st = Ui.Text(s3[i], 13, FontWeights.Normal, "Text");
                Grid.SetColumn(st, 1);
                row.Children.Add(st);
                steps.Add(row);
            }
            right.Children.Add(steps.Root);
            Grid.SetColumn(right, 2);
            grid.Children.Add(right);
            root.Children.Add(grid);
            Rebuild();
        }

        void ApplyPin()
        {
            if (!ctl.SetPin(pinBox.Text.Trim()))
            {
                MessageBox.Show("The PIN needs 4 to 6 digits.", "Dual Earbuds", MessageBoxButton.OK, MessageBoxImage.Warning);
                pinBox.Text = ctl.Settings.Pin;
            }
            Refresh();
        }

        void ApplyPort()
        {
            int p;
            if (!int.TryParse(port.Text.Trim(), out p) || !ctl.SetPort(p))
            {
                MessageBox.Show("Use a port between 1024 and 65535.", "Dual Earbuds", MessageBoxButton.OK, MessageBoxImage.Warning);
                port.Text = ctl.Settings.PhonePort.ToString(CultureInfo.InvariantCulture);
            }
            Refresh();
        }

        void Remove(string id)
        {
            ctl.KickPhone(id);
            Rebuild();
        }

        // Keeps the listener rows in step with who is connected (adds, updates in place, removes),
        // so a volume slider being dragged is never rebuilt under the mouse.
        public void Rebuild()
        {
            bool partyOn = ctl.Party;
            var seen = new HashSet<string>();
            bool order = built && ctl.Phones.Count == rows.Count;
            built = true;
            foreach (var p in ctl.Phones) if (!rows.ContainsKey(p.Id ?? "")) order = false;
            if (!order)
            {
                list.Clear();
                var keep = new Dictionary<string, ListenerRow>();
                foreach (var p in ctl.Phones)
                {
                    string id = p.Id ?? "";
                    ListenerRow r;
                    if (!rows.TryGetValue(id, out r)) r = new ListenerRow(p, ctl, Remove, true);
                    else r.Update(p, partyOn);
                    keep[id] = r;
                    list.Add(r.Root);
                }
                rows.Clear();
                foreach (var kv in keep) rows[kv.Key] = kv.Value;
                if (ctl.Phones.Count == 0)
                {
                    empty = PageKit.EmptyRow(ctl.Settings.PhoneLink ? "No one is listening yet. Scan the QR code or open the link on the device." : "The link is off.");
                    list.Add(empty);
                }
            }
            else foreach (var p in ctl.Phones) rows[p.Id ?? ""].Update(p, partyOn);
            Refresh();
        }

        public void Refresh()
        {
            ListenerRow lr;
            foreach (var p in ctl.Phones) if (rows.TryGetValue(p.Id ?? "", out lr)) lr.Update(p, ctl.Party);
            updating = true;
            enable.IsChecked = ctl.Settings.PhoneLink;
            party.IsChecked = ctl.Party;
            party.IsEnabled = ctl.Settings.PhoneLink;
            pinSwitch.IsChecked = ctl.Settings.PinOn;
            remoteSw.IsChecked = ctl.Settings.RemoteControl;
            pinRow.Visibility = ctl.Party ? Visibility.Visible : Visibility.Collapsed;
            if (!pinBox.IsKeyboardFocusWithin) pinBox.Text = ctl.Settings.Pin;
            updating = false;

            bool pinOn = ctl.PinActive;
            pinShow.Visibility = pinOn ? Visibility.Visible : Visibility.Collapsed;
            pinBig.Text = pinOn ? ctl.Settings.Pin : "";

            bool hold = ctl.Settings.TalkMode != "toggle";
            talkBtn.Content = ctl.Talking ? (hold ? "Talking" : "Stop talking") : (hold ? "Hold to talk" : "Talk");
            talkHint.Text = ctl.TalkError ?? (ctl.Talking ? "Your microphone is mixed in; the sound is 12 dB lower." : ctl.Running ? "Mixes your voice into what everyone hears. Noise cancelling: " + ctl.Settings.Noise + "." : "Start sharing on Home first.");
            talkHint.SetResourceReference(TextBlock.ForegroundProperty, ctl.TalkError != null ? "Warn" : "Muted");
            talkBtn.IsEnabled = ctl.Running || ctl.Talking;

            if (ctl.ShowHotspotHelp)
            {
                bool pub = ctl.NetworkCategory == "Public";
                hotTitle.Text = pub ? "This is a public network" : "No one has joined in 60 s";
                hotText.Text = (pub ? "Cafe, hotel and campus Wi-Fi often keeps devices apart. " : "The Wi-Fi may keep devices apart. ") +
                               "Turn on this laptop's Mobile hotspot and connect the devices to it (no mobile data used), or join the laptop to a phone's hotspot.";
                hotspot.Visibility = Visibility.Visible;
            }
            else hotspot.Visibility = Visibility.Collapsed;

            bool partyOn = ctl.Party && ctl.Settings.PhoneLink;
            string url = ctl.Settings.PhoneLink ? ctl.LinkUrl : null;
            if (url != shownUrl || partyOn != shownParty)
            {
                shownUrl = url;
                shownParty = partyOn;
                double size = partyOn ? QrParty : QrNormal;
                qrColumn.Width = new GridLength(size + 52);
                qrHost.Width = size;
                qrHost.Height = size;
                if (url == null)
                {
                    var t = Ui.Text(ctl.Settings.PhoneLink ? "Connect this laptop to Wi-Fi to get a link." : "The link is off.", 13, FontWeights.Normal, "Muted");
                    t.Foreground = Brushes.Gray;
                    t.TextAlignment = TextAlignment.Center;
                    t.VerticalAlignment = VerticalAlignment.Center;
                    t.Margin = new Thickness(12);
                    qrHost.Child = t;
                    link.Text = "";
                }
                else
                {
                    qrHost.Child = Ui.Qr(url, size - 8);
                    link.Text = url;
                }
            }
            int n = ctl.Phones.Count, cap = ctl.ListenerCapacity;
            listCount.Text = n + " of " + cap + (n > 0 ? " · P " + ctl.PlayoutMs + " ms" : "");

            if (!ctl.Settings.PhoneLink) linkState.Text = "Off.";
            else if (ctl.Phone == null && !ctl.Demo) linkState.Text = "Not running: port " + ctl.Settings.PhonePort + " is taken by another app (an older Dual Earbuds still open?).";
            else
            {
                var a = ctl.Addresses;
                linkState.Text = "TCP + UDP " + ctl.Settings.PhonePort + (a.Count > 1 ? " · also on " + string.Join(", ", a.GetRange(1, a.Count - 1).ToArray()) : "");
            }
            linkState.Visibility = Visibility.Visible;
        }

        public void Animate() { }
    }

    // ---------------------------------------------------------------------- Settings
    class SettingsPage : IPage
    {
        readonly StackPanel root;
        readonly Controller ctl;
        readonly Action exitApp;
        public FrameworkElement Root { get { return root; } }

        // listener network (Windows Firewall rule)
        readonly RadioButton netPrivate, netAll;
        readonly TextBlock netStatus;
        bool netBusy, netUpdating;

        // updates
        readonly TextBlock updStatus, relTitle, relNotes, dlText, updError;
        readonly Button checkBtn, installBtn, pageBtn;
        readonly ProgressBar checking;
        readonly Meter dlMeter;
        readonly StackPanel release;
        readonly CheckBox autoUpd;

        public SettingsPage(Controller ctl, Action<string> onTheme, Action onHotkeyChanged, Action onReset, Action exitApp)
        {
            this.ctl = ctl;
            this.exitApp = exitApp;
            root = Kit.Page();
            var s = ctl.Settings;

            var apSec = Kit.Section("Appearance");
            apSec.Name = "anchor_appearance";
            root.Children.Add(apSec);
            var ap = new RowPanel();
            string[] modes = { "system", "light", "dark" };
            int mi = Array.IndexOf(modes, s.Theme);
            ap.Add(Kit.Row("Theme", "System follows Windows' app mode and switches with it.",
                Kit.Segmented("theme", new[] { "System", "Light", "Dark" }, mi < 0 ? 0 : mi, i => { s.Theme = modes[i]; ctl.MarkDirty(); onTheme(modes[i]); })));
            root.Children.Add(ap.Root);

            root.Children.Add(Kit.Section("General"));
            var win = new RowPanel();
            win.Add(Kit.Row("Closing the window", "Keep running in the tray so the sound keeps playing, or exit.",
                Kit.Segmented("close", new[] { "Keep running", "Exit" }, s.CloseToTray ? 0 : 1, i => { s.CloseToTray = i == 0; ctl.MarkDirty(); })));
            var minSw = Ui.Switch("", s.StartMinimized);
            minSw.Checked += (o, e) => { s.StartMinimized = true; ctl.MarkDirty(); };
            minSw.Unchecked += (o, e) => { s.StartMinimized = false; ctl.MarkDirty(); };
            win.Add(Kit.Row("Start minimized", "Open to the tray icon only.", minSw));
            var boot = Ui.Switch("", StartupReg.Get());
            boot.Checked += (o, e) => SetBoot(true);
            boot.Unchecked += (o, e) => SetBoot(false);
            win.Add(Kit.Row("Run when Windows starts", "Starts in the tray after you sign in.", boot));
            var auto = Ui.Switch("", s.AutoStart);
            auto.Checked += (o, e) => { s.AutoStart = true; ctl.MarkDirty(); };
            auto.Unchecked += (o, e) => { s.AutoStart = false; ctl.MarkDirty(); };
            win.Add(Kit.Row("Share when the app opens", null, auto));
            var hk = Ui.Switch("", s.Hotkey);
            hk.Checked += (o, e) => { s.Hotkey = true; ctl.MarkDirty(); onHotkeyChanged(); };
            hk.Unchecked += (o, e) => { s.Hotkey = false; ctl.MarkDirty(); onHotkeyChanged(); };
            win.Add(Kit.Row("Ctrl + Alt + M", "Start or stop sharing from any app.", hk));
            root.Children.Add(win.Root);

            var smSec = Kit.Section("Smart features");
            smSec.Name = "anchor_smart";
            root.Children.Add(smSec);
            root.Children.Add(new SmartPanel(ctl).Root);

            root.Children.Add(Kit.Section("Networks listeners may use"));
            var net = new RowPanel();
            netPrivate = Choice("netscope", "Private networks only", "Home networks, or ones you marked Private in Windows.");
            netAll = Choice("netscope", "Private and public networks", "Also cafe, hotel and campus Wi-Fi and most hotspots. The link's key still keeps strangers out.");
            netPrivate.IsChecked = s.NetworkScope == Settings.NetScopePrivate;
            netAll.IsChecked = s.NetworkScope != Settings.NetScopePrivate;
            netPrivate.Checked += (o, e) => PickScope(Settings.NetScopePrivate);
            netAll.Checked += (o, e) => PickScope(Settings.NetScopeAll);
            var choices = new StackPanel { Margin = new Thickness(0, 6, 0, 6) };
            choices.Children.Add(netPrivate);
            choices.Children.Add(netAll);
            netStatus = Kit.Hint("Sets the Windows Firewall rule (TCP and UDP). Windows asks for permission.");
            netStatus.Margin = new Thickness(44, 0, 16, 8);
            choices.Children.Add(netStatus);
            net.Add(choices);
            var showSw = Ui.Switch("", s.ShowOnNetwork);
            showSw.Checked += (o, e) => ctl.SetShowOnNetwork(true);
            showSw.Unchecked += (o, e) => ctl.SetShowOnNetwork(false);
            net.Add(Kit.Row("Show on public networks", "Nearby devices list this laptop with its link and join in one click. On private networks that's always on.", showSw));
            root.Children.Add(net.Root);

            // updates
            root.Children.Add(Kit.Section("Updates"));
            var upd = new RowPanel();
            checkBtn = Kit.Secondary("Check now", (o, e) => ctl.CheckForUpdates(true));
            TextBlock h;
            upd.Add(Kit.Row("Dual Earbuds " + AppInfo.Version, " ", checkBtn, out h));
            updStatus = h;
            checking = new ProgressBar { IsIndeterminate = true, Height = 2, BorderThickness = new Thickness(0), Visibility = Visibility.Collapsed };
            checking.SetResourceReference(Control.ForegroundProperty, "Accent");
            checking.SetResourceReference(Control.BackgroundProperty, "Line");
            upd.Children.Add(checking);

            release = new StackPanel { Margin = new Thickness(16, 12, 16, 14), Visibility = Visibility.Collapsed };
            relTitle = Ui.Text("", 15, FontWeights.SemiBold, "Text");
            release.Children.Add(relTitle);
            relNotes = Ui.Text("", 13, FontWeights.Normal, "Text");
            relNotes.LineHeight = 20;
            var notesScroll = new ScrollViewer { Content = relNotes, MaxHeight = 160, Margin = new Thickness(0, 8, 0, 0), VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Focusable = false };
            release.Children.Add(notesScroll);
            dlMeter = new Meter { Margin = new Thickness(0, 12, 0, 0), Visibility = Visibility.Collapsed };
            release.Children.Add(dlMeter);
            dlText = Kit.Hint("");
            dlText.Margin = new Thickness(0, 8, 0, 0);
            release.Children.Add(dlText);
            updError = Ui.Text("", 12, FontWeights.Normal, "Warn");
            updError.Margin = new Thickness(0, 6, 0, 0);
            updError.Visibility = Visibility.Collapsed;
            release.Children.Add(updError);
            var rb = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 12, 0, 0) };
            installBtn = Kit.Primary("Install update", (o, e) => ctl.InstallUpdate(exitApp));
            rb.Children.Add(installBtn);
            pageBtn = Kit.Quiet("Release page", (o, e) => OpenReleasePage());
            pageBtn.Margin = new Thickness(6, 0, 0, 0);
            rb.Children.Add(pageBtn);
            release.Children.Add(rb);
            upd.Add(release);
            autoUpd = Ui.Switch("", s.CheckUpdates);
            autoUpd.Checked += (o, e) => { s.CheckUpdates = true; ctl.MarkDirty(); };
            autoUpd.Unchecked += (o, e) => { s.CheckUpdates = false; ctl.MarkDirty(); };
            upd.Add(Kit.Row("Check once a day", "Nothing is downloaded or installed until you press Install.", autoUpd));
            root.Children.Add(upd.Root);

            root.Children.Add(Kit.Section("Help and data"));
            var data = new RowPanel();
            var copyDiag = Kit.Secondary("Copy", null);
            copyDiag.Click += (o, e) =>
            {
                string text;
                try { text = ctl.DiagnosticsText(); }
                catch (Exception ex) { text = "Diagnostics failed: " + ex.Message; }
                try { Clipboard.SetText(text); copyDiag.Content = "Copied"; }
                catch { copyDiag.Content = "Couldn't copy"; }
                var t = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
                t.Tick += (o2, e2) => { t.Stop(); copyDiag.Content = "Copy"; };
                t.Start();
            };
            data.Add(Kit.Row("Diagnostics", "Version, Windows build, network, outputs, listeners and the last 200 log lines. Never your link key or PIN.", copyDiag));
            var open = Kit.Secondary("Open folder", (o, e) => PageKit.OpenFolder(AppInfo.DataDir));
            data.Add(Kit.Row("Settings and logs", AppInfo.DataDir, open));
            var reset = Kit.Secondary("Reset", (o, e) =>
            {
                var r = MessageBox.Show("Reset all settings? The link key changes too, so everyone has to join again.", "Dual Earbuds", MessageBoxButton.OKCancel, MessageBoxImage.Warning);
                if (r == MessageBoxResult.OK) onReset();
            });
            data.Add(Kit.Row("Reset all settings", "Calibrations, hearing profiles and recent devices are kept.", reset));
            root.Children.Add(data.Root);
        }

        static void SetBoot(bool on)
        {
            try { StartupReg.Set(on, System.Reflection.Assembly.GetEntryAssembly().Location); }
            catch (Exception ex) { MessageBox.Show("Windows refused the startup setting: " + ex.Message, "Dual Earbuds"); }
        }

        static RadioButton Choice(string group, string title, string text)
        {
            var sp = new StackPanel();
            sp.Children.Add(Ui.Text(title, 14, FontWeights.Normal, "Text"));
            sp.Children.Add(Kit.Hint(text));
            var r = new RadioButton { GroupName = group, Content = sp, HorizontalContentAlignment = HorizontalAlignment.Stretch };
            r.SetResourceReference(FrameworkElement.StyleProperty, "Choice");
            return r;
        }

        void PickScope(string scope)
        {
            if (netUpdating || netBusy || scope == ctl.Settings.NetworkScope) return;
            netBusy = true;
            netPrivate.IsEnabled = netAll.IsEnabled = false;
            ShowNet("Windows asks for permission to change the firewall rule.", "Muted");
            ctl.ApplyNetworkScope(scope, r =>
            {
                netBusy = false;
                netPrivate.IsEnabled = netAll.IsEnabled = true;
                if (r == FirewallResult.Ok)
                {
                    ShowNet(scope == Settings.NetScopePrivate ? "Saved: private networks only." : "Saved: private and public networks.", "Good");
                    return;
                }
                netUpdating = true;
                if (ctl.Settings.NetworkScope == Settings.NetScopePrivate) netPrivate.IsChecked = true; else netAll.IsChecked = true;
                netUpdating = false;
                ShowNet(r == FirewallResult.Declined
                    ? "Nothing changed: the permission prompt was declined."
                    : "Nothing changed: Windows didn't update the rule. Try again, or see the log on Diagnostics.", "Warn");
            });
        }

        void ShowNet(string text, string brush)
        {
            netStatus.Text = text;
            netStatus.SetResourceReference(TextBlock.ForegroundProperty, brush == "Good" ? "Muted" : brush);
        }

        void OpenReleasePage()
        {
            var r = ctl.UpdateResult;
            string url = r != null && r.Release != null ? r.Release.PageUrl : null;
            if (!Updater.IsAllowedDownloadUrl(url)) url = "https://github.com/" + Updater.Repo + "/releases/latest";
            try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); }
            catch (Exception ex) { Log.Warn("Open release page: " + ex.Message); }
        }

        public void Rebuild() { }

        public void Refresh()
        {
            var r = ctl.UpdateResult;
            bool available = r != null && r.Status == UpdateStatus.Available && r.Release != null;
            checking.Visibility = ctl.UpdateChecking ? Visibility.Visible : Visibility.Collapsed;
            checkBtn.IsEnabled = !ctl.UpdateChecking && !ctl.UpdateDownloading;
            checkBtn.Content = ctl.UpdateChecking ? "Checking" : "Check now";

            string status, brush = "Muted";
            if (ctl.UpdateChecking) status = "Asking GitHub for the latest release.";
            else if (r == null) status = ctl.Settings.LastUpdateCheckUtc > DateTime.MinValue ? "Last checked " + When(ctl.Settings.LastUpdateCheckUtc) + "." : "Not checked yet.";
            else if (available) status = r.Message;
            else if (r.Status == UpdateStatus.UpToDate) status = "Up to date, checked " + When(r.CheckedUtc) + ".";
            else if (r.Status == UpdateStatus.NoRelease) status = r.Message;
            else { status = r.Message; brush = "Warn"; }
            updStatus.Text = status;
            updStatus.SetResourceReference(TextBlock.ForegroundProperty, brush);
            updStatus.Visibility = Visibility.Visible;

            release.Visibility = available ? Visibility.Visible : Visibility.Collapsed;
            if (available)
            {
                relTitle.Text = "Version " + r.Release.Version;
                string notes = Updater.NotesForDisplay(r.Release.Notes);
                if (notes.Length == 0) notes = "Fixes and improvements.";
                if (relNotes.Text != notes) relNotes.Text = notes;
                bool dl = ctl.UpdateDownloading;
                installBtn.IsEnabled = !dl;
                installBtn.Content = dl ? "Downloading" : "Install update";
                dlMeter.Visibility = dl ? Visibility.Visible : Visibility.Collapsed;
                dlMeter.Level = ctl.UpdateProgress < 0 ? 0.5 : ctl.UpdateProgress / 100.0;
                dlText.Text = dl
                    ? (ctl.UpdateProgress < 0 ? "Downloading the installer." : "Downloading the installer: " + ctl.UpdateProgress + "%")
                    : "Dual Earbuds closes, updates and opens again. Settings stay as they are.";
                updError.Text = ctl.UpdateError ?? "";
                updError.Visibility = ctl.UpdateError != null ? Visibility.Visible : Visibility.Collapsed;
            }
        }

        static string When(DateTime utc)
        {
            var local = utc.ToLocalTime();
            return local.Date == DateTime.Today ? "today at " + local.ToString("t") : local.ToString("d MMM") + ", " + local.ToString("t");
        }

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
            root = Kit.Page();
            var bar = new StackPanel { Orientation = Orientation.Horizontal };
            run = Kit.Primary("Run check", null);
            run.Click += (s, e) =>
            {
                run.IsEnabled = false;
                report.Text = "Checking outputs.";
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
            bar.Children.Add(run);
            foreach (var b in new[] {
                Kit.Quiet("Copy", (s, e) => { try { if (report.Text.Length > 0) Clipboard.SetText(report.Text); } catch { } }),
                Kit.Quiet("Save", (s, e) => Save()),
                Kit.Quiet("Open logs", (s, e) => PageKit.OpenFolder(Log.Dir)) })
            {
                b.Margin = new Thickness(6, 0, 0, 0);
                bar.Children.Add(b);
            }
            TextBlock c;
            root.Children.Add(Kit.Section("Outputs and listeners", out c, bar));
            c.Text = "nothing is played while checking";
            report = new TextBox
            {
                IsReadOnly = true, FontFamily = Theme.Mono, FontSize = 12, MinHeight = 420, TextWrapping = TextWrapping.NoWrap, VerticalContentAlignment = VerticalAlignment.Top,
                HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                Text = "Run check lists what Windows allows on each output. Your link key is never included."
            };
            root.Children.Add(report);
        }

        void Save()
        {
            var dlg = new Microsoft.Win32.SaveFileDialog { FileName = "dual-earbuds-report.txt", Filter = "Text file|*.txt" };
            if (dlg.ShowDialog() == true)
            {
                try { File.WriteAllText(dlg.FileName, report.Text); }
                catch (Exception ex) { MessageBox.Show("Couldn't save: " + ex.Message, "Dual Earbuds"); }
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
            root = Kit.Page();
            var top = new RowPanel();
            var head = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(16) };
            head.Children.Add(Ui.Logo(40));
            var t = new StackPanel { Margin = new Thickness(14, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            t.Children.Add(Ui.Text("Dual Earbuds", 15, FontWeights.SemiBold, "Text"));
            t.Children.Add(Ui.Mono(AppInfo.Version + "  ·  protocol 3.2  ·  MIT", 12, "Muted"));
            head.Children.Add(t);
            top.Add(head);
            top.Root.Margin = new Thickness(0, 20, 0, 0);
            root.Children.Add(top.Root);

            root.Children.Add(Kit.Section("How it works"));
            var how = new RowPanel();
            string[][] rows =
            {
                new[] { "Capture", "Windows' audio engine hands over what this laptop plays (or one app), silently." },
                new[] { "Deadline", "Every ear plays a sample at capture + P. P is the slowest ear's need, so the laptop's own earbuds wait for the phones." },
                new[] { "Send", "Phones and laptops get 10 ms UDP packets with the previous one repeated, so one lost packet costs nothing. Browsers use a WebSocket." },
                new[] { "Line up", "Each ear's latency is measured (calibration) or estimated; clock sync keeps everyone within a few ms." },
            };
            foreach (var r in rows) how.Add(Kit.Row(r[0], r[1], null));
            root.Children.Add(how.Root);

            root.Children.Add(Kit.Section("Credits"));
            var cr = new RowPanel();
            cr.Add(Kit.Row("Qasim Raza Malik", "WASAPI, WPF and a small built-in web server. No account, no cloud: nothing leaves your network.", null));
            root.Children.Add(cr.Root);
        }

        public void Rebuild() { }
        public void Refresh() { }
        public void Animate() { }
    }
}
