// Dual Earbuds - "Ask": questions answered from the bundled help plus live facts about this session
// (docs/assistant-and-mcp.md). Extractive by default; optionally a local Ollama model rewrites the answer from the
// same chunks. Questions and answers are kept only while the app runs.
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;

namespace DualEarbuds
{
    class AskPage : IPage
    {
        readonly Controller ctl;
        readonly StackPanel root;
        readonly TextBox question;
        readonly Button askBtn;
        readonly TextBlock answerCount, factsCount, intro, title, answer, notes, sources, modelHead, modelText, modelNote;
        readonly StackPanel answerBox, steps, modelBox;
        readonly Grid factsSection, relatedSection;
        readonly RowPanel facts = new RowPanel(), related = new RowPanel();
        readonly CheckBox useModel;
        readonly ComboBox models;
        readonly TextBlock modelStatus;
        AssistantAnswer current;
        int requestId;
        bool modelsLoading, filling;

        public FrameworkElement Root { get { return root; } }

        public AskPage(Controller ctl)
        {
            this.ctl = ctl;
            root = Kit.Page();
            var s = ctl.Settings;

            root.Children.Add(Kit.Section("Question"));
            var qp = new RowPanel();
            var qrow = new Grid { Margin = new Thickness(16, 12, 16, 12) };
            qrow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            qrow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            question = new TextBox { FontSize = 14, MaxLength = LocalModelPrompt.MaxQuestionChars, ToolTip = "English or Roman Urdu" };
            question.KeyDown += (o, e) => { if (e.Key == Key.Enter) { e.Handled = true; Ask(question.Text); } };
            qrow.Children.Add(question);
            askBtn = Kit.Primary("Ask", (o, e) => Ask(question.Text));
            askBtn.Margin = new Thickness(8, 0, 0, 0);
            Grid.SetColumn(askBtn, 1);
            qrow.Children.Add(askBtn);
            qp.Add(qrow);
            var chips = new WrapPanel { Margin = new Thickness(12, 0, 12, 10) };
            foreach (var sq in Assistant.SuggestedQuestions)
            {
                string q = sq;
                var b = Kit.Secondary(q, (o, e) => { question.Text = q; Ask(q); });
                b.Margin = new Thickness(4);
                b.FontSize = 12.5;
                chips.Children.Add(b);
            }
            qp.Add(chips);
            root.Children.Add(qp.Root);

            root.Children.Add(Kit.Section("Answer", out answerCount, null));
            var ap = new RowPanel();
            var inner = new StackPanel { Margin = new Thickness(16, 12, 16, 14) };
            intro = Kit.Hint("Ask anything about Dual Earbuds. Answers come from the built-in help and what the app sees right now. Nothing leaves this laptop.");
            intro.TextWrapping = TextWrapping.Wrap;
            inner.Children.Add(intro);
            answerBox = new StackPanel { Visibility = Visibility.Collapsed };
            title = Ui.Text("", 15, FontWeights.SemiBold, "Text");
            title.TextWrapping = TextWrapping.Wrap;
            answerBox.Children.Add(title);
            modelBox = new StackPanel { Margin = new Thickness(0, 8, 0, 0), Visibility = Visibility.Collapsed };
            modelHead = Ui.Mono("", 11.5, "Muted");
            modelBox.Children.Add(modelHead);
            modelText = Ui.Text("", 13.5, FontWeights.Normal, "Text");
            modelText.TextWrapping = TextWrapping.Wrap;
            modelText.LineHeight = 20;
            modelText.Margin = new Thickness(0, 4, 0, 0);
            modelBox.Children.Add(modelText);
            answerBox.Children.Add(modelBox);
            answer = Ui.Text("", 13.5, FontWeights.Normal, "Text");
            answer.TextWrapping = TextWrapping.Wrap;
            answer.LineHeight = 20;
            answer.Margin = new Thickness(0, 6, 0, 0);
            answerBox.Children.Add(answer);
            steps = new StackPanel { Margin = new Thickness(0, 6, 0, 0) };
            answerBox.Children.Add(steps);
            notes = Kit.Hint("");
            notes.TextWrapping = TextWrapping.Wrap;
            notes.Margin = new Thickness(0, 6, 0, 0);
            answerBox.Children.Add(notes);
            modelNote = Kit.Hint("");
            modelNote.Margin = new Thickness(0, 6, 0, 0);
            modelNote.Visibility = Visibility.Collapsed;
            answerBox.Children.Add(modelNote);
            sources = Ui.Mono("", 11.5, "Muted");
            sources.TextWrapping = TextWrapping.Wrap;
            sources.Margin = new Thickness(0, 10, 0, 0);
            answerBox.Children.Add(sources);
            inner.Children.Add(answerBox);
            ap.Add(inner);
            root.Children.Add(ap.Root);

            factsSection = Kit.Section("Right now", out factsCount, null);
            root.Children.Add(factsSection);
            root.Children.Add(facts.Root);
            relatedSection = Kit.Section("Related");
            root.Children.Add(relatedSection);
            root.Children.Add(related.Root);

            root.Children.Add(Kit.Section("Local AI model"));
            var lm = new RowPanel();
            useModel = Ui.Switch("", s.LocalModel);
            useModel.Checked += (o, e) => { s.LocalModel = true; ctl.MarkDirty(); LoadModels(); };
            useModel.Unchecked += (o, e) => { s.LocalModel = false; ctl.MarkDirty(); };
            lm.Add(Kit.Row("Use a local AI model", "Ollama on this laptop (127.0.0.1:11434) rewrites the answer from the same help. Off: answers come straight from the help.", useModel));
            var pick = new StackPanel { Orientation = Orientation.Horizontal };
            models = Kit.Combo(200);
            models.SelectionChanged += (o, e) =>
            {
                if (filling) return;
                var m = models.SelectedItem as string;
                if (m != null) { s.LocalModelName = m; ctl.MarkDirty(); }
            };
            pick.Children.Add(models);
            var refresh = Kit.IconButton(Icons.Refresh, "Look for installed models", (o, e) => LoadModels());
            refresh.Margin = new Thickness(6, 0, 0, 0);
            pick.Children.Add(refresh);
            TextBlock mh;
            lm.Add(Kit.Row("Model", "Not checked yet.", pick, out mh));
            modelStatus = mh;
            root.Children.Add(lm.Root);

            ShowEmpty();
            if (ctl.Demo)
            {
                // screenshots and the UI test: a filled page, no network
                SetModels(new List<string> { "llama3.2:3b", "qwen2.5:3b" }, null);
                question.Text = Assistant.SuggestedQuestions[0];
                Ask(question.Text);
            }
        }

        public void Shown()
        {
            if (!ctl.Demo && ctl.Settings.LocalModel && models.Items.Count == 0) LoadModels();
            if (current == null) question.Focus();
        }

        void ShowEmpty()
        {
            answerCount.Text = "";
            intro.Visibility = Visibility.Visible;
            answerBox.Visibility = Visibility.Collapsed;
            factsSection.Visibility = facts.Root.Visibility = Visibility.Collapsed;
            relatedSection.Visibility = related.Root.Visibility = Visibility.Collapsed;
        }

        public void Ask(string q)
        {
            q = LocalModelPrompt.Clean(q);
            if (q.Length == 0) { question.Focus(); return; }
            var a = HelpContent.Assistant.Ask(q, SessionInfo.From(ctl));
            Show(a);
            MaybeAskModel(a);
        }

        void ShowSection(HelpSection sec)
        {
            question.Text = sec.Title;
            var a = HelpContent.Assistant.Show(sec, SessionInfo.From(ctl));
            Show(a);
            MaybeAskModel(a);
        }

        void Show(AssistantAnswer a)
        {
            current = a;
            requestId++;
            intro.Visibility = Visibility.Collapsed;
            answerBox.Visibility = Visibility.Visible;
            modelBox.Visibility = Visibility.Collapsed;
            modelNote.Visibility = Visibility.Collapsed;
            steps.Children.Clear();
            if (a.Found)
            {
                answerCount.Text = "from help";
                title.Text = a.Best.Title;
                answer.Text = a.AnswerText;
                answer.Visibility = a.AnswerText.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
                for (int i = 0; i < a.Steps.Count; i++)
                {
                    var row = new Grid { Margin = new Thickness(0, 3, 0, 3) };
                    row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(24) });
                    row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                    row.Children.Add(Ui.Mono((i + 1).ToString(CultureInfo.InvariantCulture), 12, "Muted"));
                    var t = Ui.Text(a.Steps[i], 13.5, FontWeights.Normal, "Text");
                    t.TextWrapping = TextWrapping.Wrap;
                    Grid.SetColumn(t, 1);
                    row.Children.Add(t);
                    steps.Children.Add(row);
                }
                notes.Text = string.Join("\n", a.Notes.ToArray());
                notes.Visibility = a.Notes.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
                var src = new List<string>();
                foreach (var x in a.Sources) src.Add(x.File + " · " + x.Title);
                sources.Text = "Source" + (src.Count == 1 ? "" : "s") + ": " + string.Join("   ", src.ToArray());
                sources.Visibility = Visibility.Visible;
            }
            else
            {
                answerCount.Text = "";
                title.Text = AssistantAnswer.NoAnswer;
                answer.Text = a.Closest.Count > 0 ? "These topics are the closest:" : "Try other words, or see Setup check and Diagnostics.";
                answer.Visibility = Visibility.Visible;
                notes.Visibility = Visibility.Collapsed;
                sources.Visibility = Visibility.Collapsed;
            }
            steps.Visibility = steps.Children.Count > 0 ? Visibility.Visible : Visibility.Collapsed;

            facts.Clear();
            foreach (var f in a.Facts)
            {
                var t = Ui.Mono(f.Text, 12, "Text");
                t.TextWrapping = TextWrapping.Wrap;
                t.Margin = new Thickness(16, 9, 16, 9);
                facts.Add(t);
            }
            factsCount.Text = a.Facts.Count > 0 ? a.Facts.Count.ToString(CultureInfo.InvariantCulture) : "";
            factsSection.Visibility = facts.Root.Visibility = a.Facts.Count > 0 ? Visibility.Visible : Visibility.Collapsed;

            related.Clear();
            var links = a.Found ? a.Related : a.Closest;
            foreach (var r in links)
            {
                var sec = r;
                var b = Kit.Quiet(r.Title, (o, e) => ShowSection(sec));
                b.HorizontalAlignment = HorizontalAlignment.Left;
                b.Margin = new Thickness(8, 4, 8, 4);
                b.ToolTip = r.File;
                related.Add(b);
            }
            relatedSection.Visibility = related.Root.Visibility = links.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        }

        // ------------------------------------------------------------------ local model
        void MaybeAskModel(AssistantAnswer a)
        {
            var s = ctl.Settings;
            if (ctl.Demo || !s.LocalModel || string.IsNullOrEmpty(s.LocalModelName) || !a.Found) return;
            int id = requestId;
            string model = s.LocalModelName;
            string prompt = LocalModelPrompt.Build(a.Question, a.Sources, a.Facts);
            var ui = Dispatcher.CurrentDispatcher;
            modelHead.Text = "Local model  ·  " + model;
            modelText.Text = "...";
            modelBox.Visibility = Visibility.Visible;
            answer.Visibility = steps.Visibility = notes.Visibility = Visibility.Collapsed;
            answerCount.Text = "local model";
            bool first = true;
            var th = new Thread(() =>
            {
                string err;
                var client = new OllamaClient();
                string text = client.Generate(model, prompt, tok =>
                {
                    try
                    {
                        ui.BeginInvoke(new Action(() =>
                        {
                            if (id != requestId) return;
                            if (first) { modelText.Text = ""; first = false; }
                            modelText.Text += tok;
                        }));
                    }
                    catch { }
                }, () => id != requestId, out err);
                try { ui.BeginInvoke(new Action(() => ModelDone(id, text, err))); } catch { }
            }) { IsBackground = true, Name = "AskModel" };
            th.Start();
        }

        void ModelDone(int id, string text, string err)
        {
            if (id != requestId || current == null) return;
            if (text != null && !OllamaClient.SaysNotInHelp(text))
            {
                modelText.Text = text.Trim();
                return;
            }
            // fall back to the extractive answer
            modelBox.Visibility = Visibility.Collapsed;
            answer.Visibility = current.AnswerText.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
            steps.Visibility = steps.Children.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
            notes.Visibility = current.Notes.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
            answerCount.Text = "from help";
            modelNote.Text = text != null ? "The local model found nothing in the help for this; showing the help text." : (err ?? "The local model didn't answer.") + " Showing the help text.";
            modelNote.Visibility = Visibility.Visible;
        }

        void LoadModels()
        {
            if (modelsLoading || ctl.Demo) return;
            modelsLoading = true;
            modelStatus.Text = "Looking for Ollama on 127.0.0.1:11434.";
            var ui = Dispatcher.CurrentDispatcher;
            var th = new Thread(() =>
            {
                List<string> list = null;
                try { list = new OllamaClient().ListModels(); } catch { }
                try { ui.BeginInvoke(new Action(() => { modelsLoading = false; SetModels(list, list == null ? "Ollama isn't answering on this laptop. Install it from ollama.com and pull a model, then press refresh." : null); })); } catch { }
            }) { IsBackground = true, Name = "AskModels" };
            th.Start();
        }

        void SetModels(List<string> list, string problem)
        {
            filling = true;
            models.Items.Clear();
            if (list != null) foreach (var m in list) models.Items.Add(m);
            var s = ctl.Settings;
            if (list != null && list.Count > 0)
            {
                int i = list.IndexOf(s.LocalModelName);
                if (i < 0) { i = 0; if (!ctl.Demo) { s.LocalModelName = list[0]; ctl.MarkDirty(); } }
                models.SelectedIndex = i;
            }
            filling = false;
            if (problem != null) modelStatus.Text = problem;
            else if (list == null || list.Count == 0) modelStatus.Text = "Ollama runs, but no model is installed. Run: ollama pull llama3.2:3b";
            else modelStatus.Text = list.Count + (list.Count == 1 ? " model" : " models") + " installed. Only the help text and the facts above are sent, and only to this laptop.";
        }

        public void Rebuild() { }
        public void Refresh() { }
        public void Animate() { }
    }
}
