// Dual Earbuds - installer window (same look as the app).
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
            // side rail: flat Surface with a hairline, like the app's navigation (no gradient)
            var side = new Border { Padding = new Thickness(20, 20, 18, 20), BorderThickness = new Thickness(0, 0, 1, 0) };
            side.SetResourceReference(Border.BackgroundProperty, "Surface");
            side.SetResourceReference(Border.BorderBrushProperty, "Line");
            var sideStack = new DockPanel { LastChildFill = false };
            var brand = new StackPanel();
            brand.Children.Add(Ui.Logo(36));
            var bt = Ui.Text("Dual Earbuds", 15, FontWeights.SemiBold, "Text");
            bt.Margin = new Thickness(0, 14, 0, 2);
            brand.Children.Add(bt);
            brand.Children.Add(Ui.Mono("setup " + AppInfo.Version, 12, "Muted"));
            DockPanel.SetDock(brand, Dock.Top);
            sideStack.Children.Add(brand);
            stepText = Ui.Mono("", 12, "Muted");
            stepText.TextWrapping = TextWrapping.Wrap;
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
            sp.Children.Add(Ui.Heading(title, 20));
            if (text != null)
            {
                var t = Ui.Text(text, 13, FontWeights.Normal, "Muted");
                t.Margin = new Thickness(0, 6, 0, 18);
                sp.Children.Add(t);
            }
            return sp;
        }

        void Buttons(params Button[] buttons)
        {
            footer.Children.Clear();
            foreach (var b in buttons) { b.Margin = new Thickness(8, 0, 0, 0); b.MinWidth = 96; footer.Children.Add(b); }
        }

        void ShowWelcome()
        {
            stepText.Text = "1 of 4 \u00b7 welcome";
            var sp = PageStack("Install Dual Earbuds " + AppInfo.Version, "Plays this laptop's sound on several pairs of earbuds at once: its own, and those of phones and laptops on the same Wi-Fi.");
            sp.Children.Add(Ui.Text("For your Windows account only. No administrator rights; takes a few seconds.", 13, FontWeights.Normal, "Text"));
            body.Content = sp;
            Buttons(Ui.Button("Cancel", "Ghost", (s, e) => Close()), Ui.Button("Next", "Primary", (s, e) => ShowLicense()));
        }

        void ShowLicense()
        {
            stepText.Text = "2 of 4 \u00b7 license";
            var sp = PageStack("License", null);
            sp.Children.Add(new FrameworkElement { Height = 10 });
            var box = new TextBox { Text = License, IsReadOnly = true, TextWrapping = TextWrapping.Wrap, Height = 240, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, FontSize = 12, FontFamily = Theme.Mono, VerticalContentAlignment = VerticalAlignment.Top };
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
            stepText.Text = modify == null ? "3 of 4 \u00b7 options" : "modify";
            options = modify != null ? modify.Options : new InstallOptions { Folder = core.DefaultFolder };
            if (modify != null) { options.Folder = modify.Folder; options.Launch = false; }
            var sp = PageStack(modify == null ? "Choose options" : "Change options", null);
            sp.Children.Add(new FrameworkElement { Height = 10 });

            sp.Children.Add(Ui.Text("Install folder", 12, FontWeights.SemiBold, "Text"));
            var folderRow = new Grid { Margin = new Thickness(0, 6, 0, 16) };
            folderRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            folderRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var folder = new TextBox { Text = options.Folder, IsEnabled = modify == null, FontFamily = Theme.Mono, FontSize = 12 };
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
            var fw = Option(sp, "Let devices on my Wi-Fi listen: firewall rule for TCP and UDP (Windows asks once)", modify == null && options.FirewallRule);
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
            stepText.Text = "installed " + (info.Version ?? "?");
            string versionNote = info.Version == AppInfo.Version
                ? "Dual Earbuds " + info.Version + " is installed in " + info.Folder + "."
                : "Dual Earbuds " + (info.Version ?? "?") + " is installed. This setup has version " + AppInfo.Version + ".";
            var sp = PageStack("Dual Earbuds is installed", versionNote);
            // one panel, one row per action (no card per item)
            var panel = new StackPanel();
            panel.Children.Add(Choice(info.Version == AppInfo.Version ? "Repair" : "Update", "Put back the app files, shortcuts and settings.", () =>
                RunTask("Repairing", () => core.Repair(payload, selfPath), info.Version == AppInfo.Version ? "Repaired" : "Updated", false)));
            panel.Children.Add(Hairline());
            panel.Children.Add(Choice("Modify", "Change shortcuts, startup and the firewall rule.", () => ShowOptions(info)));
            panel.Children.Add(Hairline());
            panel.Children.Add(Choice("Uninstall", "Remove Dual Earbuds from this PC.", () => ShowUninstall(info)));
            var frame = new Border { CornerRadius = new CornerRadius(8), BorderThickness = new Thickness(1), Child = panel };
            frame.SetResourceReference(Border.BackgroundProperty, "Surface");
            frame.SetResourceReference(Border.BorderBrushProperty, "Line");
            sp.Children.Add(frame);
            body.Content = sp;
            Buttons(Ui.Button("Close", "Ghost", (s, e) => Close()));
        }

        static Border Hairline()
        {
            var b = new Border { Height = 1 };
            b.SetResourceReference(Border.BackgroundProperty, "Line");
            return b;
        }

        // A clickable row: title + one muted line, a chevron on the right.
        Border Choice(string title, string text, Action click)
        {
            var g = new Grid();
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var left = new StackPanel();
            left.Children.Add(Ui.Text(title, 14, FontWeights.Normal, "Text"));
            var t = Ui.Text(text, 12, FontWeights.Normal, "Muted");
            t.Margin = new Thickness(0, 2, 0, 0);
            left.Children.Add(t);
            g.Children.Add(left);
            var chev = Ui.Icon("M9 6l6 6-6 6", 12, "Muted", true);
            Grid.SetColumn(chev, 1);
            g.Children.Add(chev);
            var row = new Border { Padding = new Thickness(16, 12, 16, 12), Child = g, Background = Brushes.Transparent, Cursor = System.Windows.Input.Cursors.Hand };
            row.MouseEnter += (s, e) => row.SetResourceReference(Border.BackgroundProperty, "Hover");
            row.MouseLeave += (s, e) => row.Background = Brushes.Transparent;
            row.MouseLeftButtonUp += (s, e) => click();
            return row;
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
            stepText.Text = "uninstall";
            var sp = PageStack("Uninstall Dual Earbuds", "Removes the app, its shortcuts, its startup entry and its firewall rule from " + info.Folder + ".");
            var keep = Ui.Switch("Also delete settings, calibrations and logs", false);
            keep.FontSize = 13;
            sp.Children.Add(keep);
            body.Content = sp;
            Buttons(Ui.Button("Cancel", "Quiet", (s, e) => Close()),
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
            stepText.Text = title.ToLowerInvariant();
            var sp = PageStack(title, null);
            sp.Children.Add(new FrameworkElement { Height = 14 });
            var bar = new ProgressBar { IsIndeterminate = true, Height = 2, BorderThickness = new Thickness(0) };
            bar.SetResourceReference(ForegroundProperty, "Accent");
            bar.SetResourceReference(BackgroundProperty, "Line");
            sp.Children.Add(bar);
            var log = Ui.Mono("", 12, "Muted");
            log.TextWrapping = TextWrapping.Wrap;
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
                stepText.Text = "failed";
                body.Content = PageStack("Setup didn't finish", error.Message + "\n\nClose any open Dual Earbuds window and try again.");
                Buttons(Ui.Button("Close", "Primary", (s, e) => Close()));
                return;
            }
            stepText.Text = "done";
            body.Content = PageStack(doneTitle, doneTitle.StartsWith("Dual Earbuds was removed") ? "Your settings stay unless you chose to delete them." : "Dual Earbuds is in the Start menu, and near the clock while it runs.");
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
            Theme.Apply(Application.Current.Resources, "dark");
            ScreenshotSet(dir, "");
            Theme.Apply(Application.Current.Resources, "light");
            ScreenshotSet(dir, "-light");
            Theme.Apply(Application.Current.Resources, "dark");
            return 0;
        }

        static void ScreenshotSet(string dir, string suffix)
        {
            var core = new SetupCore(@"C:\Users\You\AppData\Local");   // test mode: only used for display paths
            var win = new SetupWindow(core, new byte[0], "", SetupMode.Install);
            var root = (FrameworkElement)win.Content;
            win.Content = null;
            var frame = new Border { Child = root, Width = 760, Height = 520 };
            frame.SetResourceReference(Border.BackgroundProperty, "Bg");
            TextElement.SetFontFamily(frame, Theme.Body);
            var info = new InstalledInfo { Folder = core.DefaultFolder, Version = AppInfo.Version, Options = new InstallOptions { Folder = core.DefaultFolder, RunAtStartup = true } };

            win.ShowWelcome(); Snap(frame, Path.Combine(dir, "setup-1-welcome" + suffix + ".png"));
            win.ShowLicense(); Snap(frame, Path.Combine(dir, "setup-2-license" + suffix + ".png"));
            win.ShowOptions(null); Snap(frame, Path.Combine(dir, "setup-3-options" + suffix + ".png"));
            var log = win.ProgressPage("Installing");
            log.Text = "Copying Dual Earbuds...\nCreating shortcuts...\nRegistering with Windows...";
            Snap(frame, Path.Combine(dir, "setup-4-installing" + suffix + ".png"));
            win.options = new InstallOptions { Folder = core.DefaultFolder };
            win.Finish(null, "Dual Earbuds is installed", false); Snap(frame, Path.Combine(dir, "setup-5-finished" + suffix + ".png"));
            win.ShowMaintain(info); Snap(frame, Path.Combine(dir, "setup-6-repair-modify-uninstall" + suffix + ".png"));
            win.ShowOptions(info); Snap(frame, Path.Combine(dir, "setup-7-modify" + suffix + ".png"));
            win.ShowUninstall(info); Snap(frame, Path.Combine(dir, "setup-8-uninstall" + suffix + ".png"));
            win.Finish(null, "Dual Earbuds was removed", false); Snap(frame, Path.Combine(dir, "setup-9-removed" + suffix + ".png"));
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
