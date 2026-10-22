// Dual Earbuds - main window: custom title bar, navigation, tray icon, global shortcut.
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
    // One 48 px header bar: brand over the nav rail, then the page name and a live mono readout
    // ("LIVE  48 kHz · P 92 ms · 4 ears · sync ±3 ms"), then the window buttons. No page titles below it.
    public class MainView : Grid
    {
        public readonly Controller Ctl;
        readonly Dictionary<string, IPage> pages = new Dictionary<string, IPage>();
        readonly Dictionary<string, RadioButton> navButtons = new Dictionary<string, RadioButton>();
        readonly Dictionary<string, string> titles = new Dictionary<string, string>();
        readonly ContentControl host = new ContentControl();
        readonly ScrollViewer scroller;
        readonly TextBlock pageName, readout, checkCount;
        readonly Ellipse liveDot;
        public readonly Grid TitleBar;
        public readonly Button MinButton, MaxButton, CloseButton;
        public readonly Path MaxIcon;
        string current;

        public event Action<string> ThemeRequested;
        public event Action HotkeyChanged;
        public event Action ResetRequested;
        public event Action ExitRequested;   // the updater started the installer: close the app

        public const double HeaderHeight = 48;

        public MainView(Controller ctl)
        {
            Ctl = ctl;
            SetResourceReference(BackgroundProperty, "Bg");
            TextElement_SetFont(this);
            RowDefinitions.Add(new RowDefinition { Height = new GridLength(HeaderHeight) });
            RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
            ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(208) });
            ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            // header bar
            TitleBar = new Grid();
            TitleBar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(208) });
            TitleBar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            TitleBar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            TitleBar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            TitleBar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var brand = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(16, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            brand.Children.Add(Ui.Logo(18));
            var bt = Ui.Text("Dual Earbuds", 13, FontWeights.SemiBold, "Text");
            bt.Margin = new Thickness(8, 0, 6, 0);
            bt.VerticalAlignment = VerticalAlignment.Center;
            bt.TextWrapping = TextWrapping.NoWrap;
            brand.Children.Add(bt);
            var vt = Ui.Mono(AppInfo.Version, 11, "Muted");
            vt.VerticalAlignment = VerticalAlignment.Center;
            brand.Children.Add(vt);
            TitleBar.Children.Add(brand);

            pageName = Ui.Text("", 15, FontWeights.SemiBold, "Text");
            pageName.TextWrapping = TextWrapping.NoWrap;
            pageName.VerticalAlignment = VerticalAlignment.Center;
            pageName.Margin = new Thickness(24, 0, 16, 0);
            Grid.SetColumn(pageName, 1);
            TitleBar.Children.Add(pageName);

            var live = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 16, 0) };
            liveDot = new Ellipse { Width = 6, Height = 6, Margin = new Thickness(0, 0, 8, 0), VerticalAlignment = VerticalAlignment.Center };
            liveDot.SetResourceReference(Shape.FillProperty, "Accent");
            live.Children.Add(liveDot);
            readout = Ui.Mono("", 12, "Muted");
            readout.VerticalAlignment = VerticalAlignment.Center;
            live.Children.Add(readout);
            Grid.SetColumn(live, 3);
            TitleBar.Children.Add(live);

            var caps = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Top };
            MinButton = Caption(Icons.Minimize, "Caption", "Minimize");
            MaxIcon = CaptionIcon(Icons.Maximize);
            MaxButton = new Button { Content = MaxIcon, ToolTip = "Maximize" };
            MaxButton.SetResourceReference(StyleProperty, "Caption");
            CloseButton = Caption(Icons.Close, "CaptionClose", "Close");
            caps.Children.Add(MinButton);
            caps.Children.Add(MaxButton);
            caps.Children.Add(CloseButton);
            foreach (UIElement b in caps.Children) WindowChrome.SetIsHitTestVisibleInChrome(b, true);
            Grid.SetColumn(caps, 4);
            TitleBar.Children.Add(caps);
            var hair = new Border { Height = 1, VerticalAlignment = VerticalAlignment.Bottom };
            hair.SetResourceReference(Border.BackgroundProperty, "Line");
            Grid.SetColumnSpan(hair, 5);
            TitleBar.Children.Add(hair);
            Grid.SetColumnSpan(TitleBar, 2);
            Children.Add(TitleBar);

            // navigation rail
            var rail = new Border { BorderThickness = new Thickness(0, 0, 1, 0) };
            rail.SetResourceReference(Border.BorderBrushProperty, "Line");
            rail.SetResourceReference(Border.BackgroundProperty, "Bg");
            var items = new StackPanel { Margin = new Thickness(10, 12, 10, 16) };
            AddNav(items, "home", "Home", Icons.Home, false);
            AddNav(items, "phones", "Listeners", Icons.Phone, false);
            AddNav(items, "listen", "Listen", Icons.Listen, true);
            checkCount = AddNav(items, "setup-check", "Setup check", Icons.Check, true);
            AddNav(items, "settings", "Settings", Icons.Tune, false);
            AddNav(items, "diagnostics", "Diagnostics", Icons.Pulse, true);
            AddNav(items, "about", "About", Icons.Info, false);
            rail.Child = items;
            Grid.SetRow(rail, 1);
            Children.Add(rail);

            // content
            scroller = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = host, Focusable = false };
            Grid.SetRow(scroller, 1);
            Grid.SetColumn(scroller, 1);
            Children.Add(scroller);

            ctl.DevicesChanged += () => { foreach (var p in pages.Values) p.Rebuild(); };
            ctl.StateChanged += RefreshAll;
            ctl.ChecksChanged += () => { IPage p; if (pages.TryGetValue("setup-check", out p)) p.Rebuild(); RefreshAll(); };
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
            var p = new Path { Data = Geometry.Parse(data), Width = 10, Height = 10, Stretch = Stretch.Uniform, StrokeThickness = 1, SnapsToDevicePixels = true };
            p.SetBinding(Shape.StrokeProperty, new System.Windows.Data.Binding("Foreground") { RelativeSource = new System.Windows.Data.RelativeSource(System.Windows.Data.RelativeSourceMode.FindAncestor, typeof(Button), 1) });
            return p;
        }

        // Returns the muted count shown at the right of the item (empty by default).
        TextBlock AddNav(Panel parent, string key, string label, string icon, bool strokeIcon)
        {
            titles[key] = label;
            var row = new Grid();
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var ic = Ui.Icon(icon, 16, "Muted", strokeIcon);
            ic.Margin = new Thickness(0, 0, 10, 0);
            row.Children.Add(ic);
            var t = new TextBlock { Text = label, VerticalAlignment = VerticalAlignment.Center };
            Grid.SetColumn(t, 1);
            row.Children.Add(t);
            var count = Ui.Mono("", 11.5, "Muted");
            count.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(count, 2);
            row.Children.Add(count);
            var rb = new RadioButton { Content = row, GroupName = "nav", HorizontalContentAlignment = HorizontalAlignment.Stretch };
            rb.SetResourceReference(StyleProperty, "Nav");
            rb.Checked += (s, e) =>
            {
                ic.SetResourceReference(strokeIcon ? Shape.StrokeProperty : Shape.FillProperty, "Text");
                Navigate(key);
            };
            rb.Unchecked += (s, e) => ic.SetResourceReference(strokeIcon ? Shape.StrokeProperty : Shape.FillProperty, "Muted");
            navButtons[key] = rb;
            parent.Children.Add(rb);
            return count;
        }

        IPage Page(string key)
        {
            IPage p;
            if (pages.TryGetValue(key, out p)) return p;
            switch (key)
            {
                case "phones": p = new PhonesPage(Ctl); break;
                case "listen": p = new ListenPage(Ctl); break;
                case "setup-check": p = new SetupCheckPage(Ctl, Navigate); break;
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

        public string Current { get { return current; } }

        public void Navigate(string key)
        {
            if (current == key) return;
            IPage was;
            if (current != null && pages.TryGetValue(current, out was) && was is ListenPage) ((ListenPage)was).Hidden();
            current = key;
            RadioButton rb;
            if (navButtons.TryGetValue(key, out rb) && rb.IsChecked != true) rb.IsChecked = true;
            string title;
            pageName.Text = titles.TryGetValue(key, out title) ? title : "";
            var p = Page(key);
            p.Refresh();
            if (p is ListenPage) ((ListenPage)p).Shown();
            if (p is SetupCheckPage) ((SetupCheckPage)p).Shown();
            host.Content = p.Root;
            scroller.ScrollToTop();
        }

        // Screenshots: scroll so that the element tagged with this name is at the top.
        public void ScrollTo(string anchor)
        {
            IPage p;
            if (current == null || !pages.TryGetValue(current, out p)) return;
            var target = Find(p.Root, anchor);
            if (target == null) return;
            UpdateLayout();
            try
            {
                var pt = target.TransformToAncestor(host).Transform(new Point(0, 0));
                scroller.ScrollToVerticalOffset(Math.Max(0, pt.Y - 8));
                scroller.UpdateLayout();
            }
            catch (InvalidOperationException) { }
        }

        static FrameworkElement Find(DependencyObject d, string anchor)
        {
            var fe = d as FrameworkElement;
            if (fe != null && fe.Name == anchor) return fe;
            int n = VisualTreeHelper.GetChildrenCount(d);
            for (int i = 0; i < n; i++) { var r = Find(VisualTreeHelper.GetChild(d, i), anchor); if (r != null) return r; }
            return null;
        }

        public void RefreshAll()
        {
            IPage p;
            if (current != null && pages.TryGetValue(current, out p)) p.Refresh();
            bool live;
            readout.Text = Ctl.Readout(out live);
            liveDot.Visibility = live ? Visibility.Visible : Visibility.Collapsed;
            readout.SetResourceReference(TextBlock.ForegroundProperty, live ? "Text" : "Muted");
            int attention = SetupCheck.Attention(Ctl.Checks());
            checkCount.Text = attention > 0 ? attention.ToString(System.Globalization.CultureInfo.InvariantCulture) : "";
        }

        public void RebuildPhones()
        {
            IPage p;
            if (pages.TryGetValue("phones", out p)) p.Rebuild();
            if (pages.TryGetValue("home", out p)) p.Rebuild();
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
                CaptionHeight = MainView.HeaderHeight,
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
            Theme.StartFollowingSystem(Application.Current.Resources);   // "System" follows Windows live
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
            ctl.UpdateFound += v => ShowTrayTip("Dual Earbuds " + v + " is available", "Settings > Updates installs it.");

            fast = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(50) };
            // the sound keeps running minimized or in the tray; only the meters stop drawing
            fast.Tick += (s, e) => { ctl.FastTick(); if (IsVisible && WindowState != WindowState.Minimized) View.Animate(); };
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
                ShowTrayTip(ctl.Running || ctl.WantRunning ? "Sharing started" : "Sharing stopped", null);
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
            var menu = new WinForms.ContextMenuStrip { ShowImageMargin = false, Font = new System.Drawing.Font("Segoe UI", 9f), Padding = new WinForms.Padding(2) };
            trayState = new WinForms.ToolStripMenuItem("Stopped") { Enabled = false };
            menu.Items.Add(trayState);
            menu.Items.Add(new WinForms.ToolStripSeparator());
            menu.Items.Add("Open Dual Earbuds", null, (s, e) => ShowFromTray());
            trayToggle = new WinForms.ToolStripMenuItem("Start sharing", null, (s, e) => ctl.Toggle());
            menu.Items.Add(trayToggle);
            menu.Items.Add("Copy link", null, (s, e) => { var u = ctl.LinkUrl; if (u != null) try { Clipboard.SetText(u); } catch { } });
            menu.Items.Add("Setup check", null, (s, e) => { ShowFromTray(); View.Navigate("setup-check"); });
            menu.Items.Add(new WinForms.ToolStripSeparator());
            menu.Items.Add("Exit", null, (s, e) => ExitApp());
            menu.Opening += (s, e) => menu.Renderer = new TrayRenderer(Theme.IsDark);
            menu.Renderer = new TrayRenderer(Theme.IsDark);
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

        WinForms.ToolStripMenuItem trayState;

        void UpdateTray()
        {
            if (tray == null) return;
            bool on = ctl.Running || ctl.WantRunning;
            trayToggle.Text = on ? "Stop sharing" : "Start sharing";
            trayToggle.Enabled = !ctl.Listening;
            bool live;
            string r = ctl.Readout(out live);
            trayState.Text = r;
            string t = "Dual Earbuds \u00b7 " + (ctl.Listening ? "listening" : ctl.Running ? "sharing" : ctl.WantRunning ? "waiting for sound" : "stopped");
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
                    ShowTrayTip("Still running", "The sound keeps playing. Right-click this icon to exit.");
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
            Theme.StopFollowingSystem();
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
            ctl.Settings.PinOn = false;
            ctl.Settings.RemoteControl = false;
            ctl.Settings.ShowOnNetwork = false;
            ctl.Settings.SuggestEq = "";
            ctl.Settings.SourceMode = "all";
            ctl.Settings.SourceApp = "";
            ctl.Settings.TalkMode = "hold";
            ctl.Settings.Noise = "strong";
            ctl.Settings.SmartEq = "off";
            ctl.Settings.Dialogue = "off";
            ctl.Settings.Buffer = "smart";
            ctl.Settings.BufferMs = 80;
            ctl.Settings.ListenOutputs.Clear();
            // calibrations, hearing profiles and recent hosts are kept: they describe hardware and people, not preferences
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

    // The tray menu in the app's palette (flat, 1 px border, no gradients).
    class TrayRenderer : WinForms.ToolStripProfessionalRenderer
    {
        readonly bool dark;
        public TrayRenderer(bool dark) : base(new TrayColors(dark)) { this.dark = dark; RoundedEdges = false; }

        protected override void OnRenderItemText(WinForms.ToolStripItemTextRenderEventArgs e)
        {
            e.TextColor = !e.Item.Enabled ? (dark ? System.Drawing.Color.FromArgb(0x9A, 0xA3, 0xB0) : System.Drawing.Color.FromArgb(0x57, 0x60, 0x70))
                                          : (dark ? System.Drawing.Color.FromArgb(0xE7, 0xEA, 0xEF) : System.Drawing.Color.FromArgb(0x12, 0x17, 0x22));
            base.OnRenderItemText(e);
        }
    }

    class TrayColors : WinForms.ProfessionalColorTable
    {
        readonly System.Drawing.Color bg, line, hover;
        public TrayColors(bool dark)
        {
            bg = dark ? System.Drawing.Color.FromArgb(0x13, 0x17, 0x1E) : System.Drawing.Color.White;
            line = dark ? System.Drawing.Color.FromArgb(0x26, 0x2C, 0x36) : System.Drawing.Color.FromArgb(0xDF, 0xE3, 0xE8);
            hover = dark ? System.Drawing.Color.FromArgb(0x1A, 0x1F, 0x28) : System.Drawing.Color.FromArgb(0xF0, 0xF2, 0xF5);
            UseSystemColors = false;
        }
        public override System.Drawing.Color ToolStripDropDownBackground { get { return bg; } }
        public override System.Drawing.Color MenuBorder { get { return line; } }
        public override System.Drawing.Color MenuItemBorder { get { return hover; } }
        public override System.Drawing.Color MenuItemSelected { get { return hover; } }
        public override System.Drawing.Color MenuItemSelectedGradientBegin { get { return hover; } }
        public override System.Drawing.Color MenuItemSelectedGradientEnd { get { return hover; } }
        public override System.Drawing.Color SeparatorDark { get { return line; } }
        public override System.Drawing.Color SeparatorLight { get { return bg; } }
        public override System.Drawing.Color ImageMarginGradientBegin { get { return bg; } }
        public override System.Drawing.Color ImageMarginGradientMiddle { get { return bg; } }
        public override System.Drawing.Color ImageMarginGradientEnd { get { return bg; } }
    }
}
