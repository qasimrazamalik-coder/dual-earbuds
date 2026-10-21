// Dual Earbuds - "Listen to another device" (laptop to laptop): join another host by link, from the
// devices found on this network, or from the recent list, and pick which of this laptop's outputs play it.
using System;
using System.Collections.Generic;
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
        readonly TextBlock problem, stateTitle, stateText, stats, suggestText;
        readonly Ellipse stateDot;
        readonly Button joinBtn, stopBtn, useSuggest;
        readonly StackPanel remoteRow, foundList, recentList, outList, pinPanel;
        readonly Border nowCard, suggestBar, talkBadge;
        string foundSig, recentSig, outSig;
        bool visible;

        public FrameworkElement Root { get { return root; } }

        public ListenPage(Controller ctl)
        {
            this.ctl = ctl;
            root = PageKit.Column();
            root.Children.Add(PageKit.Header("Listen to another device", "Play another laptop's or phone's Dual Earbuds sound on this laptop's earbuds - laptop to laptop."));

            // ---- now playing
            var now = new StackPanel();
            var head = new Grid();
            head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var titleRow = new StackPanel { Orientation = Orientation.Horizontal };
            stateDot = new Ellipse { Width = 10, Height = 10, Margin = new Thickness(0, 0, 10, 0), VerticalAlignment = VerticalAlignment.Center };
            stateTitle = Ui.Heading("", 20);
            titleRow.Children.Add(stateDot);
            titleRow.Children.Add(stateTitle);
            talkBadge = new Border { CornerRadius = new CornerRadius(8), Padding = new Thickness(8, 2, 8, 2), Margin = new Thickness(12, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center, Visibility = Visibility.Collapsed };
            talkBadge.SetResourceReference(Border.BackgroundProperty, "Warn");
            var tb = Ui.Text("HOST IS TALKING", 10.5, FontWeights.Bold, "Bg");
            tb.TextWrapping = TextWrapping.NoWrap;
            talkBadge.Child = tb;
            titleRow.Children.Add(talkBadge);
            head.Children.Add(titleRow);
            stopBtn = Ui.Button("Stop listening", "Ghost", (s, e) => { ctl.StopListening(); Refresh(); });
            Grid.SetColumn(stopBtn, 1);
            head.Children.Add(stopBtn);
            now.Children.Add(head);
            stateText = Ui.Text("", 13.5, FontWeights.Normal, "Muted");
            stateText.Margin = new Thickness(20, 6, 0, 0);
            now.Children.Add(stateText);
            stats = Ui.Text("", 12, FontWeights.Normal, "Muted");
            stats.FontFamily = Theme.Mono;
            stats.Margin = new Thickness(20, 8, 0, 0);
            now.Children.Add(stats);

            remoteRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(20, 14, 0, 0), Visibility = Visibility.Collapsed };
            AddRemote("Previous", "prev");
            AddRemote("Play / pause", "playpause");
            AddRemote("Next", "next");
            AddRemote("Volume -", "voldown");
            AddRemote("Volume +", "volup");
            now.Children.Add(remoteRow);

            suggestBar = new Border { CornerRadius = new CornerRadius(12), Padding = new Thickness(14, 10, 10, 10), Margin = new Thickness(0, 14, 0, 0), BorderThickness = new Thickness(1), Visibility = Visibility.Collapsed };
            suggestBar.SetResourceReference(Border.BackgroundProperty, "AccentSoft");
            suggestBar.SetResourceReference(Border.BorderBrushProperty, "Accent");
            var sg = new Grid();
            sg.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            sg.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            sg.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            suggestText = Ui.Text("", 13, FontWeights.SemiBold, "Text");
            suggestText.VerticalAlignment = VerticalAlignment.Center;
            sg.Children.Add(suggestText);
            useSuggest = Ui.Button("Use it", "Primary", (s, e) => { ctl.AcceptSuggestion(); Refresh(); });
            useSuggest.Padding = new Thickness(14, 6, 14, 6);
            Grid.SetColumn(useSuggest, 1);
            sg.Children.Add(useSuggest);
            var noThanks = Ui.Button("No thanks", "Ghost", (s, e) => { ctl.DismissSuggestion(); Refresh(); });
            noThanks.Margin = new Thickness(8, 0, 0, 0);
            Grid.SetColumn(noThanks, 2);
            sg.Children.Add(noThanks);
            suggestBar.Child = sg;
            now.Children.Add(suggestBar);
            nowCard = Ui.Card(now);
            nowCard.Margin = new Thickness(0, 0, 0, 4);
            root.Children.Add(nowCard);

            // ---- join by link
            root.Children.Add(PageKit.Section("Join by link"));
            var join = new StackPanel();
            var jl = Ui.Text("Paste the link from the other device's Phones page (it looks like http://192.168.1.16:8790/?k=...).", 13, FontWeights.Normal, "Muted");
            jl.Margin = new Thickness(0, 0, 0, 10);
            join.Children.Add(jl);
            var jrow = new Grid();
            jrow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            jrow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            link = new TextBox { FontFamily = Theme.Mono, FontSize = 13 };
            link.KeyDown += (s, e) => { if (e.Key == Key.Enter) Join(); };
            jrow.Children.Add(link);
            joinBtn = Ui.Button("Join", "Primary", (s, e) => Join());
            joinBtn.Margin = new Thickness(8, 0, 0, 0);
            joinBtn.MinWidth = 96;
            Grid.SetColumn(joinBtn, 1);
            jrow.Children.Add(joinBtn);
            join.Children.Add(jrow);
            pinPanel = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 10, 0, 0), Visibility = Visibility.Collapsed };
            var pl = Ui.Text("Room PIN", 13, FontWeights.SemiBold, "Text");
            pl.VerticalAlignment = VerticalAlignment.Center;
            pl.Margin = new Thickness(0, 0, 10, 0);
            pinPanel.Children.Add(pl);
            pin = new TextBox { Width = 110, FontFamily = Theme.Mono, FontSize = 15, MaxLength = 6 };
            pin.KeyDown += (s, e) => { if (e.Key == Key.Enter) Join(); };
            pinPanel.Children.Add(pin);
            var ph = Ui.Text("The host tells you this. It's never part of the link.", 12.5, FontWeights.Normal, "Muted");
            ph.VerticalAlignment = VerticalAlignment.Center;
            ph.Margin = new Thickness(12, 0, 0, 0);
            pinPanel.Children.Add(ph);
            join.Children.Add(pinPanel);
            problem = Ui.Text("", 12.5, FontWeights.SemiBold, "Warn");
            problem.Margin = new Thickness(0, 8, 0, 0);
            problem.Visibility = Visibility.Collapsed;
            join.Children.Add(problem);
            root.Children.Add(Ui.Card(join));

            // ---- two columns: found on this network | recent
            var cols = new Grid();
            cols.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            cols.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(16) });
            cols.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var left = new StackPanel();
            var fh = new Grid();
            fh.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            fh.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            fh.Children.Add(PageKit.Section("On this network"));
            var refresh = new Button { Content = Ui.Icon(Icons.Refresh, 15, "Muted", false), ToolTip = "Look again", Width = 34, Height = 30, Padding = new Thickness(0), Margin = new Thickness(0, 14, 0, 4), VerticalAlignment = VerticalAlignment.Bottom };
            refresh.SetResourceReference(FrameworkElement.StyleProperty, "Ghost");
            refresh.Click += (s, e) => { ctl.StartBrowsing(); Rebuild(); };
            Grid.SetColumn(refresh, 1);
            fh.Children.Add(refresh);
            left.Children.Add(fh);
            foundList = new StackPanel();
            left.Children.Add(Ui.Card(foundList));
            cols.Children.Add(left);
            var right = new StackPanel();
            right.Children.Add(PageKit.Section("Recent"));
            recentList = new StackPanel();
            right.Children.Add(Ui.Card(recentList));
            Grid.SetColumn(right, 2);
            cols.Children.Add(right);
            root.Children.Add(cols);

            // ---- outputs
            root.Children.Add(PageKit.Section("Play on this laptop"));
            outList = new StackPanel();
            root.Children.Add(Ui.Card(outList));
            var tip = Ui.Text("Tick one or several outputs. Each keeps its own Delay, volume, EQ and Auto-sync from the Home page; with Auto-sync measured, this laptop plays in step with everyone else.", 12.5, FontWeights.Normal, "Muted");
            tip.Margin = new Thickness(4, 10, 0, 0);
            root.Children.Add(tip);

            ctl.ListenChanged += () => { if (visible) Rebuild(); };
            Rebuild();
        }

        void AddRemote(string label, string action)
        {
            var b = Ui.Button(label, "Ghost", (s, e) => ctl.ListenRemote(action));
            b.Margin = new Thickness(0, 0, 8, 0);
            b.Padding = new Thickness(12, 6, 12, 6);
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
            var g = new Grid { Margin = new Thickness(0, 6, 0, 6) };
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var ic = Ui.Icon(icon, 20, "Accent", false);
            ic.Margin = new Thickness(0, 0, 12, 0);
            g.Children.Add(ic);
            var t = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            var tt = Ui.Text(title, 14, FontWeights.SemiBold, "Text");
            tt.TextTrimming = TextTrimming.CharacterEllipsis;
            tt.TextWrapping = TextWrapping.NoWrap;
            t.Children.Add(tt);
            if (!string.IsNullOrEmpty(sub)) t.Children.Add(Ui.Text(sub, 12, FontWeights.Normal, "Muted"));
            Grid.SetColumn(t, 1);
            g.Children.Add(t);
            if (action != null)
            {
                action.Padding = new Thickness(14, 6, 14, 6);
                action.Margin = new Thickness(10, 0, 0, 0);
                Grid.SetColumn(action, 2);
                g.Children.Add(action);
            }
            return g;
        }

        public void Rebuild()
        {
            // found hosts
            var found = ctl.FoundHosts;
            var sb = new System.Text.StringBuilder();
            foreach (var h in found) sb.Append(h.Instance).Append(h.Address).Append(h.Port).Append(h.Key != null).Append(h.Pin).Append('|');
            if (sb.ToString() != foundSig)
            {
                foundSig = sb.ToString();
                foundList.Children.Clear();
                if (found.Count == 0)
                    foundList.Children.Add(Ui.Text("Looking for Dual Earbuds on this Wi-Fi... Devices appear here when they share sound. On public Wi-Fi they may not show up - paste their link instead.", 13, FontWeights.Normal, "Muted"));
                foreach (var h in found)
                {
                    var host = h;
                    string sub = (h.HostKind == "android" ? "Phone" : "Laptop") + " · " + h.Address + (h.Pin ? " · PIN" : "") + (h.Key == null ? " · needs its link" : "");
                    Button b = h.Key == null ? null : Ui.Button("Join", "Ghost", (s, e) => { ctl.JoinFound(host, PinText); Refresh(); });
                    foundList.Children.Add(HostRow(h.HostKind == "android" ? Icons.Phone : Icons.Laptop, h.Name, sub, b));
                }
            }
            // recent
            var rs = new System.Text.StringBuilder();
            foreach (var r in ctl.Settings.Recent) rs.Append(r.HostPort).Append(r.Name).Append('|');
            if (rs.ToString() != recentSig)
            {
                recentSig = rs.ToString();
                recentList.Children.Clear();
                if (ctl.Settings.Recent.Count == 0)
                    recentList.Children.Add(Ui.Text("Devices you listened to show up here (the last 5).", 13, FontWeights.Normal, "Muted"));
                foreach (var r in ctl.Settings.Recent)
                {
                    var rec = r;
                    var b = Ui.Button("Join", "Ghost", (s, e) => { ctl.JoinRecent(rec, PinText); Refresh(); });
                    recentList.Children.Add(HostRow(Icons.Listen, string.IsNullOrEmpty(r.Name) ? r.HostPort : r.Name, r.HostPort, b));
                }
            }
            // outputs
            var os = new System.Text.StringBuilder();
            foreach (var o in ctl.Outputs) os.Append(o.Id).Append('|');
            if (os.ToString() != outSig)
            {
                outSig = os.ToString();
                outList.Children.Clear();
                foreach (var o in ctl.Outputs)
                {
                    var vm = o;
                    var sw = Ui.Switch("", o.ListenTicked);
                    sw.Checked += (s, e) => ctl.SetListenTicked(vm, true);
                    sw.Unchecked += (s, e) => ctl.SetListenTicked(vm, false);
                    var row = new Grid { Margin = new Thickness(0, 4, 0, 4), Tag = vm };
                    row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                    row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                    row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                    row.Children.Add(sw);
                    var n = Ui.Text(o.Name, 14, FontWeights.SemiBold, "Text");
                    n.Margin = new Thickness(6, 0, 12, 0);
                    n.VerticalAlignment = VerticalAlignment.Center;
                    Grid.SetColumn(n, 1);
                    row.Children.Add(n);
                    var st = Ui.Text("", 12.5, FontWeights.Normal, "Muted");
                    st.VerticalAlignment = VerticalAlignment.Center;
                    Grid.SetColumn(st, 2);
                    row.Children.Add(st);
                    outList.Children.Add(row);
                }
                if (ctl.Outputs.Count == 0) outList.Children.Add(Ui.Text("No outputs found. Connect earbuds in Windows Bluetooth settings.", 13, FontWeights.Normal, "Muted"));
            }
            Refresh();
        }

        public void Refresh()
        {
            visible = true;
            bool on = ctl.Listening;
            var ls = ctl.ListenStateNow;
            nowCard.Visibility = on || ls == ListenState.Kicked || ls == ListenState.NeedPin || ls == ListenState.WrongPin || ls == ListenState.WrongKey ? Visibility.Visible : Visibility.Collapsed;
            stateTitle.Text = on ? ctl.ListenHostName : "Not listening";
            string dot = ls == ListenState.Playing ? "Good" : ls == ListenState.Connecting || ls == ListenState.Reconnecting || ls == ListenState.HostPaused ? "Warn" : "Bad";
            stateDot.SetResourceReference(Shape.FillProperty, dot);
            stateText.Text = ctl.ListenText(ls);
            stopBtn.Visibility = on ? Visibility.Visible : Visibility.Collapsed;
            var c = ctl.Listen;
            if (c != null && ls == ListenState.Playing)
            {
                stats.Text = (c.Synced ? "in step · rtt " + Math.Round(c.RttMs) + " ms · playout " + c.PlayoutMs + " ms" : "buffer " + c.Buffer.TargetMs + " ms (" + ctl.Settings.Buffer + ")") +
                             " · " + (c.Rate / 1000.0) + " kHz · lost " + c.Lost + " · filled " + c.Concealed + " · dropouts " + c.Underruns +
                             (c.HostVolume != 100 ? " · host volume " + c.HostVolume + "%" : "") +
                             (c.Source == "app" && c.SourceApp != null ? " · sharing " + c.SourceApp : "");
                stats.Visibility = Visibility.Visible;
            }
            else if (ctl.Demo && on) { stats.Text = "in step · rtt 6 ms · playout 120 ms · 48 kHz · lost 0 · filled 0 · dropouts 0"; stats.Visibility = Visibility.Visible; }
            else stats.Visibility = Visibility.Collapsed;
            remoteRow.Visibility = on && (ctl.Demo || (c != null && c.RemoteAllowed)) ? Visibility.Visible : Visibility.Collapsed;
            talkBadge.Visibility = c != null && c.HostTalking ? Visibility.Visible : Visibility.Collapsed;
            string sug = ctl.ListenSuggestion;
            suggestBar.Visibility = sug != null ? Visibility.Visible : Visibility.Collapsed;
            if (sug != null) suggestText.Text = "The host suggests the " + Cap(sug) + " EQ for this.";
            pinPanel.Visibility = ls == ListenState.NeedPin || ls == ListenState.WrongPin || PinText != null ? Visibility.Visible : Visibility.Collapsed;
            ShowProblem(ctl.ListenProblem);
            joinBtn.Content = on ? "Switch" : "Join";
            foreach (var child in outList.Children)
            {
                var row = child as Grid;
                if (row == null) continue;
                var vm = row.Tag as OutputVm;
                var st = row.Children[2] as TextBlock;
                if (vm == null || st == null) continue;
                string state = ctl.ListenOutputState(vm);
                st.Text = state ?? (vm.LatencyMs > 0 ? "Auto-sync " + Math.Round(vm.LatencyMs) + " ms" : "");
                st.SetResourceReference(TextBlock.ForegroundProperty, state == "Playing" ? "Good" : state == "Can't open" ? "Bad" : "Muted");
            }
        }

        static string Cap(string s) { return string.IsNullOrEmpty(s) ? "" : char.ToUpperInvariant(s[0]) + s.Substring(1); }

        public void Animate() { }

        // The page is shown / hidden: browse for hosts only while it is on screen.
        public void Shown() { visible = true; ctl.StartBrowsing(); Rebuild(); }
        public void Hidden() { visible = false; ctl.StopBrowsing(); }
    }
}
