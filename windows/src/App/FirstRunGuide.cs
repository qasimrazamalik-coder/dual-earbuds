// Dual Earbuds - the first-run guide: three compact steps over the page, shown once on a new install and
// re-openable from Settings > Help and data. Built from the design-system kit (one panel of rows, mono step
// numbers, one primary action).
using System;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;

namespace DualEarbuds
{
    class FirstRunGuide : Grid
    {
        public event Action Closed;

        public static readonly string[][] Steps =
        {
            new[] { "Connect your earbuds in Windows", "Pair each pair in Windows Settings > Bluetooth & devices. Two pairs can be connected at once.", "Open Bluetooth settings" },
            new[] { "Choose outputs and calibrate", "On Home, tick each pair under Outputs and press Start sharing. Calibrate each pair once so they play in step.", "Go to Home" },
            new[] { "Share with phones", "Phones on the same Wi-Fi scan the QR code on Listeners, with the Dual Earbuds app or the camera.", "Show the QR code" },
        };

        public FirstRunGuide(Action<string> navigate)
        {
            var dim = new Border { Opacity = 0.86 };
            dim.SetResourceReference(Border.BackgroundProperty, "Bg");
            Children.Add(dim);

            var card = new Border
            {
                CornerRadius = new CornerRadius(8), BorderThickness = new Thickness(1), Width = 560,
                HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, SnapsToDevicePixels = true,
            };
            card.SetResourceReference(Border.BackgroundProperty, "Surface");
            card.SetResourceReference(Border.BorderBrushProperty, "Line");
            var stack = new StackPanel();
            card.Child = stack;

            var head = new StackPanel { Margin = new Thickness(20, 18, 20, 14) };
            head.Children.Add(Ui.Text("Set up in three steps", 15, FontWeights.SemiBold, "Text"));
            head.Children.Add(Kit.Hint("Everything stays on your own Wi-Fi. You can open this guide again from Settings."));
            stack.Children.Add(head);

            string[] actions = { "bluetooth", "home", "phones" };
            for (int i = 0; i < Steps.Length; i++)
            {
                stack.Children.Add(new RowPanel.Divider());
                var row = new Grid { MinHeight = 64 };
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(44) });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                var num = Ui.Mono((i + 1).ToString(CultureInfo.InvariantCulture), 13, "Accent");
                num.Margin = new Thickness(20, 12, 0, 0);
                num.VerticalAlignment = VerticalAlignment.Top;
                row.Children.Add(num);
                var mid = new StackPanel { Margin = new Thickness(0, 10, 16, 10), VerticalAlignment = VerticalAlignment.Center };
                mid.Children.Add(Ui.Text(Steps[i][0], 14, FontWeights.Normal, "Text"));
                var hint = Kit.Hint(Steps[i][1]);
                hint.TextWrapping = TextWrapping.Wrap;
                mid.Children.Add(hint);
                Grid.SetColumn(mid, 1);
                row.Children.Add(mid);
                string act = actions[i];
                var b = Kit.Secondary(Steps[i][2], (o, e) =>
                {
                    if (act == "bluetooth") Controller.OpenSettingsPage("bluetooth");
                    else { navigate(act); Close(); }
                });
                b.Margin = new Thickness(0, 0, 20, 0);
                b.VerticalAlignment = VerticalAlignment.Center;
                Grid.SetColumn(b, 2);
                row.Children.Add(b);
                stack.Children.Add(row);
            }

            stack.Children.Add(new RowPanel.Divider());
            var foot = new DockPanel { Margin = new Thickness(20, 12, 20, 14), LastChildFill = false };
            var done = Kit.Primary("Done", (o, e) => Close());
            DockPanel.SetDock(done, Dock.Right);
            foot.Children.Add(done);
            var ask = Kit.Hint("Questions later? The Ask page answers from the built-in help.");
            ask.VerticalAlignment = VerticalAlignment.Center;
            DockPanel.SetDock(ask, Dock.Left);
            foot.Children.Add(ask);
            stack.Children.Add(foot);
            Children.Add(card);
        }

        void Close()
        {
            var h = Closed;
            if (h != null) h();
        }
    }
}
