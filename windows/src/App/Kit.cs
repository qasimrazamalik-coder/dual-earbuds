// Dual Earbuds - the "instrument" design system (docs/design-system.md) as code-only WPF pieces:
// section labels with a count and a hairline, panels of rows, segmented controls, status dots and the
// signature sync scope. Pages are built only from these.
using System;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Shapes;

namespace DualEarbuds
{
    static class Kit
    {
        // The page column: content starts right under the header bar.
        public static StackPanel Page()
        {
            return new StackPanel { Margin = new Thickness(24, 0, 24, 32), MaxWidth = 960, HorizontalAlignment = HorizontalAlignment.Stretch };
        }

        // "Outputs  2 of 3 on  ─────────" : 12 px semibold sentence case, muted count, 1 px hairline to the edge.
        // 24 px above a section, 8 px below its label. right: an optional quiet action at the end of the line.
        public static Grid Section(string label, out TextBlock count, FrameworkElement right)
        {
            var g = new Grid { Margin = new Thickness(0, 24, 0, 8) };
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var t = Ui.Text(label, 12, FontWeights.SemiBold, "Text");
            t.TextWrapping = TextWrapping.NoWrap;
            t.VerticalAlignment = VerticalAlignment.Center;
            g.Children.Add(t);
            count = Ui.Mono("", 12, "Muted");
            count.Margin = new Thickness(8, 0, 0, 0);
            count.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(count, 1);
            g.Children.Add(count);
            var line = new Border { Height = 1, Margin = new Thickness(12, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            line.SetResourceReference(Border.BackgroundProperty, "Line");
            Grid.SetColumn(line, 2);
            g.Children.Add(line);
            if (right != null)
            {
                right.Margin = new Thickness(8, 0, 0, 0);
                right.VerticalAlignment = VerticalAlignment.Center;
                Grid.SetColumn(right, 3);
                g.Children.Add(right);
            }
            return g;
        }

        public static Grid Section(string label)
        {
            TextBlock c;
            return Section(label, out c, null);
        }

        public static TextBlock Hint(string text)
        {
            var t = Ui.Text(text, 12, FontWeights.Normal, "Muted");
            t.Margin = new Thickness(0, 2, 0, 0);
            return t;
        }

        // Title (14) + optional one-line hint (12 muted) on the left, a control on the right. 52 px, 64 with a hint.
        public static Grid Row(string title, string hint, FrameworkElement control)
        {
            TextBlock h;
            return Row(title, hint, control, out h);
        }

        public static Grid Row(string title, string hint, FrameworkElement control, out TextBlock hintBlock)
        {
            var g = new Grid { MinHeight = string.IsNullOrEmpty(hint) ? 52 : 64 };
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var left = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(16, 10, 16, 10) };
            left.Children.Add(Ui.Text(title, 14, FontWeights.Normal, "Text"));
            hintBlock = Hint(hint ?? "");
            hintBlock.Visibility = string.IsNullOrEmpty(hint) ? Visibility.Collapsed : Visibility.Visible;
            left.Children.Add(hintBlock);
            g.Children.Add(left);
            if (control != null)
            {
                control.VerticalAlignment = VerticalAlignment.Center;
                control.Margin = new Thickness(0, 8, 16, 8);
                Grid.SetColumn(control, 1);
                g.Children.Add(control);
            }
            return g;
        }

        public static Button Primary(string text, RoutedEventHandler click) { return Ui.Button(text, "Primary", click); }
        public static Button Secondary(string text, RoutedEventHandler click) { return Ui.Button(text, "Ghost", click); }
        public static Button Quiet(string text, RoutedEventHandler click) { return Ui.Button(text, "Quiet", click); }

        public static Button IconButton(string icon, string tip, RoutedEventHandler click)
        {
            var b = new Button { Content = Ui.Icon(icon, 14, "Muted", false), ToolTip = tip, Width = 28, Padding = new Thickness(0) };
            b.SetResourceReference(FrameworkElement.StyleProperty, "Quiet");
            if (click != null) b.Click += click;
            return b;
        }

        // Segmented control: a 28 px strip; selected = Surface2 + accent text.
        public static Border Segmented(string group, string[] labels, int selected, Action<int> onPick, out RadioButton[] buttons)
        {
            var sp = new StackPanel { Orientation = Orientation.Horizontal };
            buttons = new RadioButton[labels.Length];
            for (int i = 0; i < labels.Length; i++)
            {
                int idx = i;
                var r = new RadioButton { Content = labels[i], GroupName = group, IsChecked = i == selected };
                r.SetResourceReference(FrameworkElement.StyleProperty, "Segment");
                r.Checked += (s, e) => onPick(idx);
                sp.Children.Add(r);
                buttons[i] = r;
            }
            var b = new Border { CornerRadius = new CornerRadius(6), BorderThickness = new Thickness(1), Height = 28, Child = sp, VerticalAlignment = VerticalAlignment.Center };
            b.SetResourceReference(Border.BackgroundProperty, "Bg");
            b.SetResourceReference(Border.BorderBrushProperty, "Line");
            return b;
        }

        public static Border Segmented(string group, string[] labels, int selected, Action<int> onPick)
        {
            RadioButton[] b;
            return Segmented(group, labels, selected, onPick, out b);
        }

        public static ComboBox Combo(double width)
        {
            return new ComboBox { Width = width, Height = 32, FontSize = 13, VerticalAlignment = VerticalAlignment.Center };
        }

        // 6 px dot + word.
        public static StackPanel Status(out Ellipse dot, out TextBlock word)
        {
            dot = new Ellipse { Width = 6, Height = 6, Margin = new Thickness(0, 0, 6, 0), VerticalAlignment = VerticalAlignment.Center };
            word = Ui.Text("", 12, FontWeights.Normal, "Muted");
            word.TextWrapping = TextWrapping.NoWrap;
            word.VerticalAlignment = VerticalAlignment.Center;
            var sp = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            sp.Children.Add(dot);
            sp.Children.Add(word);
            return sp;
        }

        public static void SetStatus(Ellipse dot, TextBlock word, string text, string brush)
        {
            word.Text = text;
            dot.SetResourceReference(Shape.FillProperty, brush);
        }

        public static string Brush(CheckStatus s)
        {
            switch (s)
            {
                case CheckStatus.Good: return "Good";
                case CheckStatus.Warn: return "Warn";
                case CheckStatus.Bad: return "Bad";
                default: return "Muted";
            }
        }

        public static string Word(CheckStatus s)
        {
            switch (s)
            {
                case CheckStatus.Good: return "OK";
                case CheckStatus.Warn: return "Check";
                case CheckStatus.Bad: return "Fix";
                case CheckStatus.Info: return "Info";
                default: return "...";
            }
        }

        public static string Ms(double v) { return Math.Round(v).ToString(CultureInfo.InvariantCulture) + " ms"; }

        public static string Signed(double v)
        {
            int r = (int)Math.Round(v);
            return (r > 0 ? "+" : r < 0 ? "−" : "±") + Math.Abs(r).ToString(CultureInfo.InvariantCulture) + " ms";
        }

        public static string Cap(string s) { return string.IsNullOrEmpty(s) ? "" : char.ToUpperInvariant(s[0]) + s.Substring(1); }
    }

    // A list of rows inside one panel (radius 8, 1 px Line border), separated by 1 px dividers.
    class RowPanel
    {
        public readonly Border Root;
        readonly StackPanel stack = new StackPanel();

        public RowPanel()
        {
            Root = new Border { CornerRadius = new CornerRadius(8), BorderThickness = new Thickness(1), Child = stack, SnapsToDevicePixels = true };
            Root.SetResourceReference(Border.BackgroundProperty, "Surface");
            Root.SetResourceReference(Border.BorderBrushProperty, "Line");
        }

        public int Count { get { int n = 0; foreach (var c in stack.Children) if (!(c is Divider)) n++; return n; } }

        public void Add(UIElement row)
        {
            if (stack.Children.Count > 0) stack.Children.Add(new Divider());
            stack.Children.Add(row);
        }

        // Rows can be hidden; a divider shows only between visible rows.
        public void FixDividers()
        {
            bool seen = false;
            for (int i = 0; i < stack.Children.Count; i++)
            {
                var d = stack.Children[i] as Divider;
                if (d == null) continue;
                bool next = false;
                for (int j = i + 1; j < stack.Children.Count; j++)
                {
                    var e = stack.Children[j];
                    if (e is Divider) continue;
                    next = e.Visibility == Visibility.Visible;
                    break;
                }
                seen = false;
                for (int j = i - 1; j >= 0; j--)
                {
                    var e = stack.Children[j];
                    if (e is Divider) continue;
                    if (e.Visibility == Visibility.Visible) { seen = true; break; }
                }
                d.Visibility = next && seen ? Visibility.Visible : Visibility.Collapsed;
            }
        }

        public void Clear() { stack.Children.Clear(); }
        public UIElementCollection Children { get { return stack.Children; } }

        public class Divider : Border
        {
            public Divider()
            {
                Height = 1;
                SetResourceReference(BackgroundProperty, "Line");
            }
        }
    }

    // The signature element: a +-50 ms scale with a tick at 0 and a marker at this ear's offset from the common
    // deadline (+ = late). Within +-5 ms the marker is Good, to +-20 Warn, beyond Bad; NaN = no reading (hollow).
    public class SyncScope : FrameworkElement
    {
        public const double RangeMs = 50;
        double offset = double.NaN;

        public SyncScope() { Width = 140; Height = 18; SnapsToDevicePixels = true; ToolTip = "Offset from the shared deadline (±50 ms scale)"; }

        public double OffsetMs
        {
            get { return offset; }
            set
            {
                if (double.IsNaN(value) && double.IsNaN(offset)) return;
                if (!double.IsNaN(value) && !double.IsNaN(offset) && Math.Abs(value - offset) < 0.5) return;
                offset = value;
                InvalidateVisual();
            }
        }

        public static string BrushFor(double ms)
        {
            if (double.IsNaN(ms)) return "Muted";
            double a = Math.Abs(ms);
            return a <= 5 ? "Good" : a <= 20 ? "Warn" : "Bad";
        }

        protected override void OnRender(DrawingContext dc)
        {
            double w = ActualWidth, h = ActualHeight, mid = h / 2;
            var line = TryFindResource("Line") as Brush ?? Brushes.Gray;
            var muted = TryFindResource("Muted") as Brush ?? Brushes.Gray;
            var band = TryFindResource("Surface2") as Brush ?? Brushes.Transparent;
            double cx = Math.Round(w / 2) + 0.5;
            // the +-5 ms "in step" band
            double bw = w / 2 * (5 / RangeMs);
            dc.DrawRectangle(band, null, new Rect(cx - bw, mid - 4, bw * 2, 8));
            var pen = new Pen(line, 1);
            dc.DrawLine(pen, new Point(0, mid + 0.5), new Point(w, mid + 0.5));
            for (int i = -2; i <= 2; i++)
            {
                double x = Math.Round(cx + i * (w / 2 - 1) / 2) + (i == 0 ? 0 : 0.5);
                double th = i == 0 ? 6 : 3;
                dc.DrawLine(i == 0 ? new Pen(muted, 1) : pen, new Point(x, mid - th), new Point(x, mid + th + 1));
            }
            if (double.IsNaN(offset)) return;
            var mark = TryFindResource(BrushFor(offset)) as Brush ?? Brushes.Teal;
            double v = Math.Max(-RangeMs, Math.Min(RangeMs, offset));
            double mx = cx + v / RangeMs * (w / 2 - 2);
            if (Math.Abs(offset) > RangeMs)
            {
                // off the scale: an arrow at the edge
                double dir = offset > 0 ? 1 : -1, ex = offset > 0 ? w - 1 : 1;
                var g = new StreamGeometry();
                using (var c = g.Open())
                {
                    c.BeginFigure(new Point(ex, mid + 0.5), true, true);
                    c.LineTo(new Point(ex - dir * 6, mid - 5), true, false);
                    c.LineTo(new Point(ex - dir * 6, mid + 6), true, false);
                }
                g.Freeze();
                dc.DrawGeometry(mark, null, g);
                return;
            }
            dc.DrawRectangle(mark, null, new Rect(Math.Round(mx) - 1, mid - 6, 3, 13));
        }
    }

    // One ear: icon, name + mono detail, sync scope, offset readout. Used for local outputs and remote listeners.
    class EarRow
    {
        public readonly Grid Root;
        public readonly Grid Extra;      // the right-most column for buttons / switches
        public readonly Grid Lead;       // the left-most column (a switch for outputs)
        readonly TextBlock name, detail, value;
        readonly SyncScope scope;
        readonly Path icon;

        public EarRow(string iconData, string title)
        {
            Root = new Grid { MinHeight = 64 };
            Root.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });                       // lead
            Root.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });                       // icon
            Root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });  // name
            Root.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });                       // scope
            Root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(68) });                   // value
            Root.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });                       // extra
            Lead = new Grid { Margin = new Thickness(16, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            Root.Children.Add(Lead);
            icon = Ui.Icon(iconData, 16, "Muted", false);
            icon.Margin = new Thickness(12, 0, 12, 0);
            Grid.SetColumn(icon, 1);
            Root.Children.Add(icon);
            var mid = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 10, 12, 10) };
            name = Ui.Text(title, 14, FontWeights.Normal, "Text");
            name.TextWrapping = TextWrapping.NoWrap;
            name.TextTrimming = TextTrimming.CharacterEllipsis;
            mid.Children.Add(name);
            detail = Ui.Mono("", 11.5, "Muted");
            detail.TextTrimming = TextTrimming.CharacterEllipsis;
            detail.Margin = new Thickness(0, 3, 0, 0);
            mid.Children.Add(detail);
            Grid.SetColumn(mid, 2);
            Root.Children.Add(mid);
            scope = new SyncScope { VerticalAlignment = VerticalAlignment.Center };
            Grid.SetColumn(scope, 3);
            Root.Children.Add(scope);
            value = Ui.Mono("", 12, "Text");
            value.VerticalAlignment = VerticalAlignment.Center;
            value.TextAlignment = TextAlignment.Right;
            Grid.SetColumn(value, 4);
            Root.Children.Add(value);
            Extra = new Grid { Margin = new Thickness(12, 0, 12, 0), VerticalAlignment = VerticalAlignment.Center };
            Grid.SetColumn(Extra, 5);
            Root.Children.Add(Extra);
        }

        public void SetIcon(string data) { icon.Data = Geometry.Parse(data); }

        // offsetMs NaN = no reading ("-").
        public void Update(string title, string sub, double offsetMs)
        {
            name.Text = title;
            detail.Text = sub ?? "";
            scope.OffsetMs = offsetMs;
            value.Text = double.IsNaN(offsetMs) ? "–" : Kit.Signed(offsetMs);
            value.SetResourceReference(TextBlock.ForegroundProperty, double.IsNaN(offsetMs) ? "Muted" : "Text");
        }

        public void SetOffset(double offsetMs)
        {
            scope.OffsetMs = offsetMs;
            value.Text = double.IsNaN(offsetMs) ? "\u2013" : Kit.Signed(offsetMs);
            value.SetResourceReference(TextBlock.ForegroundProperty, double.IsNaN(offsetMs) ? "Muted" : "Text");
        }

        public void Dim(bool dim) { name.SetResourceReference(TextBlock.ForegroundProperty, dim ? "Muted" : "Text"); }
    }
}
