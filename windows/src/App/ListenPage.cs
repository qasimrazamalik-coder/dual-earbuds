// Dual Earbuds - Listen: this laptop plays another device's sound (laptop to laptop, or a phone that shares).
// Join in one click from the devices found on this network, from the recent list, or by pasting the link,
// and pick which of this laptop's outputs play it. Every output shows its sync scope.
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Shapes;

namespace DualEarbuds
{
    class ListenPage : IPage
    {
        readonly Controller ctl;
        readonly StackPanel root;
        readonly TextBox link, pin;
        readonly TextBlock problem, headline, stateLine, stats, suggestText, foundCount, recentCount, outCount, talkWord;
        readonly Ellipse stateDot;
        readonly Button joinBtn, stopBtn;
        readonly StackPanel remoteRow, pinPanel;
        readonly Border nowPanel;
        readonly Grid suggestRow;
        readonly RowPanel found = new RowPanel(), recent = new RowPanel(), outs = new RowPanel();
        readonly List<Tuple<OutputVm, EarRow, CheckBox>> outRows = new List<Tuple<OutputVm, EarRow, CheckBox>>();
        string foundSig, recentSig, outSig;
        bool visible, updating;

        public FrameworkElement Root { get { return root; } }

        public ListenPage(Controller ctl)
        {
            this.ctl = ctl;
            root = Kit.Page();

            // now playing
            var now = new RowPanel();
            stopBtn = Kit.Secondary("Stop listening", (s, e) => { ctl.StopListening(); Refresh(); });
            var nowBody = new StackPanel();
            nowBody.Children.Add(PageKit.StateRow(out headline, out stateLine, out stateDot, stopBtn));
            stats = Ui.Mono("", 12, "Muted");
            stats.Margin = new Thickness(34, -6, 16, 12);
            nowBody.Children.Add(stats);
            talkWord = Ui.Text("The host is talking.", 12, FontWeights.Normal, "Warn");
            talkWord.Margin = new Thickness(34, -6, 16, 12);
            talkWord.Visibility = Visibility.Collapsed;
            nowBody.Children.Add(talkWord);
            remoteRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(28, 0, 16, 12), Visibility = Visibility.Collapsed };
            AddRemote("Previous", "prev");
            AddRemote("Play / pause", "playpause");
            AddRemote("Next", "next");
            AddRemote("Volume −", "voldown");
            AddRemote("Volume +", "volup");
            nowBody.Children.Add(remoteRow);
            now.Add(nowBody);
            var useIt = Kit.Secondary("Use it", (s, e) => { ctl.AcceptSuggestion(); Refresh(); });
            var noThanks = Kit.Quiet("No thanks", (s, e) => { ctl.DismissSuggestion(); Refresh(); });
            noThanks.Margin = new Thickness(6, 0, 0, 0);
            var sgBtns = new StackPanel { Orientation = Orientation.Horizontal };
            sgBtns.Children.Add(useIt);
            sgBtns.Children.Add(noThanks);
            suggestRow = Kit.Row("EQ suggestion", "", sgBtns, out suggestText);
            now.Add(suggestRow);
            nowPanel = now.Root;
            nowPanel.Margin = new Thickness(0, 20, 0, 0);
            root.Children.Add(nowPanel);

            // found on this network: one click joins (phones that share are listed too)
            var refresh = Kit.Quiet("Look again", (s, e) => { ctl.StartBrowsing(); Rebuild(); });
            root.Children.Add(Kit.Section("On this network", out foundCount, refresh));
            root.Children.Add(found.Root);

            root.Children.Add(Kit.Section("Recent", out recentCount, null));
            root.Children.Add(recent.Root);

            // join by link
            root.Children.Add(Kit.Section("Join by link"));
            var join = new RowPanel();
            var jrow = new Grid { Margin = new Thickness(16, 12, 16, 12) };
            jrow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            jrow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            link = new TextBox { FontFamily = Theme.Mono, FontSize = 12.5, ToolTip = "http://192.168.1.16:8790/?k=..." };
            link.KeyDown += (s, e) => { if (e.Key == Key.Enter) Join(); };
            jrow.Children.Add(link);
            joinBtn = Kit.Primary("Join", (s, e) => Join());
            joinBtn.Margin = new Thickness(8, 0, 0, 0);
            joinBtn.MinWidth = 80;
            Grid.SetColumn(joinBtn, 1);
            jrow.Children.Add(joinBtn);
            var jcol = new StackPanel();
            jcol.Children.Add(jrow);
            var jh = Kit.Hint("Paste the link from the other device's Listeners page.");
            jh.Margin = new Thickness(16, -6, 16, 12);
            jcol.Children.Add(jh);
            join.Add(jcol);
            pinPanel = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(16, 10, 16, 10), Visibility = Visibility.Collapsed };
            var pl = Ui.Text("Room PIN", 14, FontWeights.Normal, "Text");
            pl.VerticalAlignment = VerticalAlignment.Center;
            pl.Margin = new Thickness(0, 0, 12, 0);
            pinPanel.Children.Add(pl);
            pin = new TextBox { Width = 96, FontFamily = Theme.Mono, FontSize = 14, MaxLength = 6 };
            pin.KeyDown += (s, e) => { if (e.Key == Key.Enter) Join(); };
            pinPanel.Children.Add(pin);
            var ph = Kit.Hint("The host tells you this. It's never in the link.");
            ph.VerticalAlignment = VerticalAlignment.Center;
            ph.Margin = new Thickness(12, 0, 0, 0);
            pinPanel.Children.Add(ph);
            join.Add(pinPanel);
            problem = Ui.Text("", 12, FontWeights.Normal, "Warn");
            problem.Margin = new Thickness(16, 10, 16, 10);
            problem.Visibility = Visibility.Collapsed;
            join.Add(problem);
            root.Children.Add(join.Root);

            // outputs on this laptop
            root.Children.Add(Kit.Section("Play on this laptop", out outCount, null));
            root.Children.Add(outs.Root);
            var tip = Kit.Hint("Each output keeps its Delay, volume, EQ and calibration from Home. Calibrated outputs play on the host's deadline.");
            tip.Margin = new Thickness(16, 8, 16, 0);
            root.Children.Add(tip);

            ctl.ListenChanged += () => { if (visible) Rebuild(); };
            Rebuild();
        }

        void AddRemote(string label, string action)
        {
            var b = Kit.Quiet(label, (s, e) => ctl.ListenRemote(action));
            b.Margin = new Thickness(0, 0, 4, 0);
            remoteRow.Children.Add(b);
        }

        string PinText { get { string p = pin.Text.Trim(); return p.Length == 0 ? null : p; } }

        void Join()
        {
            string p = PinText;
            if (p != null && !RoomPin.IsValid(p)) { ShowProblem("The PIN has 4 to 6 digits."); return; }
            if (ctl.JoinLink(link.Text, p)) link.Text = "";
            Refresh();
        }

        void ShowProblem(string text)
        {
            problem.Text = text ?? "";
            problem.Visibility = string.IsNullOrEmpty(text) ? Visibility.Collapsed : Visibility.Visible;
        }

        static Grid HostRow(string icon, string title, string sub, Button action)
        {
            var g = new Grid { MinHeight = 56 };
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var ic = Ui.Icon(icon, 16, "Muted", false);
            ic.Margin = new Thickness(16, 0, 12, 0);
            g.Children.Add(ic);
            var t = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 8, 12, 8) };
            var tt = Ui.Text(title, 14, FontWeights.Normal, "Text");
            tt.TextTrimming = TextTrimming.CharacterEllipsis;
            tt.TextWrapping = TextWrapping.NoWrap;
            t.Children.Add(tt);
            if (!string.IsNullOrEmpty(sub)) { var st = Ui.Mono(sub, 11.5, "Muted"); st.Margin = new Thickness(0, 3, 0, 0); t.Children.Add(st); }
            Grid.SetColumn(t, 1);
            g.Children.Add(t);
            if (action != null)
            {
                action.Margin = new Thickness(0, 0, 16, 0);
                action.VerticalAlignment = VerticalAlignment.Center;
                Grid.SetColumn(action, 2);
                g.Children.Add(action);
            }
            return g;
        }

        public void Rebuild()
        {
            var hosts = ctl.FoundHosts;
            var sb = new System.Text.StringBuilder();
            foreach (var h in hosts) sb.Append(h.Instance).Append(h.Address).Append(h.Port).Append(h.Key != null).Append(h.Pin).Append(h.HostKind).Append('|');
            if (sb.ToString() != foundSig)
            {
                foundSig = sb.ToString();
                found.Clear();
                if (hosts.Count == 0)
                    found.Add(PageKit.EmptyRow("Looking for devices that share on this Wi-Fi. On public Wi-Fi they may not show; paste their link instead."));
                foreach (var h in hosts)
                {
                    var host = h;
                    bool phone = h.HostKind == "android";
                    string sub = (phone ? "phone, shares its sound" : "laptop") + " · " + h.Address + (h.Pin ? " · PIN" : "") + (h.Key == null ? " · needs its link" : "");
                    Button b = h.Key == null ? null : Kit.Secondary("Join", (s, e) => { ctl.JoinFound(host, PinText); Refresh(); });
                    found.Add(HostRow(phone ? Icons.Phone : Icons.Laptop, h.Name, sub, b));
                }
                foundCount.Text = hosts.Count.ToString(CultureInfo.InvariantCulture);
            }
            var rs = new System.Text.StringBuilder();
            foreach (var r in ctl.Settings.Recent) rs.Append(r.HostPort).Append(r.Name).Append(r.Kind).Append('|');
            if (rs.ToString() != recentSig)
            {
                recentSig = rs.ToString();
                recent.Clear();
                if (ctl.Settings.Recent.Count == 0) recent.Add(PageKit.EmptyRow("Devices you listened to show up here, the last 5."));
                foreach (var r in ctl.Settings.Recent)
                {
                    var rec = r;
                    bool phone = r.Kind == "android";
                    var b = Kit.Secondary("Join", (s, e) => { ctl.JoinRecent(rec, PinText); Refresh(); });
                    recent.Add(HostRow(phone ? Icons.Phone : Icons.Laptop, string.IsNullOrEmpty(r.Name) ? r.HostPort : r.Name, (phone ? "phone · " : "laptop · ") + r.HostPort, b));
                }
                recentCount.Text = ctl.Settings.Recent.Count.ToString(CultureInfo.InvariantCulture);
            }
            var os = new System.Text.StringBuilder();
            foreach (var o in ctl.Outputs) os.Append(o.Id).Append('|');
            if (os.ToString() != outSig)
            {
                outSig = os.ToString();
                outs.Clear();
                outRows.Clear();
                foreach (var o in ctl.Outputs)
                {
                    var vm = o;
                    var er = new EarRow(o.Name.StartsWith("Speaker", StringComparison.OrdinalIgnoreCase) ? Icons.Speaker : Icons.Headphones, o.Name);
                    var sw = Ui.Switch("", o.ListenTicked);
                    sw.Checked += (s, e) => { if (!updating) ctl.SetListenTicked(vm, true); };
                    sw.Unchecked += (s, e) => { if (!updating) ctl.SetListenTicked(vm, false); };
                    er.Lead.Children.Add(sw);
                    outRows.Add(Tuple.Create(vm, er, sw));
                    outs.Add(er.Root);
                }
                if (ctl.Outputs.Count == 0) outs.Add(PageKit.EmptyRow("No outputs. Connect earbuds in Windows Bluetooth settings."));
            }
            Refresh();
        }

        public void Refresh()
        {
            bool on = ctl.Listening;
            var ls = ctl.ListenStateNow;
            nowPanel.Visibility = on || ls == ListenState.Kicked || ls == ListenState.NeedPin || ls == ListenState.WrongPin || ls == ListenState.WrongKey ? Visibility.Visible : Visibility.Collapsed;
            headline.Text = on ? ctl.ListenHostName : "Not listening";
            string dot = ls == ListenState.Playing ? "Accent" : ls == ListenState.Connecting || ls == ListenState.Reconnecting || ls == ListenState.HostPaused ? "Warn" : "Bad";
            stateDot.SetResourceReference(Shape.FillProperty, dot);
            stateLine.Text = ctl.ListenText(ls);
            stopBtn.Visibility = on ? Visibility.Visible : Visibility.Collapsed;
            var c = ctl.Listen;
            if (c != null && ls == ListenState.Playing)
            {
                stats.Text = (c.HostKind == "android" ? "phone · " : "laptop · ") + c.Transport.ToUpperInvariant() + " · " + (c.Rate / 1000.0).ToString("0.#", CultureInfo.InvariantCulture) + " kHz · " +
                             (c.Synced ? "P " + c.PlayoutMs + " ms · needs " + c.NeedMs + " ms · rtt " + Math.Round(c.RttMs) + " ms" : "buffer " + c.Buffer.TargetMs + " ms (" + ctl.Settings.Buffer + ")") +
                             " · lost " + c.Lost + " · filled " + c.Concealed + (c.UdpRecovered > 0 ? " · recovered " + c.UdpRecovered : "") + " · dropouts " + c.Underruns +
                             (c.HostVolume != 100 ? " · host vol " + c.HostVolume + "%" : "") + (c.Source == "app" && c.SourceApp != null ? " · " + c.SourceApp : "");
                stats.Visibility = Visibility.Visible;
            }
            else if (ctl.Demo && on) { stats.Text = "laptop · UDP · 48 kHz · P 120 ms · needs 84 ms · rtt 6 ms · lost 0 · filled 0 · dropouts 0"; stats.Visibility = Visibility.Visible; }
            else stats.Visibility = Visibility.Collapsed;
            remoteRow.Visibility = on && (ctl.Demo || (c != null && c.RemoteAllowed)) ? Visibility.Visible : Visibility.Collapsed;
            talkWord.Visibility = c != null && c.HostTalking ? Visibility.Visible : Visibility.Collapsed;
            string sug = ctl.ListenSuggestion;
            suggestRow.Visibility = sug != null ? Visibility.Visible : Visibility.Collapsed;
            if (sug != null) suggestText.Text = "The host suggests the " + Kit.Cap(sug) + " EQ.";
            pinPanel.Visibility = ls == ListenState.NeedPin || ls == ListenState.WrongPin || PinText != null ? Visibility.Visible : Visibility.Collapsed;
            ShowProblem(ctl.ListenProblem);
            joinBtn.Content = on ? "Switch" : "Join";
            updating = true;
            int ticked = 0;
            foreach (var t in outRows)
            {
                var vm = t.Item1;
                if (vm.ListenTicked) ticked++;
                t.Item3.IsChecked = vm.ListenTicked;
                string state = ctl.ListenOutputState(vm);
                string lat = vm.LatencyMeasured ? Kit.Ms(vm.LatencyMs) + " measured" : "~" + Kit.Ms(vm.EffectiveLatencyMs) + " estimated";
                t.Item2.Update(vm.Name, (state != null ? state.ToLowerInvariant() + " · " : "") + lat, ctl.ListenOffsetMs(vm));
                t.Item2.Dim(!vm.ListenTicked);
            }
            updating = false;
            outCount.Text = ticked + " of " + outRows.Count + " on";
        }

        public void Animate()
        {
            // the scopes move with the clock sync, a few times a second is plenty
            if (ctl.Listening) foreach (var t in outRows) if (t.Item1.ListenTicked) t.Item2.SetOffset(ctl.ListenOffsetMs(t.Item1));
        }

        // The page is shown / hidden: browse for hosts only while it is on screen.
        public void Shown() { visible = true; ctl.StartBrowsing(); Rebuild(); }
        public void Hidden() { visible = false; ctl.StopBrowsing(); }
    }
}
