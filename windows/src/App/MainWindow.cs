// Dual Earbuds Pro - main window: custom title bar, navigation, tray icon, global shortcut.
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Shapes;
using System.Windows.Shell;
using System.Windows.Threading;
using WinForms = System.Windows.Forms;

namespace DualEarbuds
{
    // The window's content; also rendered on its own for screenshots.
    public class MainView : Grid
    {
        public readonly Controller Ctl;
        readonly Dictionary<string, IPage> pages = new Dictionary<string, IPage>();
        readonly Dictionary<string, RadioButton> navButtons = new Dictionary<string, RadioButton>();
        readonly ContentControl host = new ContentControl();
        readonly ScrollViewer scroller;
        readonly Ellipse footDot;
        readonly TextBlock footText;
        public readonly Grid TitleBar;
        public readonly Button MinButton, MaxButton, CloseButton;
        public readonly Path MaxIcon;
        string current;

        public event Action<string> ThemeRequested;
        public event Action HotkeyChanged;
        public event Action ResetRequested;
        public event Action ExitRequested;   // the updater started the installer: close the app

        public MainView(Controller ctl)
        {
            Ctl = ctl;
            SetResourceReference(BackgroundProperty, "Bg");
            TextElement_SetFont(this);
            RowDefinitions.Add(new RowDefinition { Height = new GridLength(44) });
            RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
            ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(232) });
            ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            // title bar
            TitleBar = new Grid();
            TitleBar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            TitleBar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var brand = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(18, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            brand.Children.Add(Ui.Logo(22));
            var bt = Ui.Heading("Dual Earbuds", 14);
            bt.Margin = new Thickness(10, 0, 8, 0);
            bt.VerticalAlignment = VerticalAlignment.Center;
            brand.Children.Add(bt);
            var ver = new Border { CornerRadius = new CornerRadius(6), Padding = new Thickness(6, 1, 6, 1), VerticalAlignment = VerticalAlignment.Center };
            ver.SetResourceReference(Border.BackgroundProperty, "AccentSoft");
            var vt = Ui.Text("PRO " + AppInfo.Version, 10.5, FontWeights.Bold, "Accent");
            ver.Child = vt;
            brand.Children.Add(ver);
            TitleBar.Children.Add(brand);

            var caps = new StackPanel { Orientation = Orientation.Horizontal };
            MinButton = Caption(Icons.Minimize, "Caption", "Minimize");
            MaxIcon = CaptionIcon(Icons.Maximize);
            MaxButton = new Button { Content = MaxIcon, ToolTip = "Maximize" };
            MaxButton.SetResourceReference(StyleProperty, "Caption");
            CloseButton = Caption(Icons.Close, "CaptionClose", "Close");
            caps.Children.Add(MinButton);
            caps.Children.Add(MaxButton);
            caps.Children.Add(CloseButton);
            foreach (UIElement b in caps.Children) WindowChrome.SetIsHitTestVisibleInChrome(b, true);
            Grid.SetColumn(caps, 1);
            TitleBar.Children.Add(caps);
            Grid.SetColumnSpan(TitleBar, 2);
            Children.Add(TitleBar);

            // navigation rail
            var nav = new DockPanel { Margin = new Thickness(14, 6, 10, 16), LastChildFill = false };
            var foot = new Border { CornerRadius = new CornerRadius(12), Padding = new Thickness(12, 10, 12, 10) };
            foot.SetResourceReference(Border.BackgroundProperty, "Surface");
            var footRow = new StackPanel { Orientation = Orientation.Horizontal };
            footDot = new Ellipse { Width = 9, Height = 9, Margin = new Thickness(0, 0, 9, 0), VerticalAlignment = VerticalAlignment.Center };
            footText = Ui.Text("Stopped", 12.5, FontWeights.SemiBold, "Text");
            footRow.Children.Add(footDot);
            footRow.Children.Add(footText);
            foot.Child = footRow;
            DockPanel.SetDock(foot, Dock.Bottom);
            nav.Children.Add(foot);
            var items = new StackPanel();
            AddNav(items, "home", "Home", Icons.Home, false);
            AddNav(items, "phones", "Phones", Icons.Phone, false);
            AddNav(items, "settings", "Settings", Icons.Tune, false);
            AddNav(items, "diagnostics", "Diagnostics", Icons.Pulse, true);
            AddNav(items, "about", "About", Icons.Info, false);
            DockPanel.SetDock(items, Dock.Top);
            nav.Children.Add(items);
            Grid.SetRow(nav, 1);
            Children.Add(nav);

            // content
            scroller = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = host, Focusable = false };
            var contentBorder = new Border { CornerRadius = new CornerRadius(18, 0, 0, 0), BorderThickness = new Thickness(1, 1, 0, 0), Child = scroller };
            contentBorder.SetResourceReference(Border.BackgroundProperty, "Bg");
            contentBorder.SetResourceReference(Border.BorderBrushProperty, "Line");
            Grid.SetRow(contentBorder, 1);
            Grid.SetColumn(contentBorder, 1);
            Children.Add(contentBorder);

            ctl.DevicesChanged += () => { foreach (var p in pages.Values) p.Rebuild(); };
            ctl.StateChanged += RefreshAll;
            ctl.UpdateChanged += () => { IPage p; if (pages.TryGetValue("settings", out p)) p.Refresh(); };
            Navigate("home");
        }

        static void TextElement_SetFont(FrameworkElement e)
        {
            TextElement.SetFontFamily(e, Theme.Body);
            TextElement.SetFontSize(e, 14);
        }

        Button Caption(string data, string style, string tip)
        {
            var b = new Button { Content = CaptionIcon(data), ToolTip = tip };
            b.SetResourceReference(StyleProperty, style);
            return b;
        }

        static Path CaptionIcon(string data)
        {
            var p = new Path { Data = Geometry.Parse(data), Width = 10, Height = 10, Stretch = Stretch.Uniform, StrokeThickness = 1.2, SnapsToDevicePixels = true };
            p.SetBinding(Shape.StrokeProperty, new System.Windows.Data.Binding("Foreground") { RelativeSource = new System.Windows.Data.RelativeSource(System.Windows.Data.RelativeSourceMode.FindAncestor, typeof(Button), 1) });
            return p;
        }

        void AddNav(Panel parent, string key, string label, string icon, bool strokeIcon)
        {
            var row = new StackPanel { Orientation = Orientation.Horizontal };
            var ic = Ui.Icon(icon, 18, "Muted", strokeIcon);
            ic.Margin = new Thickness(0, 0, 12, 0);
            row.Children.Add(ic);
            var t = new TextBlock { Text = label, VerticalAlignment = VerticalAlignment.Center, FontWeight = FontWeights.SemiBold };
            row.Children.Add(t);
            var rb = new RadioButton { Content = row, GroupName = "nav" };
            rb.SetResourceReference(StyleProperty, "Nav");
            rb.Checked += (s, e) =>
            {
                ic.SetResourceReference(strokeIcon ? Shape.StrokeProperty : Shape.FillProperty, "Accent");
                Navigate(key);
            };
            rb.Unchecked += (s, e) => ic.SetResourceReference(strokeIcon ? Shape.StrokeProperty : Shape.FillProperty, "Muted");
            navButtons[key] = rb;
            parent.Children.Add(rb);
        }

        IPage Page(string key)
        {
            IPage p;
            if (pages.TryGetValue(key, out p)) return p;
            switch (key)
            {
                case "phones": p = new PhonesPage(Ctl); break;
                case "settings":
                    p = new SettingsPage(Ctl,
                        m => { var h = ThemeRequested; if (h != null) h(m); },
                        () => { var h = HotkeyChanged; if (h != null) h(); },
                        () => { var h = ResetRequested; if (h != null) h(); },
                        () => { var h = ExitRequested; if (h != null) h(); });
                    break;
                case "diagnostics": p = new DiagnosticsPage(Ctl); break;
                case "about": p = new AboutPage(); break;
                default: p = new HomePage(Ctl, Navigate); break;
            }
            pages[key] = p;
            return p;
        }

        public void Navigate(string key)
        {
            if (current == key) return;
            current = key;
            RadioButton rb;
            if (navButtons.TryGetValue(key, out rb) && rb.IsChecked != true) rb.IsChecked = true;
            var p = Page(key);
            p.Refresh();
            host.Content = p.Root;
            scroller.ScrollToTop();
        }

        public void RefreshAll()
        {
            IPage p;
            if (current != null && pages.TryGetValue(current, out p)) p.Refresh();
            bool on = Ctl.Running;
            footText.Text = on ? (Ctl.StatusKind == StateKind.Problem ? "Mirroring · check outputs" : "Mirroring") : (Ctl.WantRunning ? "Waiting for sound" : "Stopped");
            footDot.SetResourceReference(Shape.FillProperty, on ? (Ctl.StatusKind == StateKind.Problem ? "Warn" : "Good") : (Ctl.WantRunning ? "Warn" : "Muted"));
        }

        public void RebuildPhones()
        {
            IPage p;
            if (pages.TryGetValue("phones", out p)) p.Rebuild();
            if (pages.TryGetValue("home", out p)) p.Refresh();
        }

        public void Animate()
        {
            IPage p;
            if (current != null && pages.TryGetValue(current, out p)) p.Animate();
        }

        // Forget pages so they are rebuilt (after reset).
        public void ResetPages()
        {
            string keep = current ?? "home";
            pages.Clear();
            current = null;
            Navigate(keep);
        }
    }

    public class MainWindow : Window
    {
        public readonly MainView View;
        readonly Controller ctl;
        WinForms.NotifyIcon tray;
        WinForms.ToolStripMenuItem trayToggle;
        readonly DispatcherTimer fast, slow;
        HwndSource hwndSource;
        bool hotkeyRegistered, exiting;
        int phoneSig = -1;
        string phoneDetail = "";

        const int HotkeyId = 0xDE01;
        const int WM_HOTKEY = 0x0312;
        const uint MOD_ALT = 0x1, MOD_CONTROL = 0x2, MOD_NOREPEAT = 0x4000;
        [DllImport("user32.dll")] static extern bool RegisterHotKey(IntPtr hWnd, int id, uint modifiers, uint vk);
        [DllImport("user32.dll")] static extern bool UnregisterHotKey(IntPtr hWnd, int id);

        public MainWindow(Controller ctl)
        {
            this.ctl = ctl;
            Title = "Dual Earbuds";
            Width = 1180;
            Height = 780;
            MinWidth = 900;
            MinHeight = 600;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            WindowStyle = WindowStyle.SingleBorderWindow;
            ResizeMode = ResizeMode.CanResize;
            SetResourceReference(BackgroundProperty, "Bg");
            Icon = IconFactory.WindowIcon();
            WindowChrome.SetWindowChrome(this, new WindowChrome
            {
                CaptionHeight = 44,
                ResizeBorderThickness = new Thickness(6),
                GlassFrameThickness = new Thickness(0, 0, 0, 1),
                CornerRadius = new CornerRadius(0),
                UseAeroCaptionButtons = false,
            });

            View = new MainView(ctl);
            Content = View;
            View.MinButton.Click += (s, e) => WindowState = WindowState.Minimized;
            View.MaxButton.Click += (s, e) => WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
            View.CloseButton.Click += (s, e) => Close();
            View.ThemeRequested += m => Theme.Apply(Application.Current.Resources, m);
            View.HotkeyChanged += UpdateHotkey;
            View.ResetRequested += ResetAll;
            View.ExitRequested += ExitApp;
            StateChanged += (s, e) =>
            {
                View.MaxIcon.Data = Geometry.Parse(WindowState == WindowState.Maximized ? Icons.Restore : Icons.Maximize);
                View.MaxButton.ToolTip = WindowState == WindowState.Maximized ? "Restore" : "Maximize";
                // keep content inside the screen when maximized with a custom frame
                View.Margin = WindowState == WindowState.Maximized ? new Thickness(7) : new Thickness(0);
            };

            CreateTray();
            ctl.StateChanged += UpdateTray;
            ctl.UpdateFound += v => ShowTrayTip("Dual Earbuds " + v + " is available", "Open Settings > Updates to install it.");

            fast = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(50) };
            fast.Tick += (s, e) => { ctl.FastTick(); View.Animate(); };
            slow = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
            slow.Tick += (s, e) => SlowTick();
            fast.Start();
            slow.Start();
        }

        void SlowTick()
        {
            ctl.SlowTick();
            int sig = 0;
            var sb = new System.Text.StringBuilder();
            foreach (var p in ctl.Phones) sb.Append(p.Id).Append(p.Name).Append(p.Earbuds).Append(p.Battery).Append(p.Volume).Append(p.Underruns).Append(p.RttMs / 20);
            string detail = sb.ToString();
            sig = ctl.Phones.Count;
            if (sig != phoneSig || detail != phoneDetail) { phoneSig = sig; phoneDetail = detail; View.RebuildPhones(); }
        }

        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);
            hwndSource = (HwndSource)PresentationSource.FromVisual(this);
            hwndSource.AddHook(WndProc);
            UpdateHotkey();
        }

        IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
        {
            if (msg == WM_HOTKEY && wParam.ToInt32() == HotkeyId)
            {
                ctl.Toggle();
                ShowTrayTip(ctl.Running || ctl.WantRunning ? "Mirroring started" : "Mirroring stopped", null);
                handled = true;
            }
            return IntPtr.Zero;
        }

        void UpdateHotkey()
        {
            if (hwndSource == null) return;
            if (hotkeyRegistered) { UnregisterHotKey(hwndSource.Handle, HotkeyId); hotkeyRegistered = false; }
            if (ctl.Settings.Hotkey)
            {
                hotkeyRegistered = RegisterHotKey(hwndSource.Handle, HotkeyId, MOD_CONTROL | MOD_ALT | MOD_NOREPEAT, 0x4D);
                if (!hotkeyRegistered) Log.Warn("Ctrl+Alt+M is taken by another app");
            }
        }

        void CreateTray()
        {
            var menu = new WinForms.ContextMenuStrip();
            menu.Items.Add("Open Dual Earbuds", null, (s, e) => ShowFromTray());
            trayToggle = new WinForms.ToolStripMenuItem("Start mirroring", null, (s, e) => ctl.Toggle());
            menu.Items.Add(trayToggle);
            menu.Items.Add("Copy phone link", null, (s, e) => { var u = ctl.LinkUrl; if (u != null) try { Clipboard.SetText(u); } catch { } });
            menu.Items.Add(new WinForms.ToolStripSeparator());
            menu.Items.Add("Exit", null, (s, e) => ExitApp());
            tray = new WinForms.NotifyIcon { Icon = IconFactory.TrayIcon(), Text = "Dual Earbuds", ContextMenuStrip = menu, Visible = true };
            tray.DoubleClick += (s, e) => ShowFromTray();
            tray.BalloonTipClicked += (s, e) =>
            {
                ShowFromTray();
                var u = ctl.UpdateResult;
                if (u != null && u.Status == UpdateStatus.Available) View.Navigate("settings");
            };
            UpdateTray();
        }

        void UpdateTray()
        {
            if (tray == null) return;
            bool on = ctl.Running || ctl.WantRunning;
            trayToggle.Text = on ? "Stop mirroring" : "Start mirroring";
            string t = ctl.Running ? "Dual Earbuds - mirroring" : (ctl.WantRunning ? "Dual Earbuds - waiting" : "Dual Earbuds - stopped");
            tray.Text = t.Length > 63 ? t.Substring(0, 63) : t;
        }

        void ShowTrayTip(string title, string text)
        {
            try { tray.ShowBalloonTip(2500, title, text ?? "Dual Earbuds", WinForms.ToolTipIcon.None); } catch { }
        }

        public void ShowFromTray()
        {
            Show();
            if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
            Activate();
            Topmost = true;
            Topmost = false;
        }

        protected override void OnClosing(CancelEventArgs e)
        {
            if (!exiting && ctl.Settings.CloseToTray)
            {
                e.Cancel = true;
                Hide();
                if (!ctl.Settings.TrayTipShown)
                {
                    ctl.Settings.TrayTipShown = true;
                    ctl.MarkDirty();
                    ShowTrayTip("Still running", "Dual Earbuds keeps playing here. Right-click the icon to exit.");
                }
                return;
            }
            base.OnClosing(e);
        }

        protected override void OnClosed(EventArgs e)
        {
            fast.Stop();
            slow.Stop();
            if (hotkeyRegistered && hwndSource != null) UnregisterHotKey(hwndSource.Handle, HotkeyId);
            ctl.Shutdown();
            if (tray != null) { tray.Visible = false; tray.Dispose(); }
            base.OnClosed(e);
            Application.Current.Shutdown();
        }

        public void ExitApp()
        {
            exiting = true;
            Close();
        }

        void ResetAll()
        {
            bool wasOn = ctl.Running || ctl.WantRunning;
            ctl.Stop();
            ctl.Outputs.Clear();
            ctl.Settings.SourceId = "";
            ctl.Settings.Theme = "system";
            ctl.Settings.AutoStart = true;
            ctl.Settings.CloseToTray = true;
            ctl.Settings.StartMinimized = false;
            ctl.Settings.PhoneLink = true;
            ctl.Settings.PhonePort = 8790;
            ctl.Settings.Hotkey = true;
            ctl.Settings.Party = false;
            ctl.Settings.CheckUpdates = true;
            // NetworkScope stays: it mirrors the firewall rule, which only changes with Windows' permission
            ctl.Settings.Outs.Clear();
            try { StartupReg.Set(false, ""); } catch { }
            ctl.ResetLinkKey();
            ctl.StopPhone();
            ctl.StartPhone();
            Theme.Apply(Application.Current.Resources, "system");
            ctl.RefreshDevices(true);
            ctl.MarkDirty();
            UpdateHotkey();
            View.ResetPages();
            if (wasOn) ctl.Start(true);
        }
    }
}
