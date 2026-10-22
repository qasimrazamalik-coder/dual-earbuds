// Dual Earbuds - colors, fonts and control styles (dark + light), built at runtime.
using System;
using System.Windows;
using System.Windows.Markup;
using System.Windows.Media;
using Microsoft.Win32;

namespace DualEarbuds
{
    public static class Theme
    {
        public static bool IsDark { get; private set; }
        public static readonly FontFamily Body = new FontFamily("Segoe UI Variable Text, Segoe UI");
        // One family for everything (design-system.md "Type"); Display is kept as an alias for old call sites.
        public static readonly FontFamily Display = Body;
        public static readonly FontFamily Mono = new FontFamily("Cascadia Mono, Consolas");

        static readonly string[] Keys = { "Bg", "Surface", "Surface2", "Line", "Text", "Muted", "Accent", "AccentInk", "AccentSoft", "Warn", "Bad", "Good", "Hover", "Pressed", "QrBg" };

        // Bg and Surface differ subtly; panels are separated by 1 px Line borders, not brightness jumps.
        // Muted is >= 4.5:1 on Surface and Surface2 in both themes; light Accent is dark enough for text on white.
        static readonly string[] DarkColors = { "#0D1015", "#13171E", "#1A1F28", "#262C36", "#E7EAEF", "#9AA3B0", "#14E0C2", "#03211C", "#1F14E0C2", "#E9B04B", "#F07171", "#3DD68C", "#181D25", "#20262F", "#FFFFFF" };
        static readonly string[] LightColors = { "#F5F6F8", "#FFFFFF", "#F0F2F5", "#DFE3E8", "#121722", "#576070", "#0B7F76", "#FFFFFF", "#170B7F76", "#9A5B05", "#C42B2B", "#167A3E", "#EEF0F3", "#E3E7EC", "#FFFFFF" };

        public static bool SystemPrefersDark()
        {
            try
            {
                using (var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize"))
                {
                    object v = k == null ? null : k.GetValue("AppsUseLightTheme");
                    return v is int ? (int)v == 0 : true;
                }
            }
            catch { return true; }
        }

        public static string Mode { get; private set; }
        public static event Action Changed;

        // mode: system | dark | light
        public static void Apply(ResourceDictionary res, string mode)
        {
            Mode = mode == "dark" || mode == "light" ? mode : "system";
            IsDark = mode == "dark" || (mode != "light" && SystemPrefersDark());
            string[] colors = IsDark ? DarkColors : LightColors;
            for (int i = 0; i < Keys.Length; i++)
            {
                var c = (Color)ColorConverter.ConvertFromString(colors[i]);
                var existing = res[Keys[i]] as SolidColorBrush;
                if (existing != null && !existing.IsFrozen) existing.Color = c;
                else res[Keys[i]] = new SolidColorBrush(c);
            }
            if (res["Styles.Loaded"] == null)
            {
                res.MergedDictionaries.Add((ResourceDictionary)XamlReader.Parse(StylesXaml));
                res["Styles.Loaded"] = true;
            }
            var h = Changed;
            if (h != null) h();
        }

        // "System" follows Windows' app mode (HKCU\...\Themes\Personalize\AppsUseLightTheme) live.
        static ResourceDictionary followRes;
        static System.Windows.Threading.Dispatcher followUi;
        static UserPreferenceChangedEventHandler followHandler;

        public static void StartFollowingSystem(ResourceDictionary res)
        {
            if (followHandler != null) return;
            followRes = res;
            followUi = System.Windows.Threading.Dispatcher.CurrentDispatcher;
            followHandler = (s, e) =>
            {
                if (e.Category != UserPreferenceCategory.General && e.Category != UserPreferenceCategory.VisualStyle && e.Category != UserPreferenceCategory.Color) return;
                var d = followUi;
                if (d == null) return;
                try
                {
                    d.BeginInvoke(new Action(() =>
                    {
                        if (Mode != "system" || followRes == null) return;
                        bool dark = SystemPrefersDark();
                        if (dark != IsDark) Apply(followRes, "system");
                    }));
                }
                catch { }
            };
            SystemEvents.UserPreferenceChanged += followHandler;   // static event: removed again in StopFollowingSystem
        }

        public static void StopFollowingSystem()
        {
            if (followHandler == null) return;
            SystemEvents.UserPreferenceChanged -= followHandler;
            followHandler = null;
            followRes = null;
            followUi = null;
        }

        public static Brush B(string key) { return (Brush)Application.Current.Resources[key]; }

        const string StylesXaml = @"
<ResourceDictionary xmlns='http://schemas.microsoft.com/winfx/2006/xaml/presentation'
                    xmlns:x='http://schemas.microsoft.com/winfx/2006/xaml'>

  <!-- Buttons: 32 px, radius 6. One Primary per view; everything else Ghost (outline) or Quiet (text). -->
  <Style x:Key='Primary' TargetType='Button'>
    <Setter Property='Foreground' Value='{DynamicResource AccentInk}'/>
    <Setter Property='Background' Value='{DynamicResource Accent}'/>
    <Setter Property='FontWeight' Value='SemiBold'/>
    <Setter Property='FontSize' Value='13'/>
    <Setter Property='Height' Value='32'/>
    <Setter Property='Padding' Value='14,0'/>
    <Setter Property='Cursor' Value='Hand'/>
    <Setter Property='FocusVisualStyle' Value='{x:Null}'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='Button'>
          <Grid>
            <Border x:Name='b' Background='{TemplateBinding Background}' CornerRadius='6' Padding='{TemplateBinding Padding}'>
              <ContentPresenter HorizontalAlignment='Center' VerticalAlignment='Center'/>
            </Border>
            <Border x:Name='focus' CornerRadius='8' Margin='-3' BorderThickness='2' BorderBrush='{DynamicResource Accent}' Visibility='Collapsed'/>
          </Grid>
          <ControlTemplate.Triggers>
            <Trigger Property='IsMouseOver' Value='True'><Setter TargetName='b' Property='Opacity' Value='0.92'/></Trigger>
            <Trigger Property='IsPressed' Value='True'><Setter TargetName='b' Property='Opacity' Value='0.84'/></Trigger>
            <Trigger Property='IsKeyboardFocused' Value='True'><Setter TargetName='focus' Property='Visibility' Value='Visible'/></Trigger>
            <Trigger Property='IsEnabled' Value='False'><Setter TargetName='b' Property='Opacity' Value='0.45'/></Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <Style x:Key='Ghost' TargetType='Button'>
    <Setter Property='Foreground' Value='{DynamicResource Text}'/>
    <Setter Property='Background' Value='Transparent'/>
    <Setter Property='BorderBrush' Value='{DynamicResource Line}'/>
    <Setter Property='FontSize' Value='13'/>
    <Setter Property='Height' Value='32'/>
    <Setter Property='Padding' Value='12,0'/>
    <Setter Property='Cursor' Value='Hand'/>
    <Setter Property='FocusVisualStyle' Value='{x:Null}'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='Button'>
          <Border x:Name='b' Background='{TemplateBinding Background}' BorderBrush='{TemplateBinding BorderBrush}' BorderThickness='1' CornerRadius='6' Padding='{TemplateBinding Padding}'>
            <ContentPresenter HorizontalAlignment='Center' VerticalAlignment='Center'/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property='IsMouseOver' Value='True'><Setter TargetName='b' Property='Background' Value='{DynamicResource Hover}'/></Trigger>
            <Trigger Property='IsPressed' Value='True'><Setter TargetName='b' Property='Background' Value='{DynamicResource Pressed}'/></Trigger>
            <Trigger Property='IsKeyboardFocused' Value='True'><Setter TargetName='b' Property='BorderBrush' Value='{DynamicResource Accent}'/></Trigger>
            <Trigger Property='IsEnabled' Value='False'><Setter TargetName='b' Property='Opacity' Value='0.45'/></Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <Style x:Key='Quiet' TargetType='Button'>
    <Setter Property='Foreground' Value='{DynamicResource Muted}'/>
    <Setter Property='Background' Value='Transparent'/>
    <Setter Property='FontSize' Value='13'/>
    <Setter Property='Height' Value='28'/>
    <Setter Property='Padding' Value='8,0'/>
    <Setter Property='Cursor' Value='Hand'/>
    <Setter Property='FocusVisualStyle' Value='{x:Null}'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='Button'>
          <Border x:Name='b' Background='{TemplateBinding Background}' BorderThickness='1' BorderBrush='Transparent' CornerRadius='6' Padding='{TemplateBinding Padding}'>
            <ContentPresenter HorizontalAlignment='Center' VerticalAlignment='Center'/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property='IsMouseOver' Value='True'>
              <Setter TargetName='b' Property='Background' Value='{DynamicResource Hover}'/>
              <Setter Property='Foreground' Value='{DynamicResource Text}'/>
            </Trigger>
            <Trigger Property='IsKeyboardFocused' Value='True'><Setter TargetName='b' Property='BorderBrush' Value='{DynamicResource Accent}'/></Trigger>
            <Trigger Property='IsEnabled' Value='False'><Setter TargetName='b' Property='Opacity' Value='0.45'/></Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <Style x:Key='Caption' TargetType='Button'>
    <Setter Property='Foreground' Value='{DynamicResource Muted}'/>
    <Setter Property='Background' Value='Transparent'/>
    <Setter Property='Width' Value='46'/>
    <Setter Property='Height' Value='40'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='Button'>
          <Border x:Name='b' Background='{TemplateBinding Background}'>
            <ContentPresenter HorizontalAlignment='Center' VerticalAlignment='Center'/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property='IsMouseOver' Value='True'>
              <Setter TargetName='b' Property='Background' Value='{DynamicResource Hover}'/>
              <Setter Property='Foreground' Value='{DynamicResource Text}'/>
            </Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <Style x:Key='CaptionClose' TargetType='Button' BasedOn='{StaticResource Caption}'>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='Button'>
          <Border x:Name='b' Background='{TemplateBinding Background}'>
            <ContentPresenter HorizontalAlignment='Center' VerticalAlignment='Center'/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property='IsMouseOver' Value='True'>
              <Setter TargetName='b' Property='Background' Value='#C42B2B'/>
              <Setter Property='Foreground' Value='White'/>
            </Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <!-- Navigation: the selected page gets Surface2 and a 2 px accent bar (selection is an accent use). -->
  <Style x:Key='Nav' TargetType='RadioButton'>
    <Setter Property='Foreground' Value='{DynamicResource Muted}'/>
    <Setter Property='FontSize' Value='13.5'/>
    <Setter Property='Margin' Value='0,1'/>
    <Setter Property='Cursor' Value='Hand'/>
    <Setter Property='FocusVisualStyle' Value='{x:Null}'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='RadioButton'>
          <Grid>
            <Border x:Name='b' Background='Transparent' CornerRadius='6' Padding='10,0' Height='34' BorderThickness='1' BorderBrush='Transparent'>
              <ContentPresenter VerticalAlignment='Center'/>
            </Border>
            <Border x:Name='bar' Width='2' HorizontalAlignment='Left' Margin='0,9' Background='{DynamicResource Accent}' Visibility='Collapsed'/>
          </Grid>
          <ControlTemplate.Triggers>
            <Trigger Property='IsMouseOver' Value='True'><Setter TargetName='b' Property='Background' Value='{DynamicResource Hover}'/></Trigger>
            <Trigger Property='IsChecked' Value='True'>
              <Setter TargetName='b' Property='Background' Value='{DynamicResource Surface2}'/>
              <Setter TargetName='bar' Property='Visibility' Value='Visible'/>
              <Setter Property='Foreground' Value='{DynamicResource Text}'/>
            </Trigger>
            <Trigger Property='IsKeyboardFocused' Value='True'><Setter TargetName='b' Property='BorderBrush' Value='{DynamicResource Accent}'/></Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <!-- Segmented control: 28 px, radius 6; selected = Surface2 + accent text, not a solid block. -->
  <Style x:Key='Segment' TargetType='RadioButton'>
    <Setter Property='Foreground' Value='{DynamicResource Muted}'/>
    <Setter Property='FontSize' Value='12.5'/>
    <Setter Property='Cursor' Value='Hand'/>
    <Setter Property='FocusVisualStyle' Value='{x:Null}'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='RadioButton'>
          <Border x:Name='b' Background='Transparent' CornerRadius='5' Padding='10,0' Height='24' Margin='1' BorderThickness='1' BorderBrush='Transparent'>
            <ContentPresenter HorizontalAlignment='Center' VerticalAlignment='Center'/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property='IsMouseOver' Value='True'><Setter Property='Foreground' Value='{DynamicResource Text}'/></Trigger>
            <Trigger Property='IsChecked' Value='True'>
              <Setter TargetName='b' Property='Background' Value='{DynamicResource Surface2}'/>
              <Setter TargetName='b' Property='BorderBrush' Value='{DynamicResource Line}'/>
              <Setter Property='Foreground' Value='{DynamicResource Accent}'/>
              <Setter Property='FontWeight' Value='SemiBold'/>
            </Trigger>
            <Trigger Property='IsKeyboardFocused' Value='True'><Setter TargetName='b' Property='BorderBrush' Value='{DynamicResource Accent}'/></Trigger>
            <Trigger Property='IsEnabled' Value='False'><Setter Property='Opacity' Value='0.45'/></Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <!-- Choice row (one of a few options, title + one line): a radio dot, no card. -->
  <Style x:Key='Choice' TargetType='RadioButton'>
    <Setter Property='Foreground' Value='{DynamicResource Text}'/>
    <Setter Property='Cursor' Value='Hand'/>
    <Setter Property='FocusVisualStyle' Value='{x:Null}'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='RadioButton'>
          <Border x:Name='b' Background='Transparent' Padding='16,10' BorderThickness='1' BorderBrush='Transparent' CornerRadius='6'>
            <Grid>
              <Grid.ColumnDefinitions><ColumnDefinition Width='Auto'/><ColumnDefinition Width='*'/></Grid.ColumnDefinitions>
              <Grid Width='16' Height='16' VerticalAlignment='Top' Margin='0,2,12,0'>
                <Ellipse x:Name='ring' Stroke='{DynamicResource Muted}' StrokeThickness='1.5'/>
                <Ellipse x:Name='dot' Width='8' Height='8' Fill='{DynamicResource Accent}' Visibility='Collapsed'/>
              </Grid>
              <ContentPresenter Grid.Column='1' VerticalAlignment='Center'/>
            </Grid>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property='IsMouseOver' Value='True'><Setter TargetName='b' Property='Background' Value='{DynamicResource Hover}'/></Trigger>
            <Trigger Property='IsChecked' Value='True'>
              <Setter TargetName='ring' Property='Stroke' Value='{DynamicResource Accent}'/>
              <Setter TargetName='dot' Property='Visibility' Value='Visible'/>
            </Trigger>
            <Trigger Property='IsKeyboardFocused' Value='True'><Setter TargetName='b' Property='BorderBrush' Value='{DynamicResource Accent}'/></Trigger>
            <Trigger Property='IsEnabled' Value='False'><Setter TargetName='b' Property='Opacity' Value='0.55'/></Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <!-- Toggle: neutral track when off, accent only when on. -->
  <Style x:Key='Switch' TargetType='CheckBox'>
    <Setter Property='Cursor' Value='Hand'/>
    <Setter Property='Foreground' Value='{DynamicResource Text}'/>
    <Setter Property='FocusVisualStyle' Value='{x:Null}'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='CheckBox'>
          <StackPanel Orientation='Horizontal' Background='Transparent'>
            <Border x:Name='track' Width='34' Height='20' CornerRadius='10' Background='{DynamicResource Surface2}' BorderBrush='{DynamicResource Line}' BorderThickness='1' VerticalAlignment='Center'>
              <Ellipse x:Name='knob' Width='14' Height='14' Fill='{DynamicResource Muted}' HorizontalAlignment='Left' Margin='2,0,0,0'/>
            </Border>
            <ContentPresenter x:Name='cp' Margin='8,0,0,0' VerticalAlignment='Center'/>
          </StackPanel>
          <ControlTemplate.Triggers>
            <Trigger Property='IsChecked' Value='True'>
              <Setter TargetName='track' Property='Background' Value='{DynamicResource Accent}'/>
              <Setter TargetName='track' Property='BorderBrush' Value='{DynamicResource Accent}'/>
              <Setter TargetName='knob' Property='HorizontalAlignment' Value='Right'/>
              <Setter TargetName='knob' Property='Margin' Value='0,0,2,0'/>
              <Setter TargetName='knob' Property='Fill' Value='{DynamicResource AccentInk}'/>
            </Trigger>
            <Trigger Property='IsKeyboardFocused' Value='True'><Setter TargetName='track' Property='BorderBrush' Value='{DynamicResource Accent}'/></Trigger>
            <Trigger Property='IsEnabled' Value='False'><Setter Property='Opacity' Value='0.45'/></Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <!-- Slider: 2 px track, small thumb; the value is shown in mono next to it. -->
  <Style x:Key='SliderThumb' TargetType='Thumb'>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='Thumb'>
          <Ellipse x:Name='e' Width='12' Height='12' Fill='{DynamicResource Text}' Stroke='{DynamicResource Surface}' StrokeThickness='2'/>
          <ControlTemplate.Triggers>
            <Trigger Property='IsMouseOver' Value='True'><Setter TargetName='e' Property='Fill' Value='{DynamicResource Accent}'/></Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>
  <Style x:Key='SliderFill' TargetType='RepeatButton'>
    <Setter Property='Focusable' Value='False'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='RepeatButton'>
          <Border Background='Transparent'><Border Height='2' Background='{DynamicResource Muted}'/></Border>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>
  <Style x:Key='SliderRest' TargetType='RepeatButton'>
    <Setter Property='Focusable' Value='False'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='RepeatButton'>
          <Border Background='Transparent'><Border Height='2' Background='{DynamicResource Line}'/></Border>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>
  <Style TargetType='Slider'>
    <Setter Property='Cursor' Value='Hand'/>
    <Setter Property='IsMoveToPointEnabled' Value='True'/>
    <Setter Property='FocusVisualStyle' Value='{x:Null}'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='Slider'>
          <Grid Height='20' Background='Transparent'>
            <Track x:Name='PART_Track'>
              <Track.DecreaseRepeatButton><RepeatButton Style='{StaticResource SliderFill}' Command='Slider.DecreaseLarge'/></Track.DecreaseRepeatButton>
              <Track.IncreaseRepeatButton><RepeatButton Style='{StaticResource SliderRest}' Command='Slider.IncreaseLarge'/></Track.IncreaseRepeatButton>
              <Track.Thumb><Thumb Style='{StaticResource SliderThumb}'/></Track.Thumb>
            </Track>
          </Grid>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <!-- Text box -->
  <Style TargetType='TextBox'>
    <Setter Property='Foreground' Value='{DynamicResource Text}'/>
    <Setter Property='Background' Value='{DynamicResource Surface}'/>
    <Setter Property='BorderBrush' Value='{DynamicResource Line}'/>
    <Setter Property='CaretBrush' Value='{DynamicResource Text}'/>
    <Setter Property='SelectionBrush' Value='{DynamicResource Accent}'/>
    <Setter Property='Padding' Value='8,6'/>
    <Setter Property='MinHeight' Value='32'/>
    <Setter Property='VerticalContentAlignment' Value='Center'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='TextBox'>
          <Border x:Name='b' Background='{TemplateBinding Background}' BorderBrush='{TemplateBinding BorderBrush}' BorderThickness='1' CornerRadius='6'>
            <ScrollViewer x:Name='PART_ContentHost' Margin='{TemplateBinding Padding}' VerticalAlignment='{TemplateBinding VerticalContentAlignment}'/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property='IsMouseOver' Value='True'><Setter TargetName='b' Property='BorderBrush' Value='{DynamicResource Muted}'/></Trigger>
            <Trigger Property='IsKeyboardFocused' Value='True'><Setter TargetName='b' Property='BorderBrush' Value='{DynamicResource Accent}'/></Trigger>
            <Trigger Property='IsEnabled' Value='False'><Setter TargetName='b' Property='Opacity' Value='0.55'/></Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <!-- Combo box -->
  <Style x:Key='ComboToggle' TargetType='ToggleButton'>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='ToggleButton'>
          <Border x:Name='b' Background='{DynamicResource Surface}' BorderBrush='{DynamicResource Line}' BorderThickness='1' CornerRadius='6'>
            <Path HorizontalAlignment='Right' VerticalAlignment='Center' Margin='0,0,12,0' Data='M0,0 L4,4 L8,0' Stroke='{DynamicResource Muted}' StrokeThickness='1.5'/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property='IsMouseOver' Value='True'><Setter TargetName='b' Property='BorderBrush' Value='{DynamicResource Muted}'/></Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>
  <Style TargetType='ComboBoxItem'>
    <Setter Property='Foreground' Value='{DynamicResource Text}'/>
    <Setter Property='Cursor' Value='Hand'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='ComboBoxItem'>
          <Border x:Name='b' Background='Transparent' CornerRadius='4' Padding='8,6' Margin='2,1'>
            <ContentPresenter/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property='IsHighlighted' Value='True'><Setter TargetName='b' Property='Background' Value='{DynamicResource Hover}'/></Trigger>
            <Trigger Property='IsSelected' Value='True'><Setter TargetName='b' Property='Background' Value='{DynamicResource Surface2}'/></Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>
  <Style TargetType='ComboBox'>
    <Setter Property='Foreground' Value='{DynamicResource Text}'/>
    <Setter Property='FontSize' Value='13'/>
    <Setter Property='Height' Value='32'/>
    <Setter Property='Cursor' Value='Hand'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='ComboBox'>
          <Grid>
            <ToggleButton Style='{StaticResource ComboToggle}' Focusable='False' ClickMode='Press'
                          IsChecked='{Binding IsDropDownOpen, Mode=TwoWay, RelativeSource={RelativeSource TemplatedParent}}'/>
            <ContentPresenter IsHitTestVisible='False' Margin='10,0,30,0' VerticalAlignment='Center'
                              Content='{TemplateBinding SelectionBoxItem}' ContentTemplate='{TemplateBinding SelectionBoxItemTemplate}'/>
            <Popup x:Name='PART_Popup' Placement='Bottom' IsOpen='{TemplateBinding IsDropDownOpen}' AllowsTransparency='True' Focusable='False' PopupAnimation='None'>
              <Border Background='{DynamicResource Surface}' BorderBrush='{DynamicResource Line}' BorderThickness='1' CornerRadius='6' Margin='0,4,0,0' Padding='2'
                      MinWidth='{Binding ActualWidth, RelativeSource={RelativeSource TemplatedParent}}' MaxHeight='320'>
                <ScrollViewer><ItemsPresenter/></ScrollViewer>
              </Border>
            </Popup>
          </Grid>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <!-- Thin scrollbars -->
  <Style x:Key='ScrollThumb' TargetType='Thumb'>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='Thumb'>
          <Border CornerRadius='3' Background='{DynamicResource Line}'/>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>
  <Style TargetType='ScrollBar'>
    <Setter Property='Width' Value='8'/>
    <Setter Property='MinWidth' Value='8'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='ScrollBar'>
          <Track x:Name='PART_Track' IsDirectionReversed='True'>
            <Track.Thumb><Thumb Style='{StaticResource ScrollThumb}' Margin='1'/></Track.Thumb>
          </Track>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
    <Style.Triggers>
      <Trigger Property='Orientation' Value='Horizontal'>
        <Setter Property='Width' Value='Auto'/>
        <Setter Property='Height' Value='8'/>
        <Setter Property='MinHeight' Value='8'/>
        <Setter Property='Template'>
          <Setter.Value>
            <ControlTemplate TargetType='ScrollBar'>
              <Track x:Name='PART_Track'>
                <Track.Thumb><Thumb Style='{StaticResource ScrollThumb}' Margin='1'/></Track.Thumb>
              </Track>
            </ControlTemplate>
          </Setter.Value>
        </Setter>
      </Trigger>
    </Style.Triggers>
  </Style>

  <Style TargetType='ToolTip'>
    <Setter Property='Foreground' Value='{DynamicResource Text}'/>
    <Setter Property='Background' Value='{DynamicResource Surface2}'/>
    <Setter Property='BorderBrush' Value='{DynamicResource Line}'/>
    <Setter Property='FontSize' Value='12'/>
    <Setter Property='MaxWidth' Value='360'/>
  </Style>
</ResourceDictionary>";
    }
}
