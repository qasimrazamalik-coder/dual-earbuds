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
            var p = new RowPanel();
            p.Add(Choice("Mic noise cancelling", "Removes fans, traffic and keys from your voice when you talk over the sound.",
                new[] { "Off", "Light", "Strong" }, new[] { "off", "light", "strong" }, s.Noise, v => { s.Noise = v; ctl.SmartSettingsChanged(); }));
            p.Add(Choice("Smart EQ", "Picks the EQ of outputs set to auto: by the app that plays, by how it sounds, or both.",
                new[] { "Off", "By app", "By sound", "Both" }, new[] { "off", "app", "sound", "both" }, s.SmartEq, v => { s.SmartEq = v; ctl.SmartSettingsChanged(); }));
            p.Add(Choice("Dialogue boost", "Clearer voices on this laptop's outputs. Night also evens out loud and quiet parts.",
                new[] { "Off", "Light", "Strong", "Night" }, new[] { "off", "light", "strong", "night" }, s.Dialogue, v => { s.Dialogue = v; ctl.SmartSettingsChanged(); }));
            p.Add(Choice("Buffer when listening", "Smart learns this Wi-Fi and keeps the delay as low as is safe. Steady uses fixed rules. Manual is yours.",
                new[] { "Smart", "Steady", "Manual" }, new[] { "smart", "steady", "manual" }, s.Buffer, v => { s.Buffer = v; ctl.SmartSettingsChanged(); UpdateManual(); }));
            Slider ms;
            var slider = Ui.LabeledSlider("Buffer", JitterBuffer.MinMs, JitterBuffer.MaxMs, s.BufferMs, " ms", out ms, out manualVal);
            slider.Width = 300;
            ms.SmallChange = 10;
            ms.ValueChanged += (o, e) =>
            {
                int v = (int)(Math.Round(ms.Value / 5) * 5);
                manualVal.Text = v + " ms";
                s.BufferMs = v;
                ctl.SmartSettingsChanged();
            };
            manualRow = Kit.Row("Manual buffer", null, slider);
            p.Add(manualRow);

            // hearing profiles
            var newBtn = Kit.Secondary("New profile", (o, e) => StartTest(null));
            p.Add(Kit.Row("Hearing profiles", "A short listening test per person gently tunes the EQ of the output they use. Not a medical hearing test.", newBtn));
            profileList = new StackPanel();
            p.Add(profileList);
            testPanel = new StackPanel { Visibility = Visibility.Collapsed };
            p.Add(testPanel);
            Root.Children.Add(p.Root);
            UpdateManual();
            FillProfiles();
        }

        void UpdateManual() { manualRow.Visibility = ctl.Settings.Buffer == "manual" ? Visibility.Visible : Visibility.Collapsed; }

        static FrameworkElement Choice(string title, string text, string[] labels, string[] values, string current, Action<string> pick)
        {
            int sel = Math.Max(0, Array.IndexOf(values, current));
            return Kit.Row(title, text, Kit.Segmented("smart-" + title, labels, sel, i => pick(values[i])));
        }

        void FillProfiles()
        {
            profileList.Children.Clear();
            foreach (var p in ctl.Settings.Profiles)
            {
                var prof = p;
                var btns = new StackPanel { Orientation = Orientation.Horizontal };
                btns.Children.Add(Kit.Quiet("Retest", (o, e) => StartTest(prof.Name)));
                var del = Kit.Quiet("Delete", (o, e) =>
                {
                    if (MessageBox.Show("Delete the hearing profile \"" + prof.Name + "\"? Outputs using it go back to none.", "Dual Earbuds", MessageBoxButton.OKCancel, MessageBoxImage.Question) != MessageBoxResult.OK) return;
                    ctl.DeleteProfile(prof.Name);
                    FillProfiles();
                    ctl.UpdateStates();
                });
                del.Margin = new Thickness(4, 0, 0, 0);
                btns.Children.Add(del);
                var row = Kit.Row(p.Name, Describe(p), btns);
                row.Margin = new Thickness(16, 0, 0, 0);
                profileList.Children.Add(row);
            }
        }

        static string Describe(HearingProfile p)
        {
            double maxL = 0, maxR = 0;
            for (int i = 0; i < 7; i++) { maxL = Math.Max(maxL, p.LeftDb[i]); maxR = Math.Max(maxR, p.RightDb[i]); }
            if (maxL < 0.1 && maxR < 0.1) return "No boost needed";
            return "Up to +" + Math.Round(maxL, 1) + " dB left, +" + Math.Round(maxR, 1) + " dB right, mostly high tones";
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

        // The test runs inside the panel, as an inset area on Surface2 (no extra card).
        Border Frame(UIElement child)
        {
            var b = new Border { Padding = new Thickness(16, 14, 16, 14), Child = child };
            b.SetResourceReference(Border.BackgroundProperty, "Surface2");
            return b;
        }

        void ShowSetup()
        {
            panel.Children.Clear();
            var sp = new StackPanel();
            sp.Children.Add(Ui.Heading(name == null ? "New hearing profile" : "Retest " + name, 15));
            var d = Kit.Hint("Both earbuds in, a quiet room, laptop volume at a comfortable level. Soft tones play in one ear at a time, about 3 minutes. Not a medical hearing test.");
            d.Margin = new Thickness(0, 4, 0, 12);
            sp.Children.Add(d);
            TextBox nameBox = null;
            if (name == null)
            {
                nameBox = new TextBox { Width = 220, Text = "", MaxLength = 30 };
                sp.Children.Add(Kit.Row("Whose profile", null, nameBox));
            }
            var pick = Kit.Combo(260);
            foreach (var o in outs) pick.Items.Add(new ComboBoxItem { Content = o.Name, Tag = o.Id });
            pick.SelectedIndex = 0;
            sp.Children.Add(Kit.Row("Test with", "The earbuds this person wears.", pick));
            var err = Ui.Text("", 12, FontWeights.Normal, "Warn");
            sp.Children.Add(err);
            var btns = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 10, 0, 0) };
            var start = Kit.Primary("Start the test", (o, e) =>
            {
                if (nameBox != null)
                {
                    string n = nameBox.Text.Trim().Replace("|", " ");
                    if (n.Length == 0) { err.Text = "Give the profile a name, for example the person's."; return; }
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
            var cancel = Kit.Quiet("Cancel", (o, e) => finished(false));
            cancel.Margin = new Thickness(8, 0, 0, 0);
            btns.Children.Add(cancel);
            sp.Children.Add(btns);
            panel.Children.Add(Frame(sp));
        }

        void ShowStep()
        {
            panel.Children.Clear();
            var sp = new StackPanel();
            progress = Ui.Mono("", 12, "Muted");
            sp.Children.Add(progress);
            prompt = Ui.Heading("", 15);
            prompt.Margin = new Thickness(0, 6, 0, 4);
            sp.Children.Add(prompt);
            sp.Children.Add(Kit.Hint("Answer for the tone you just heard, or didn't. Play it again as often as you like."));
            var btns = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 14, 0, 0) };
            var yes = Kit.Primary("I heard it", (o, e) => Answer(true));
            var no = Kit.Secondary("I can't hear it", (o, e) => Answer(false));
            no.Margin = new Thickness(8, 0, 0, 0);
            var again = Kit.Quiet("Play again", (o, e) => Play());
            again.Margin = new Thickness(8, 0, 0, 0);
            var stop = Kit.Quiet("Stop the test", (o, e) => finished(false));
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
            progress.Text = "step " + step + " of 14 \u00b7 " + (ear == 0 ? "left" : "right") + " ear \u00b7 " + (HearingProfile.Freqs[freq] >= 1000 ? (HearingProfile.Freqs[freq] / 1000) + " kHz" : HearingProfile.Freqs[freq] + " Hz");
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
            sp.Children.Add(Ui.Heading("Saved \"" + p.Name + "\"", 15));
            var t = Kit.Hint("Now used on " + output.Name + ". Change it per output on Home, retest or delete it here. Not a medical hearing test: if you're worried about your hearing, see a doctor.");
            t.Margin = new Thickness(0, 4, 0, 12);
            sp.Children.Add(t);
            sp.Children.Add(Kit.Primary("Done", (o, e) => finished(true)));
            panel.Children.Add(Frame(sp));
        }
    }
}
