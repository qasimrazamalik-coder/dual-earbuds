// Dual Earbuds - small UI building blocks (code-only WPF).
using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;

namespace DualEarbuds
{
    public enum StateKind { Off, Ready, Starting, Playing, Problem, Source }

    public static class Ui
    {
        public static void Fg(FrameworkElement e, string key)
        {
            if (e is TextBlock) e.SetResourceReference(TextBlock.ForegroundProperty, key);
            else if (e is Control) e.SetResourceReference(Control.ForegroundProperty, key);
        }

        public static TextBlock Text(string s, double size, FontWeight weight, string brush)
        {
            var t = new TextBlock { Text = s, FontSize = size, FontWeight = weight, TextWrapping = TextWrapping.Wrap, FontFamily = Theme.Body };
            t.SetResourceReference(TextBlock.ForegroundProperty, brush);
            return t;
        }

        public static TextBlock Heading(string s, double size)
        {
            var t = Text(s, size, FontWeights.SemiBold, "Text");
            t.FontFamily = Theme.Display;
            return t;
        }

        public static Border Card(UIElement child)
        {
            var b = new Border { CornerRadius = new CornerRadius(18), Padding = new Thickness(20), BorderThickness = new Thickness(1), Child = child };
            b.SetResourceReference(Border.BackgroundProperty, "Surface");
            b.SetResourceReference(Border.BorderBrushProperty, "Line");
            return b;
        }

        public static string KindBrush(StateKind k)
        {
            switch (k)
            {
                case StateKind.Playing: return "Good";
                case StateKind.Problem: return "Bad";
                case StateKind.Starting: return "Warn";
                case StateKind.Source: return "Accent";
                default: return "Muted";
            }
        }

        // Rounded pill with a colored dot.
        public static Border Chip(string text, string brushKey, out TextBlock label, out Ellipse dot)
        {
            dot = new Ellipse { Width = 7, Height = 7, Margin = new Thickness(0, 0, 6, 0), VerticalAlignment = VerticalAlignment.Center };
            dot.SetResourceReference(Shape.FillProperty, brushKey);
            label = Text(text, 12, FontWeights.SemiBold, brushKey);
            label.TextWrapping = TextWrapping.NoWrap;
            var sp = new StackPanel { Orientation = Orientation.Horizontal };
            sp.Children.Add(dot);
            sp.Children.Add(label);
            var b = new Border { CornerRadius = new CornerRadius(20), Padding = new Thickness(10, 4, 10, 4), Child = sp, VerticalAlignment = VerticalAlignment.Center };
            b.SetResourceReference(Border.BackgroundProperty, "Surface2");
            return b;
        }

        public static void SetChip(TextBlock label, Ellipse dot, string text, string brushKey)
        {
            label.Text = text;
            label.SetResourceReference(TextBlock.ForegroundProperty, brushKey);
            dot.SetResourceReference(Shape.FillProperty, brushKey);
        }

        public static Path Icon(string data, double size, string brushKey, bool stroke)
        {
            var p = new Path { Data = Geometry.Parse(data), Width = size, Height = size, Stretch = Stretch.Uniform, VerticalAlignment = VerticalAlignment.Center };
            if (stroke)
            {
                p.StrokeThickness = 2;
                p.StrokeStartLineCap = PenLineCap.Round;
                p.StrokeEndLineCap = PenLineCap.Round;
                p.StrokeLineJoin = PenLineJoin.Round;
                p.SetResourceReference(Shape.StrokeProperty, brushKey);
            }
            else p.SetResourceReference(Shape.FillProperty, brushKey);
            return p;
        }

        // Battery badge: outline icon + percent, colored by level.
        public static StackPanel Battery(out TextBlock label, out Path icon)
        {
            icon = Icon(Icons.Battery, 18, "Muted", false);
            label = Text("", 12, FontWeights.SemiBold, "Muted");
            label.Margin = new Thickness(5, 0, 0, 0);
            label.VerticalAlignment = VerticalAlignment.Center;
            var sp = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            sp.Children.Add(icon);
            sp.Children.Add(label);
            return sp;
        }

        public static void SetBattery(StackPanel panel, TextBlock label, Path icon, int percent)
        {
            if (percent < 0) { panel.Visibility = Visibility.Collapsed; return; }
            panel.Visibility = Visibility.Visible;
            label.Text = percent + "%";
            string key = percent <= 15 ? "Bad" : percent <= 35 ? "Warn" : "Good";
            label.SetResourceReference(TextBlock.ForegroundProperty, key);
            icon.SetResourceReference(Shape.FillProperty, key);
        }

        public static Grid LabeledSlider(string label, double min, double max, double value, string unit, out Slider slider, out TextBlock valueText)
        {
            var g = new Grid();
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(64) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(62) });
            var l = Text(label, 13, FontWeights.Normal, "Muted");
            l.VerticalAlignment = VerticalAlignment.Center;
            slider = new Slider { Minimum = min, Maximum = max, Value = value, VerticalAlignment = VerticalAlignment.Center, SmallChange = 5, LargeChange = 25 };
            valueText = Text(Math.Round(value) + unit, 13, FontWeights.SemiBold, "Text");
            valueText.FontFamily = Theme.Display;
            valueText.TextAlignment = TextAlignment.Right;
            valueText.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(slider, 1);
            Grid.SetColumn(valueText, 2);
            g.Children.Add(l);
            g.Children.Add(slider);
            g.Children.Add(valueText);
            return g;
        }

        public static Button Button(string text, string style, RoutedEventHandler click)
        {
            var b = new Button { Content = text };
            b.SetResourceReference(FrameworkElement.StyleProperty, style);
            if (click != null) b.Click += click;
            return b;
        }

        public static CheckBox Switch(string text, bool on)
        {
            var c = new CheckBox { Content = text, IsChecked = on, FontSize = 14 };
            c.SetResourceReference(FrameworkElement.StyleProperty, "Switch");
            return c;
        }

        // QR matrix -> crisp bitmap with a 6-module quiet zone.
        public static Image Qr(string text, double size)
        {
            bool[,] m = QrCode.Encode(text);
            int n = m.GetLength(0), quiet = 6, total = n + quiet * 2, scale = 8;
            int px = total * scale;
            var pixels = new byte[px * px];
            for (int i = 0; i < pixels.Length; i++) pixels[i] = 255;
            for (int y = 0; y < n; y++)
                for (int x = 0; x < n; x++)
                    if (m[y, x])
                        for (int dy = 0; dy < scale; dy++)
                            for (int dx = 0; dx < scale; dx++)
                                pixels[((y + quiet) * scale + dy) * px + (x + quiet) * scale + dx] = 0;
            var bmp = BitmapSource.Create(px, px, 96, 96, PixelFormats.Gray8, null, pixels, px);
            bmp.Freeze();
            var img = new Image { Source = bmp, Width = size, Height = size, SnapsToDevicePixels = true };
            RenderOptions.SetBitmapScalingMode(img, BitmapScalingMode.NearestNeighbor);
            return img;
        }

        // The app mark (assets/logo): a teal source dot with a teal and a blue arc, each ending in an earbud,
        // on the dark brand tile. Drawn from the same 24x24 layers as the .ico, so every size matches.
        public static Border Logo(double size)
        {
            var tile = new Border { Width = size, Height = size, CornerRadius = new CornerRadius(size * 0.24) };
            tile.Background = new SolidColorBrush(Color.FromRgb(0x0B, 0x12, 0x20));
            var canvas = new Canvas { Width = 24, Height = 24 };
            for (int i = 0; i < Icons.Mark.Length; i++)
                canvas.Children.Add(new Path { Data = Geometry.Parse(Icons.Mark[i][1]), Fill = Icons.MarkBrush(Icons.Mark[i][0]) });
            tile.Child = new Viewbox { Child = canvas, Margin = new Thickness(size * 0.14) };
            return tile;
        }
    }

    // A horizontal level meter (rounded track + fill).
    public class Meter : FrameworkElement
    {
        double level;
        public double Level
        {
            get { return level; }
            set
            {
                double v = Math.Max(0, Math.Min(1, value));
                if (Math.Abs(v - level) < 0.004) return;
                level = v;
                InvalidateVisual();
            }
        }

        public Meter() { Height = 6; }

        protected override void OnRender(DrawingContext dc)
        {
            double w = ActualWidth, h = ActualHeight;
            var track = TryFindResource("Line") as Brush ?? Brushes.Gray;
            var fill = TryFindResource("Accent") as Brush ?? Brushes.Teal;
            dc.DrawRoundedRectangle(track, null, new Rect(0, 0, w, h), h / 2, h / 2);
            double fw = Math.Max(level > 0.01 ? h : 0, w * level);
            if (fw > 0) dc.DrawRoundedRectangle(fill, null, new Rect(0, 0, fw, h), h / 2, h / 2);
        }
    }

    public static class Icons
    {
        public const string Home = "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z";
        public const string Phone = "M16 1H8C6.34 1 5 2.34 5 4v16c0 1.66 1.34 3 3 3h8c1.66 0 3-1.34 3-3V4c0-1.66-1.34-3-3-3zm-2 20h-4v-1h4v1zm3.25-3H6.75V4h10.5v14z";
        public const string Tune = "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z";
        public const string Pulse = "M3 13h4l3-8 4 16 3-8h4";
        public const string Info = "M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z";
        public const string Battery = "M3 8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v1h1a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-1v1a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zm2 0v8h12V8z";
        public const string Headphones = "M12 3a9 9 0 0 0-9 9v7a2 2 0 0 0 2 2h2v-8H5v-1a7 7 0 0 1 14 0v1h-2v8h2a2 2 0 0 0 2-2v-7a9 9 0 0 0-9-9z";
        public const string Speaker = "M3 9v6h4l5 5V4L7 9H3zm13.5 3A4.5 4.5 0 0 0 14 8v8a4.47 4.47 0 0 0 2.5-4z";
        public const string Copy = "M16 1H4a2 2 0 0 0-2 2v14h2V3h12V1zm3 4H8a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2zm0 16H8V7h11v14z";
        public const string Wifi = "M1 9l2 2c4.97-4.97 13.03-4.97 18 0l2-2C16.93 2.93 7.08 2.93 1 9zm8 8l3 3 3-3a4.24 4.24 0 0 0-6 0zm-4-4l2 2a7.07 7.07 0 0 1 10 0l2-2C15.14 9.14 8.87 9.14 5 13z";
        public const string Listen = "M4 13a8 8 0 0 1 16 0 M4 13v5a2 2 0 0 0 2 2h1v-7H4 M20 13v5a2 2 0 0 1-2 2h-1v-7h3";
        public const string Mic = "M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.92V21h2v-3.08A7 7 0 0 0 19 11h-2z";
        public const string Refresh = "M17.65 6.35A7.95 7.95 0 0 0 12 4a8 8 0 1 0 7.73 10h-2.08A6 6 0 1 1 12 6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z";
        public const string Lock = "M18 8h-1V6a5 5 0 0 0-10 0v2H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V10a2 2 0 0 0-2-2zm-6 9a2 2 0 1 1 0-4 2 2 0 0 1 0 4zm3.1-9H8.9V6a3.1 3.1 0 0 1 6.2 0v2z";
        public const string Spark = "M12 2l2.2 6.3L20.5 10l-6.3 2.2L12 18.5l-2.2-6.3L3.5 10l6.3-1.7z";
        public const string Laptop = "M4 6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v9H4zm2 0v7h12V6zM2 17h20v1a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2z";
        // Logo layers from assets/logo/wpf-geometry.txt: { fill, path } in a 24x24 box, drawn in order.
        public static readonly string[][] Mark =
        {
            new[] { "#3B82F6", "F1 M16.45,1.581 A10.941,10.941 0 0 1 19.87,19.177 A1.906,1.906 0 0 1 17.128,16.529 A7.129,7.129 0 0 0 14.9,5.063 A1.906,1.906 0 0 1 16.45,1.581 Z M14.547,17.853 A3.953,3.953 0 1 1 22.452,17.853 A3.953,3.953 0 1 1 14.547,17.853 Z" },
            new[] { "#14E0C2", "F1 M4.13,19.177 A10.941,10.941 0 0 1 7.55,1.581 A1.906,1.906 0 0 1 9.1,5.063 A7.129,7.129 0 0 0 6.872,16.529 A1.906,1.906 0 0 1 4.13,19.177 Z M1.548,17.853 A3.953,3.953 0 1 1 9.453,17.853 A3.953,3.953 0 1 1 1.548,17.853 Z" },
            new[] { "#14E0C2", "F1 M8.753,11.576 A3.247,3.247 0 1 1 15.247,11.576 A3.247,3.247 0 1 1 8.753,11.576 Z" },
        };

        public static Brush MarkBrush(string hex)
        {
            var b = (Brush)new BrushConverter().ConvertFrom(hex);
            b.Freeze();
            return b;
        }
        public const string Minimize = "M0,5 L10,5";
        public const string Maximize = "M0.5,0.5 H9.5 V9.5 H0.5 Z";
        public const string Restore = "M2.5,2.5 H9.5 V9.5 H2.5 Z M0.5,7.5 V0.5 H7.5";
        public const string Close = "M0,0 L10,10 M10,0 L0,10";
    }
}
