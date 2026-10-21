// Dual Earbuds Pro - installer window (same look as the app).
using System;
using System.Diagnostics;
using System.IO;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shell;

namespace DualEarbuds.Setup
{
    public enum SetupMode { Install, Maintain, Uninstall }

    public class SetupWindow : Window
    {
        readonly SetupCore core;
        readonly byte[] payload;
        readonly string selfPath;
        readonly ContentControl body = new ContentControl();
        readonly StackPanel footer = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        readonly TextBlock stepText;
        InstallOptions options;
        bool busy;
        public int ExitCode;

        public const string License = @"MIT License

Copyright (c) 2026 Qasim Raza Malik

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the ""Software""), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED ""AS IS"", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.";

        public SetupWindow(SetupCore core, byte[] payload, string selfPath, SetupMode mode)
        {
            this.core = core;
            this.payload = payload;
            this.selfPath = selfPath;
            Title = "Dual Earbuds Setup";
            Width = 760;
            Height = 520;
            ResizeMode = ResizeMode.NoResize;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            SetResourceReference(BackgroundProperty, "Bg");
            Icon = IconFactory.WindowIcon();
            WindowChrome.SetWindowChrome(this, new WindowChrome { CaptionHeight = 44, ResizeBorderThickness = new Thickness(0), GlassFrameThickness = new Thickness(0, 0, 0, 1), UseAeroCaptionButtons = false });
            TextElement.SetFontFamily(this, Theme.Body);

            var root = new Grid();
            root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(230) });
            root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            // side panel
            var side = new Border { Padding = new Thickness(26, 30, 22, 26) };
            side.Background = new LinearGradientBrush(Color.FromRgb(0x0E, 0x2A, 0x3A), Color.FromRgb(0x0B, 0x12, 0x20), 90);
            var sideStack = new DockPanel { LastChildFill = false };
            var brand = new StackPanel();
            brand.Children.Add(Ui.Logo(56));
            var bt = new TextBlock { Text = "Dual Earbuds", FontFamily = Theme.Display, FontSize = 22, FontWeight = FontWeights.SemiBold, Foreground = Brushes.White, Margin = new Thickness(0, 16, 0, 2) };
            brand.Children.Add(bt);
            brand.Children.Add(new TextBlock { Text = "Version " + AppInfo.Version, Foreground = new SolidColorBrush(Color.FromRgb(0x8F, 0xA3, 0xBF)), FontSize = 13 });
            DockPanel.SetDock(brand, Dock.Top);
            sideStack.Children.Add(brand);
            stepText = new TextBlock { Foreground = new SolidColorBrush(Color.FromRgb(0x14, 0xE0, 0xC2)), FontSize = 13, FontWeight = FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap };
            DockPanel.SetDock(stepText, Dock.Bottom);
            sideStack.Children.Add(stepText);
            side.Child = sideStack;
            root.Children.Add(side);

            // main
            var main = new Grid { Margin = new Thickness(30, 0, 18, 18) };
            main.RowDefinitions.Add(new RowDefinition { Height = new GridLength(44) });
            main.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
            main.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            var close = new Button { Content = new System.Windows.Shapes.Path { Data = Geometry.Parse(Icons.Close), Width = 10, Height = 10, Stretch = Stretch.Uniform, StrokeThickness = 1.2, Stroke = Brushes.Gray }, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top };
            close.SetResourceReference(StyleProperty, "CaptionClose");
            close.Click += (s, e) => { if (!busy) Close(); };
            WindowChrome.SetIsHitTestVisibleInChrome(close, true);
            main.Children.Add(close);
            Grid.SetRow(body, 1);
            main.Children.Add(body);
            footer.Margin = new Thickness(0, 12, 0, 0);
            Grid.SetRow(footer, 2);
            main.Children.Add(footer);
            Grid.SetColumn(main, 1);
            root.Children.Add(main);
            Content = root;

            var info = core.Installed();
            if (mode == SetupMode.Uninstall) ShowUninstall();
            else if (mode == SetupMode.Maintain || info != null) ShowMaintain(info);
            else ShowWelcome();
        }

        // ------------------------------------------------------------------ pages
        static StackPanel PageStack(string title, string text)
        {
            var sp = new StackPanel { Margin = new Thickness(0, 6, 12, 0) };
            sp.Children.Add(Ui.Heading(title, 26));
            if (text != null)
            {
                var t = Ui.Text(text, 14, FontWeights.Normal, "Muted");
                t.Margin = new Thickness(0, 8, 0, 18);
                sp.Children.Add(t);
            }
            return sp;
        }

        void Buttons(params Button[] buttons)
        {
            footer.Children.Clear();
            foreach (var b in buttons) { b.Margin = new Thickness(8, 0, 0, 0); b.MinWidth = 110; footer.Children.Add(b); }
        }

        void ShowWelcome()
        {
            stepText.Text = "Step 1 of 4 · Welcome";
            var sp = PageStack("Welcome", "This installs Dual Earbuds " + AppInfo.Version + " for your Windows account. It lets you play your laptop's sound on two (or more) pairs of earbuds at once - and stream to a phone over Wi-Fi.");
            sp.Children.Add(Ui.Text("No administrator rights needed. Takes a few seconds.", 13.5, FontWeights.Normal, "Text"));
            body.Content = sp;
            Buttons(Ui.Button("Cancel", "Ghost", (s, e) => Close()), Ui.Button("Next", "Primary", (s, e) => ShowLicense()));
        }

        void ShowLicense()
        {
            stepText.Text = "Step 2 of 4 · License";
            var sp = PageStack("License", null);
            sp.Children.Add(new FrameworkElement { Height = 10 });
            var box = new TextBox { Text = License, IsReadOnly = true, TextWrapping = TextWrapping.Wrap, Height = 230, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, FontSize = 12.5 };
            sp.Children.Add(box);
            var agree = Ui.Switch("I accept the license", false);
            agree.Margin = new Thickness(0, 14, 0, 0);
            sp.Children.Add(agree);
            body.Content = sp;
            var next = Ui.Button("Next", "Primary", (s, e) => ShowOptions(null));
            next.IsEnabled = false;
            agree.Checked += (s, e) => next.IsEnabled = true;
            agree.Unchecked += (s, e) => next.IsEnabled = false;
            Buttons(Ui.Button("Back", "Ghost", (s, e) => ShowWelcome()), next);
        }

        void ShowOptions(InstalledInfo modify)
        {
            stepText.Text = modify == null ? "Step 3 of 4 · Options" : "Modify";
            options = modify != null ? modify.Options : new InstallOptions { Folder = core.DefaultFolder };
            if (modify != null) { options.Folder = modify.Folder; options.Launch = false; }
            var sp = PageStack(modify == null ? "Choose options" : "Change options", null);
            sp.Children.Add(new FrameworkElement { Height = 10 });

            sp.Children.Add(Ui.Text("Install folder", 12.5, FontWeights.SemiBold, "Muted"));
            var folderRow = new Grid { Margin = new Thickness(0, 6, 0, 16) };
            folderRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            folderRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var folder = new TextBox { Text = options.Folder, IsEnabled = modify == null };
            var browse = Ui.Button("Browse", "Ghost", (s, e) =>
            {
                using (var dlg = new System.Windows.Forms.FolderBrowserDialog { Description = "Choose where to install Dual Earbuds", SelectedPath = folder.Text })
                    if (dlg.ShowDialog() == System.Windows.Forms.DialogResult.OK) folder.Text = Path.Combine(dlg.SelectedPath, "Dual Earbuds");
            });
            browse.Margin = new Thickness(8, 0, 0, 0);
            browse.IsEnabled = modify == null;
            Grid.SetColumn(browse, 1);
            folderRow.Children.Add(folder);
            folderRow.Children.Add(browse);
            sp.Children.Add(folderRow);

            var desk = Option(sp, "Desktop shortcut", options.DesktopShortcut);
            var start = Option(sp, "Start menu shortcut", options.StartMenuShortcut);
            var boot = Option(sp, "Run when Windows starts (quietly, in the tray)", options.RunAtStartup);
            var fw = Option(sp, "Let phones on my Wi-Fi connect (Windows asks once)", modify == null && options.FirewallRule);
            CheckBox launch = null;
            if (modify == null) launch = Option(sp, "Open Dual Earbuds when setup finishes", true);
            body.Content = sp;

            RoutedEventHandler go = (s, e) =>
            {
                options.Folder = folder.Text.Trim();
                options.DesktopShortcut = desk.IsChecked == true;
                options.StartMenuShortcut = start.IsChecked == true;
                options.RunAtStartup = boot.IsChecked == true;
                options.FirewallRule = fw.IsChecked == true;
                options.Launch = launch != null && launch.IsChecked == true;
                if (string.IsNullOrEmpty(options.Folder) || options.Folder.IndexOfAny(Path.GetInvalidPathChars()) >= 0 || !Path.IsPathRooted(options.Folder))
                {
                    MessageBox.Show(this, "Please choose a full folder path.", "Dual Earbuds Setup");
                    return;
                }
                RunTask(modify == null ? "Installing" : "Applying changes", () => core.Install(options, payload, selfPath), modify == null ? "Dual Earbuds is installed" : "Changes applied", options.Launch);
            };
            Buttons(Ui.Button("Back", "Ghost", (s, e) => { if (modify == null) ShowLicense(); else ShowMaintain(modify); }),
                    Ui.Button(modify == null ? "Install" : "Apply", "Primary", go));
        }

        static CheckBox Option(Panel parent, string text, bool on)
        {
            var c = Ui.Switch(text, on);
            c.FontSize = 13.5;
            c.Margin = new Thickness(0, 6, 0, 6);
            parent.Children.Add(c);
            return c;
        }

        void ShowMaintain(InstalledInfo info)
        {
            if (info == null) info = core.Installed();
            if (info == null) { ShowWelcome(); return; }
            stepText.Text = "Installed: " + (info.Version ?? "?");
            string versionNote = info.Version == AppInfo.Version
                ? "Dual Earbuds " + info.Version + " is installed in " + info.Folder + "."
                : "Dual Earbuds " + (info.Version ?? "?") + " is installed. This setup has version " + AppInfo.Version + ".";
            var sp = PageStack("Dual Earbuds is installed", versionNote);
            sp.Children.Add(Choice(info.Version == AppInfo.Version ? "Repair" : "Update", "Put back the app files, shortcuts and settings if something is broken or missing.", () =>
                RunTask("Repairing", () => core.Repair(payload, selfPath), info.Version == AppInfo.Version ? "Repaired" : "Updated", false)));
            sp.Children.Add(Choice("Modify", "Change shortcuts, startup and firewall options.", () => ShowOptions(info)));
            sp.Children.Add(Choice("Uninstall", "Remove Dual Earbuds from this PC.", () => ShowUninstall(info)));
            body.Content = sp;
            Buttons(Ui.Button("Close", "Ghost", (s, e) => Close()));
        }

        Border Choice(string title, string text, Action click)
        {
            var g = new StackPanel();
            g.Children.Add(Ui.Heading(title, 16));
            var t = Ui.Text(text, 13, FontWeights.Normal, "Muted");
            t.Margin = new Thickness(0, 3, 0, 0);
            g.Children.Add(t);
            var card = Ui.Card(g);
            card.Padding = new Thickness(18, 12, 18, 12);
            card.Margin = new Thickness(0, 0, 0, 10);
            card.Cursor = System.Windows.Input.Cursors.Hand;
            card.MouseEnter += (s, e) => card.SetResourceReference(Border.BorderBrushProperty, "Accent");
            card.MouseLeave += (s, e) => card.SetResourceReference(Border.BorderBrushProperty, "Line");
            card.MouseLeftButtonUp += (s, e) => click();
            return card;
        }

        void ShowUninstall() { ShowUninstall(null); }

        void ShowUninstall(InstalledInfo info)
        {
            if (info == null) info = core.Installed();
            if (info == null)
            {
                stepText.Text = "";
                body.Content = PageStack("Not installed", "Dual Earbuds isn't installed for this Windows account.");
                Buttons(Ui.Button("Close", "Primary", (s, e) => Close()));
                return;
            }
            stepText.Text = "Uninstall";
            var sp = PageStack("Uninstall Dual Earbuds", "This removes the app, its shortcuts and its startup entry from " + info.Folder + ".");
            var keep = Ui.Switch("Also delete my settings and logs", false);
            keep.FontSize = 13.5;
            sp.Children.Add(keep);
            body.Content = sp;
            Buttons(Ui.Button("Cancel", "Ghost", (s, e) => Close()),
                    Ui.Button("Uninstall", "Primary", (s, e) => RunTask("Uninstalling", () => core.Uninstall(keep.IsChecked == true), "Dual Earbuds was removed", false)));
        }

        // ------------------------------------------------------------------ progress
        void RunTask(string title, Action work, string doneTitle, bool launchAfter)
        {
            busy = true;
            var log = ProgressPage(title);
            core.Progress = msg => Dispatcher.BeginInvoke(new Action(() => log.Text += msg + "\n"));
            var t = new Thread(() =>
            {
                Exception error = null;
                try { work(); }
                catch (Exception ex) { error = ex; }
                Dispatcher.BeginInvoke(new Action(() => Finish(error, doneTitle, launchAfter)));
            });
            t.IsBackground = true;
            t.Start();
        }

        TextBlock ProgressPage(string title)
        {
            stepText.Text = title + "...";
            var sp = PageStack(title + "...", null);
            sp.Children.Add(new FrameworkElement { Height = 14 });
            var bar = new ProgressBar { IsIndeterminate = true, Height = 6, BorderThickness = new Thickness(0) };
            bar.SetResourceReference(ForegroundProperty, "Accent");
            bar.SetResourceReference(BackgroundProperty, "Line");
            sp.Children.Add(bar);
            var log = Ui.Text("", 13, FontWeights.Normal, "Muted");
            log.Margin = new Thickness(0, 16, 0, 0);
            sp.Children.Add(log);
            body.Content = sp;
            footer.Children.Clear();
            return log;
        }

        void Finish(Exception error, string doneTitle, bool launchAfter)
        {
            busy = false;
            if (error != null)
            {
                ExitCode = 1;
                stepText.Text = "Something went wrong";
                body.Content = PageStack("That didn't work", error.Message + "\n\nClose any open Dual Earbuds window and try again.");
                Buttons(Ui.Button("Close", "Primary", (s, e) => Close()));
                return;
            }
            stepText.Text = "Finished";
            body.Content = PageStack(doneTitle, doneTitle.StartsWith("Dual Earbuds was removed") ? "Thanks for trying Dual Earbuds." : "You'll find Dual Earbuds in the Start menu and near the clock while it runs.");
            Buttons(Ui.Button("Finish", "Primary", (s, e) =>
            {
                if (launchAfter && options != null) Launch(Path.Combine(options.Folder, SetupCore.AppExe));
                Close();
            }));
        }

        static void Launch(string exe)
        {
            try { Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true, WorkingDirectory = Path.GetDirectoryName(exe) }); } catch { }
        }

        // ------------------------------------------------------------------ docs
        // Renders every installer screen to PNG files offscreen (no window is shown, nothing is installed).
        public static int Screenshots(string dir)
        {
            Directory.CreateDirectory(dir);
            var core = new SetupCore(@"C:\Users\You\AppData\Local");   // test mode: only used for display paths
            var win = new SetupWindow(core, new byte[0], "", SetupMode.Install);
            var root = (FrameworkElement)win.Content;
            win.Content = null;
            var frame = new Border { Child = root, Width = 760, Height = 520 };
            frame.SetResourceReference(Border.BackgroundProperty, "Bg");
            TextElement.SetFontFamily(frame, Theme.Body);
            var info = new InstalledInfo { Folder = core.DefaultFolder, Version = AppInfo.Version, Options = new InstallOptions { Folder = core.DefaultFolder, RunAtStartup = true } };

            win.ShowWelcome(); Snap(frame, Path.Combine(dir, "setup-1-welcome.png"));
            win.ShowLicense(); Snap(frame, Path.Combine(dir, "setup-2-license.png"));
            win.ShowOptions(null); Snap(frame, Path.Combine(dir, "setup-3-options.png"));
            var log = win.ProgressPage("Installing");
            log.Text = "Copying Dual Earbuds...\nCreating shortcuts...\nRegistering with Windows...";
            Snap(frame, Path.Combine(dir, "setup-4-installing.png"));
            win.options = new InstallOptions { Folder = core.DefaultFolder };
            win.Finish(null, "Dual Earbuds is installed", false); Snap(frame, Path.Combine(dir, "setup-5-finished.png"));
            win.ShowMaintain(info); Snap(frame, Path.Combine(dir, "setup-6-repair-modify-uninstall.png"));
            win.ShowOptions(info); Snap(frame, Path.Combine(dir, "setup-7-modify.png"));
            win.ShowUninstall(info); Snap(frame, Path.Combine(dir, "setup-8-uninstall.png"));
            win.Finish(null, "Dual Earbuds was removed", false); Snap(frame, Path.Combine(dir, "setup-9-removed.png"));
            return 0;
        }

        static void Snap(FrameworkElement e, string path)
        {
            const double scale = 1.5;
            for (int i = 0; i < 2; i++)
            {
                e.Measure(new Size(e.Width, e.Height));
                e.Arrange(new Rect(0, 0, e.Width, e.Height));
                e.UpdateLayout();
                e.Dispatcher.Invoke(new Action(() => { }), System.Windows.Threading.DispatcherPriority.ApplicationIdle);
            }
            var bmp = new RenderTargetBitmap((int)(e.Width * scale), (int)(e.Height * scale), 96 * scale, 96 * scale, PixelFormats.Pbgra32);
            bmp.Render(e);
            var enc = new PngBitmapEncoder();
            enc.Frames.Add(BitmapFrame.Create(bmp));
            using (var fs = File.Create(path)) enc.Save(fs);
        }

        protected override void OnClosing(System.ComponentModel.CancelEventArgs e)
        {
            if (busy) { e.Cancel = true; return; }
            base.OnClosing(e);
        }
    }
}
