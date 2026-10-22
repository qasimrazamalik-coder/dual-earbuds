// Dual Earbuds - Setup check: the things that make sound and sync better, live, each with a status and a fix,
// and a short "For the best sound" guide. Rows come from SetupCheck (pure logic in the engine).
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Shapes;

namespace DualEarbuds
{
    class SetupCheckPage : IPage
    {
        readonly Controller ctl;
        readonly Action<string> navigate;
        readonly StackPanel root;
        readonly RowPanel checks = new RowPanel();
        readonly TextBlock count, fwNote;
        readonly Dictionary<string, CheckRow> rows = new Dictionary<string, CheckRow>();
        string sig;
        bool fwBusy;

        public FrameworkElement Root { get { return root; } }

        public SetupCheckPage(Controller ctl, Action<string> navigate)
        {
            this.ctl = ctl;
            this.navigate = navigate;
            root = Kit.Page();
            var again = Kit.Quiet("Check again", (s, e) => { Shown(); Rebuild(); });
            root.Children.Add(Kit.Section("Checks", out count, again));
            root.Children.Add(checks.Root);
            fwNote = Kit.Hint("");
            fwNote.Margin = new Thickness(16, 8, 16, 0);
            fwNote.Visibility = Visibility.Collapsed;
            root.Children.Add(fwNote);

            root.Children.Add(Kit.Section("For the best sound"));
            var guide = new RowPanel();
            for (int i = 0; i < SetupCheck.BestSoundGuide.Length; i++)
            {
                var row = new Grid { Margin = new Thickness(16, 10, 16, 10) };
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(24) });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                row.Children.Add(Ui.Mono((i + 1).ToString(CultureInfo.InvariantCulture), 12, "Muted"));
                var t = Ui.Text(SetupCheck.BestSoundGuide[i], 13, FontWeights.Normal, "Text");
                Grid.SetColumn(t, 1);
                row.Children.Add(t);
                guide.Add(row);
            }
            root.Children.Add(guide.Root);
            Rebuild();
        }

        public void Shown()
        {
            if (ctl.Demo) return;
            ctl.RefreshFirewallStatus();
            WifiReader.Refresh();
        }

        // Rows are kept by id and updated in place; the set changes only when outputs come and go.
        public void Rebuild()
        {
            var items = ctl.Checks();
            var ids = new List<string>();
            foreach (var i in items) ids.Add(i.Id);
            string now = string.Join("|", ids.ToArray());
            if (now != sig)
            {
                sig = now;
                checks.Clear();
                rows.Clear();
                foreach (var i in items)
                {
                    var r = new CheckRow(i.Id, Act);
                    rows[i.Id] = r;
                    checks.Add(r.Root);
                }
            }
            foreach (var i in items) rows[i.Id].Update(i);
            int n = SetupCheck.Attention(items);
            count.Text = n == 0 ? "all good" : n + (n == 1 ? " to look at" : " to look at");
        }

        void Act(string actionId)
        {
            if (actionId == null) return;
            if (actionId.StartsWith("calibrate:"))
            {
                string id = actionId.Substring(10);
                var o = ctl.Outputs.Find(x => x.Id == id);
                if (o == null) { navigate("home"); return; }
                var ok = MessageBox.Show("Hold one earbud of " + o.Name + " against the laptop's microphone, in a quiet room.\n\nPress OK and keep it still: 8 clicks play over about 7 seconds.",
                                         "Calibrate", MessageBoxButton.OKCancel, MessageBoxImage.Information);
                if (ok != MessageBoxResult.OK) return;
                ctl.Calibrate(o, r =>
                {
                    fwNote.Text = r.Ok ? o.Name + ": measured " + Math.Round(r.LatencyMs) + " ms." : o.Name + ": " + r.Problem;
                    fwNote.Visibility = Visibility.Visible;
                    Rebuild();
                });
                return;
            }
            if (actionId == "firewall")
            {
                if (fwBusy) return;
                fwBusy = true;
                fwNote.Text = "Windows asks for permission to update the firewall rule.";
                fwNote.Visibility = Visibility.Visible;
                string scope = ctl.NetworkCategory == "Public" ? Settings.NetScopeAll : ctl.Settings.NetworkScope;
                ctl.ApplyNetworkScope(scope, r =>
                {
                    fwBusy = false;
                    fwNote.Text = r == FirewallResult.Ok ? "Firewall rule updated (TCP and UDP)." : r == FirewallResult.Declined ? "Nothing changed: the permission prompt was declined." : "Windows didn't update the rule. See the log on Diagnostics.";
                    Rebuild();
                });
                return;
            }
            Controller.OpenSettingsPage(actionId);
        }

        public void Refresh() { Rebuild(); }
        public void Animate() { }

        class CheckRow
        {
            public readonly Grid Root;
            readonly Ellipse dot;
            readonly TextBlock word, title, detail, value;
            readonly Button action;
            string actionId;

            public CheckRow(string id, Action<string> act)
            {
                Root = new Grid { MinHeight = 64 };
                Root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(84) });
                Root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                Root.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                Root.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                var st = Kit.Status(out dot, out word);
                st.Margin = new Thickness(16, 0, 0, 0);
                Root.Children.Add(st);
                var mid = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 10, 16, 10) };
                title = Ui.Text("", 14, FontWeights.Normal, "Text");
                title.TextTrimming = TextTrimming.CharacterEllipsis;
                title.TextWrapping = TextWrapping.NoWrap;
                mid.Children.Add(title);
                detail = Kit.Hint("");
                mid.Children.Add(detail);
                Grid.SetColumn(mid, 1);
                Root.Children.Add(mid);
                value = Ui.Mono("", 12, "Text");
                value.VerticalAlignment = VerticalAlignment.Center;
                value.Margin = new Thickness(0, 0, 16, 0);
                Grid.SetColumn(value, 2);
                Root.Children.Add(value);
                action = Kit.Secondary("", (s, e) => act(actionId));
                action.Margin = new Thickness(0, 0, 16, 0);
                action.VerticalAlignment = VerticalAlignment.Center;
                Grid.SetColumn(action, 3);
                Root.Children.Add(action);
            }

            public void Update(CheckItem i)
            {
                Kit.SetStatus(dot, word, Kit.Word(i.Status), Kit.Brush(i.Status));
                title.Text = i.Title;
                detail.Text = i.Detail ?? "";
                value.Text = i.Value ?? "";
                actionId = i.ActionId;
                action.Content = i.Action ?? "";
                action.Visibility = i.Action != null ? Visibility.Visible : Visibility.Collapsed;
            }
        }
    }
}
