// Dual Earbuds - Settings > Smart features (docs/smart-features.md): every feature optional, each with a
// one-line explanation and clear choices. Plus the hearing test that creates hearing profiles.
using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;

namespace DualEarbuds
{
    class SmartPanel
    {
        public readonly StackPanel Root = new StackPanel();
        readonly Controller ctl;
        readonly StackPanel profileList, testPanel;
        readonly Grid manualRow;
        readonly TextBlock manualVal;
        HearingTest test;

        public SmartPanel(Controller ctl)
        {
            this.ctl = ctl;
            var s = ctl.Settings;
            var card = new StackPanel();
            var intro = Ui.Text("Everything here runs on this laptop - no cloud, no account, nothing leaves your network. Each one is optional; a choice you make by hand always wins.", 12.5, FontWeights.Normal, "Muted");
            intro.Margin = new Thickness(0, 0, 0, 8);
            card.Children.Add(intro);

            card.Children.Add(Choice("Mic noise cancelling", "Cleans fans, traffic and keyboard noise out of your voice when you talk over the music.",
                new[] { "Off", "Light", "Strong" }, new[] { "off", "light", "strong" }, s.Noise, v => { s.Noise = v; ctl.SmartSettingsChanged(); }));
            card.Children.Add(PageKit.Divider());
            card.Children.Add(Choice("Smart EQ", "Picks the EQ for outputs set to Auto: by the app that plays (Netflix, Spotify, Zoom...), by how the sound sounds, or both.",
                new[] { "Off", "By app", "By sound", "Both" }, new[] { "off", "app", "sound", "both" }, s.SmartEq, v => { s.SmartEq = v; ctl.SmartSettingsChanged(); }));
            card.Children.Add(PageKit.Divider());
            card.Children.Add(Choice("Dialogue boost", "Clearer voices over music and effects on this laptop's outputs. Night also evens out loud and quiet parts.",
                new[] { "Off", "Light", "Strong", "Night" }, new[] { "off", "light", "strong", "night" }, s.Dialogue, v => { s.Dialogue = v; ctl.SmartSettingsChanged(); }));
            card.Children.Add(PageKit.Divider());
            card.Children.Add(Choice("Smart buffer", "When listening to another device: Smart learns this network and keeps the delay as low as is safe; Steady uses fixed rules; Manual is yours.",
                new[] { "Smart", "Steady", "Manual" }, new[] { "smart", "steady", "manual" }, s.Buffer, v => { s.Buffer = v; ctl.SmartSettingsChanged(); UpdateManual(); }));
            Slider ms;
            manualRow = Ui.LabeledSlider("Buffer", JitterBuffer.MinMs, JitterBuffer.MaxMs, s.BufferMs, " ms", out ms, out manualVal);
            manualRow.Margin = new Thickness(0, 6, 0, 4);
            ms.SmallChange = 10;
            ms.ValueChanged += (o, e) =>
            {
                int v = (int)(Math.Round(ms.Value / 5) * 5);
                manualVal.Text = v + " ms";
                s.BufferMs = v;
                ctl.SmartSettingsChanged();
            };
            card.Children.Add(manualRow);
            card.Children.Add(PageKit.Divider());

            // hearing profiles
            var hh = new Grid { Margin = new Thickness(0, 6, 0, 6) };
            hh.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            hh.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var hl = new StackPanel { Margin = new Thickness(0, 0, 20, 0) };
            hl.Children.Add(Ui.Text("Hearing profiles", 14, FontWeights.SemiBold, "Text"));
            var hd = Ui.Text("A short listening test per person; the result gently tunes the EQ of the output they use (pick it on the Home page). Not a medical hearing test.", 12.5, FontWeights.Normal, "Muted");
            hd.Margin = new Thickness(0, 2, 0, 0);
            hl.Children.Add(hd);
            hh.Children.Add(hl);
            var newBtn = Ui.Button("New profile", "Ghost", (o, e) => StartTest(null));
            newBtn.VerticalAlignment = VerticalAlignment.Top;
            Grid.SetColumn(newBtn, 1);
            hh.Children.Add(newBtn);
            card.Children.Add(hh);
            profileList = new StackPanel();
            card.Children.Add(profileList);
            testPanel = new StackPanel { Visibility = Visibility.Collapsed };
            card.Children.Add(testPanel);
            Root.Children.Add(Ui.Card(card));
            UpdateManual();
            FillProfiles();
        }

        void UpdateManual() { manualRow.Visibility = ctl.Settings.Buffer == "manual" ? Visibility.Visible : Visibility.Collapsed; }

        static FrameworkElement Choice(string title, string text, string[] labels, string[] values, string current, Action<string> pick)
        {
            var sp = new StackPanel { Margin = new Thickness(0, 6, 0, 6) };
            var top = new Grid();
            top.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var left = new StackPanel { Margin = new Thickness(0, 0, 20, 0) };
            left.Children.Add(Ui.Text(title, 14, FontWeights.SemiBold, "Text"));
            var d = Ui.Text(text, 12.5, FontWeights.Normal, "Muted");
            d.Margin = new Thickness(0, 2, 0, 0);
            left.Children.Add(d);
            top.Children.Add(left);
            int sel = Math.Max(0, Array.IndexOf(values, current));
            var seg = PageKit.Segmented("smart-" + title, labels, sel, i => pick(values[i]));
            seg.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(seg, 1);
            top.Children.Add(seg);
            sp.Children.Add(top);
            return sp;
        }

        void FillProfiles()
        {
            profileList.Children.Clear();
            foreach (var p in ctl.Settings.Profiles)
            {
                var prof = p;
                var row = new Grid { Margin = new Thickness(0, 4, 0, 4) };
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                var info = new StackPanel();
                info.Children.Add(Ui.Text(p.Name, 13.5, FontWeights.SemiBold, "Text"));
                info.Children.Add(Ui.Text(Describe(p), 12, FontWeights.Normal, "Muted"));
                row.Children.Add(info);
                var retest = Ui.Button("Retest", "Ghost", (o, e) => StartTest(prof.Name));
                retest.Padding = new Thickness(12, 5, 12, 5);
                Grid.SetColumn(retest, 1);
                row.Children.Add(retest);
                var del = Ui.Button("Delete", "Ghost", (o, e) =>
                {
                    if (MessageBox.Show("Delete the hearing profile \"" + prof.Name + "\"? Outputs using it go back to no profile.", "Dual Earbuds", MessageBoxButton.OKCancel, MessageBoxImage.Question) != MessageBoxResult.OK) return;
                    ctl.DeleteProfile(prof.Name);
                    FillProfiles();
                    ctl.UpdateStates();
                });
                del.Padding = new Thickness(12, 5, 12, 5);
                del.Margin = new Thickness(8, 0, 0, 0);
                Grid.SetColumn(del, 2);
                row.Children.Add(del);
                profileList.Children.Add(row);
            }
        }

        static string Describe(HearingProfile p)
        {
            double maxL = 0, maxR = 0;
            for (int i = 0; i < 7; i++) { maxL = Math.Max(maxL, p.LeftDb[i]); maxR = Math.Max(maxR, p.RightDb[i]); }
            if (maxL < 0.1 && maxR < 0.1) return "No boost needed";
            return "Boost up to +" + Math.Round(maxL, 1) + " dB left, +" + Math.Round(maxR, 1) + " dB right (mostly high tones)";
        }

        // ------------------------------------------------------------------ hearing test
        void StartTest(string name)
        {
            if (test != null) return;
            var outs = ctl.Outputs.FindAll(o => !o.IsSource || ctl.Outputs.Count == 1);
            if (outs.Count == 0) { MessageBox.Show("Connect the earbuds first.", "Dual Earbuds"); return; }
            test = new HearingTest(ctl, testPanel, outs, name, saved =>
            {
                test = null;
                testPanel.Visibility = Visibility.Collapsed;
                testPanel.Children.Clear();
                FillProfiles();
                ctl.UpdateStates();
            });
            testPanel.Visibility = Visibility.Visible;
        }
    }

    // The test itself: pick earbuds + name, then for each ear and frequency answer "heard it" / "can't hear it".
    class HearingTest
    {
        readonly Controller ctl;
        readonly StackPanel panel;
        readonly List<OutputVm> outs;
        readonly Action<bool> finished;
        string name;
        OutputVm output;
        int ear, freq;   // ear 0 = left, 1 = right
        HearingStaircase stair;
        readonly double[] left = new double[7], right = new double[7];
        TextBlock progress, prompt;

        public HearingTest(Controller ctl, StackPanel panel, List<OutputVm> outs, string existing, Action<bool> finished)
        {
            this.ctl = ctl;
            this.panel = panel;
            this.outs = outs;
            this.finished = finished;
            name = existing;
            ShowSetup();
        }

        Border Frame(UIElement child)
        {
            var b = new Border { CornerRadius = new CornerRadius(14), Padding = new Thickness(18, 16, 18, 16), BorderThickness = new Thickness(1), Margin = new Thickness(0, 12, 0, 4), Child = child };
            b.SetResourceReference(Border.BackgroundProperty, "Surface2");
            b.SetResourceReference(Border.BorderBrushProperty, "Accent");
            return b;
        }

        void ShowSetup()
        {
            panel.Children.Clear();
            var sp = new StackPanel();
            sp.Children.Add(Ui.Heading(name == null ? "New hearing profile" : "Retest " + name, 17));
            var d = Ui.Text("Put both earbuds in, in a quiet room, and set the laptop volume to a comfortable level. You'll hear soft tones in one ear at a time - about 3 minutes. Not a medical hearing test.", 12.5, FontWeights.Normal, "Muted");
            d.Margin = new Thickness(0, 4, 0, 12);
            sp.Children.Add(d);
            TextBox nameBox = null;
            if (name == null)
            {
                nameBox = new TextBox { Width = 220, Text = "", MaxLength = 30 };
                sp.Children.Add(PageKit.Row("Whose profile", null, nameBox));
            }
            var pick = PageKit.Combo(260);
            foreach (var o in outs) pick.Items.Add(new ComboBoxItem { Content = o.Name, Tag = o.Id });
            pick.SelectedIndex = 0;
            sp.Children.Add(PageKit.Row("Test with", "The earbuds this person wears.", pick));
            var err = Ui.Text("", 12.5, FontWeights.SemiBold, "Warn");
            sp.Children.Add(err);
            var btns = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 10, 0, 0) };
            var start = Ui.Button("Start the test", "Primary", (o, e) =>
            {
                if (nameBox != null)
                {
                    string n = nameBox.Text.Trim().Replace("|", " ");
                    if (n.Length == 0) { err.Text = "Give the profile a name (for example the person's name)."; return; }
                    if (ctl.Settings.FindProfile(n) != null) { err.Text = "There is already a profile called " + n + ". Use Retest on it instead."; return; }
                    name = n;
                }
                string id = PageKit.Tag(pick);
                output = outs.Find(x => x.Id == id) ?? outs[0];
                ear = 0; freq = 0;
                stair = new HearingStaircase();
                ShowStep();
            });
            btns.Children.Add(start);
            var cancel = Ui.Button("Cancel", "Ghost", (o, e) => finished(false));
            cancel.Margin = new Thickness(8, 0, 0, 0);
            btns.Children.Add(cancel);
            sp.Children.Add(btns);
            panel.Children.Add(Frame(sp));
        }

        void ShowStep()
        {
            panel.Children.Clear();
            var sp = new StackPanel();
            progress = Ui.Text("", 11.5, FontWeights.Bold, "Accent");
            sp.Children.Add(progress);
            prompt = Ui.Heading("", 17);
            prompt.Margin = new Thickness(0, 6, 0, 4);
            sp.Children.Add(prompt);
            sp.Children.Add(Ui.Text("Answer for the tone you just heard (or didn't). Play it again as often as you like.", 12.5, FontWeights.Normal, "Muted"));
            var btns = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 14, 0, 0) };
            var yes = Ui.Button("I heard it", "Primary", (o, e) => Answer(true));
            var no = Ui.Button("I can't hear it", "Ghost", (o, e) => Answer(false));
            no.Margin = new Thickness(8, 0, 0, 0);
            var again = Ui.Button("Play again", "Ghost", (o, e) => Play());
            again.Margin = new Thickness(8, 0, 0, 0);
            var stop = Ui.Button("Stop the test", "Ghost", (o, e) => finished(false));
            stop.Margin = new Thickness(24, 0, 0, 0);
            btns.Children.Add(yes); btns.Children.Add(no); btns.Children.Add(again); btns.Children.Add(stop);
            sp.Children.Add(btns);
            panel.Children.Add(Frame(sp));
            UpdateText();
            Play();
        }

        void UpdateText()
        {
            int step = ear * 7 + freq + 1;
            double hz = HearingProfile.Freqs[freq];
            progress.Text = "STEP " + step + " OF 14 · " + (ear == 0 ? "LEFT" : "RIGHT") + " EAR";
            prompt.Text = "Can you hear the " + (hz >= 1000 ? (hz / 1000) + " kHz" : hz + " Hz") + " tone in your " + (ear == 0 ? "left" : "right") + " ear?";
        }

        void Play() { ctl.PlayTestTone(output.Id, HearingProfile.Freqs[freq], stair.LevelDb, ear == 0); }

        void Answer(bool heard)
        {
            stair.Answer(heard);
            if (!stair.Done) { Play(); return; }
            (ear == 0 ? left : right)[freq] = stair.ThresholdDb;
            freq++;
            if (freq == 7) { freq = 0; ear++; }
            if (ear == 2) { Done(); return; }
            stair = new HearingStaircase();
            UpdateText();
            Play();
        }

        void Done()
        {
            var p = HearingStaircase.ToProfile(name, left, right);
            ctl.SaveProfile(p);
            ctl.SetOutputProfile(output, p.Name);
            panel.Children.Clear();
            var sp = new StackPanel();
            sp.Children.Add(Ui.Heading("Saved \"" + p.Name + "\"", 17));
            var t = Ui.Text("It's now used on " + output.Name + ". You can switch it per output on the Home page, retest any time, or delete it. Not a medical hearing test - if you're worried about your hearing, see a doctor.", 12.5, FontWeights.Normal, "Muted");
            t.Margin = new Thickness(0, 4, 0, 12);
            sp.Children.Add(t);
            sp.Children.Add(Ui.Button("Done", "Primary", (o, e) => finished(true)));
            panel.Children.Add(Frame(sp));
        }
    }
}
