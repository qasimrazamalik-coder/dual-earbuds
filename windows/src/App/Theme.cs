// Dual Earbuds Pro - colors, fonts and control styles (dark + light), built at runtime.
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
        public static readonly FontFamily Display = new FontFamily("Bahnschrift, Segoe UI Variable Display, Segoe UI");
        public static readonly FontFamily Mono = new FontFamily("Cascadia Mono, Consolas");

        static readonly string[] Keys = { "Bg", "Surface", "Surface2", "Line", "Text", "Muted", "Accent", "AccentInk", "AccentSoft", "Warn", "Bad", "Good", "Hover", "Pressed", "QrBg" };

        static readonly string[] DarkColors = { "#0B1220", "#121B2E", "#18233A", "#22304A", "#E6EDF7", "#8FA3BF", "#14E0C2", "#04201B", "#2414E0C2", "#F5B84B", "#FF6B6B", "#34D399", "#1A2640", "#22304A", "#FFFFFF" };
        static readonly string[] LightColors = { "#F3F6FB", "#FFFFFF", "#EEF3FA", "#DCE4EF", "#0F172A", "#5B6B82", "#0E9F95", "#FFFFFF", "#1F0E9F95", "#B7791F", "#DC2626", "#059669", "#E9EFF8", "#DCE4EF", "#FFFFFF" };

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

        // mode: system | dark | light
        public static void Apply(ResourceDictionary res, string mode)
        {
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
        }

        public static Brush B(string key) { return (Brush)Application.Current.Resources[key]; }

        const string StylesXaml = @"
<ResourceDictionary xmlns='http://schemas.microsoft.com/winfx/2006/xaml/presentation'
                    xmlns:x='http://schemas.microsoft.com/winfx/2006/xaml'>

  <!-- Buttons -->
  <Style x:Key='Primary' TargetType='Button'>
    <Setter Property='Foreground' Value='{DynamicResource AccentInk}'/>
    <Setter Property='Background' Value='{DynamicResource Accent}'/>
    <Setter Property='FontWeight' Value='SemiBold'/>
    <Setter Property='FontSize' Value='14'/>
    <Setter Property='Padding' Value='18,10'/>
    <Setter Property='Cursor' Value='Hand'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='Button'>
          <Border x:Name='b' Background='{TemplateBinding Background}' CornerRadius='12' Padding='{TemplateBinding Padding}'>
            <ContentPresenter HorizontalAlignment='Center' VerticalAlignment='Center'/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property='IsMouseOver' Value='True'><Setter TargetName='b' Property='Opacity' Value='0.9'/></Trigger>
            <Trigger Property='IsPressed' Value='True'><Setter TargetName='b' Property='Opacity' Value='0.8'/></Trigger>
            <Trigger Property='IsEnabled' Value='False'><Setter TargetName='b' Property='Opacity' Value='0.45'/></Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <Style x:Key='Ghost' TargetType='Button'>
    <Setter Property='Foreground' Value='{DynamicResource Text}'/>
    <Setter Property='Background' Value='{DynamicResource Surface2}'/>
    <Setter Property='BorderBrush' Value='{DynamicResource Line}'/>
    <Setter Property='FontSize' Value='13'/>
    <Setter Property='Padding' Value='14,8'/>
    <Setter Property='Cursor' Value='Hand'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='Button'>
          <Border x:Name='b' Background='{TemplateBinding Background}' BorderBrush='{TemplateBinding BorderBrush}' BorderThickness='1' CornerRadius='10' Padding='{TemplateBinding Padding}'>
            <ContentPresenter HorizontalAlignment='Center' VerticalAlignment='Center'/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property='IsMouseOver' Value='True'><Setter TargetName='b' Property='BorderBrush' Value='{DynamicResource Accent}'/></Trigger>
            <Trigger Property='IsPressed' Value='True'><Setter TargetName='b' Property='Background' Value='{DynamicResource Pressed}'/></Trigger>
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
              <Setter TargetName='b' Property='Background' Value='#E5484D'/>
              <Setter Property='Foreground' Value='White'/>
            </Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <!-- Navigation items -->
  <Style x:Key='Nav' TargetType='RadioButton'>
    <Setter Property='Foreground' Value='{DynamicResource Muted}'/>
    <Setter Property='FontSize' Value='14'/>
    <Setter Property='Margin' Value='0,2'/>
    <Setter Property='Cursor' Value='Hand'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='RadioButton'>
          <Grid>
            <Border x:Name='b' Background='Transparent' CornerRadius='10' Padding='12,10'>
              <ContentPresenter VerticalAlignment='Center'/>
            </Border>
            <Border x:Name='bar' Width='3' HorizontalAlignment='Left' Margin='0,10' CornerRadius='2' Background='{DynamicResource Accent}' Visibility='Collapsed'/>
          </Grid>
          <ControlTemplate.Triggers>
            <Trigger Property='IsMouseOver' Value='True'><Setter TargetName='b' Property='Background' Value='{DynamicResource Hover}'/></Trigger>
            <Trigger Property='IsChecked' Value='True'>
              <Setter TargetName='b' Property='Background' Value='{DynamicResource AccentSoft}'/>
              <Setter TargetName='bar' Property='Visibility' Value='Visible'/>
              <Setter Property='Foreground' Value='{DynamicResource Text}'/>
            </Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <!-- Segmented choice (theme picker, close behavior) -->
  <Style x:Key='Segment' TargetType='RadioButton'>
    <Setter Property='Foreground' Value='{DynamicResource Muted}'/>
    <Setter Property='FontSize' Value='13'/>
    <Setter Property='Cursor' Value='Hand'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='RadioButton'>
          <Border x:Name='b' Background='Transparent' CornerRadius='8' Padding='14,7' Margin='2'>
            <ContentPresenter HorizontalAlignment='Center'/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property='IsMouseOver' Value='True'><Setter TargetName='b' Property='Background' Value='{DynamicResource Hover}'/></Trigger>
            <Trigger Property='IsChecked' Value='True'>
              <Setter TargetName='b' Property='Background' Value='{DynamicResource Accent}'/>
              <Setter Property='Foreground' Value='{DynamicResource AccentInk}'/>
            </Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <!-- Toggle switch -->
  <Style x:Key='Switch' TargetType='CheckBox'>
    <Setter Property='Cursor' Value='Hand'/>
    <Setter Property='Foreground' Value='{DynamicResource Text}'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='CheckBox'>
          <StackPanel Orientation='Horizontal' Background='Transparent'>
            <Border x:Name='track' Width='42' Height='24' CornerRadius='12' Background='{DynamicResource Line}' VerticalAlignment='Center'>
              <Ellipse x:Name='knob' Width='18' Height='18' Fill='White' HorizontalAlignment='Left' Margin='3,0,0,0'/>
            </Border>
            <ContentPresenter Margin='10,0,0,0' VerticalAlignment='Center'/>
          </StackPanel>
          <ControlTemplate.Triggers>
            <Trigger Property='IsChecked' Value='True'>
              <Setter TargetName='track' Property='Background' Value='{DynamicResource Accent}'/>
              <Setter TargetName='knob' Property='HorizontalAlignment' Value='Right'/>
              <Setter TargetName='knob' Property='Margin' Value='0,0,3,0'/>
              <Setter TargetName='knob' Property='Fill' Value='{DynamicResource AccentInk}'/>
            </Trigger>
            <Trigger Property='IsEnabled' Value='False'><Setter Property='Opacity' Value='0.45'/></Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <!-- Slider -->
  <Style x:Key='SliderThumb' TargetType='Thumb'>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='Thumb'>
          <Ellipse Width='16' Height='16' Fill='{DynamicResource Accent}' Stroke='{DynamicResource Surface}' StrokeThickness='2'/>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>
  <Style x:Key='SliderFill' TargetType='RepeatButton'>
    <Setter Property='Focusable' Value='False'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='RepeatButton'>
          <Border Height='4' CornerRadius='2' Background='{DynamicResource Accent}'/>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>
  <Style x:Key='SliderRest' TargetType='RepeatButton'>
    <Setter Property='Focusable' Value='False'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='RepeatButton'>
          <Border Height='4' CornerRadius='2' Background='{DynamicResource Line}'/>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>
  <Style TargetType='Slider'>
    <Setter Property='Cursor' Value='Hand'/>
    <Setter Property='IsMoveToPointEnabled' Value='True'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='Slider'>
          <Grid Height='22' Background='Transparent'>
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
    <Setter Property='Background' Value='{DynamicResource Surface2}'/>
    <Setter Property='BorderBrush' Value='{DynamicResource Line}'/>
    <Setter Property='CaretBrush' Value='{DynamicResource Accent}'/>
    <Setter Property='SelectionBrush' Value='{DynamicResource Accent}'/>
    <Setter Property='Padding' Value='10,7'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='TextBox'>
          <Border x:Name='b' Background='{TemplateBinding Background}' BorderBrush='{TemplateBinding BorderBrush}' BorderThickness='1' CornerRadius='10'>
            <ScrollViewer x:Name='PART_ContentHost' Margin='{TemplateBinding Padding}'/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property='IsKeyboardFocused' Value='True'><Setter TargetName='b' Property='BorderBrush' Value='{DynamicResource Accent}'/></Trigger>
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
          <Border x:Name='b' Background='{DynamicResource Surface2}' BorderBrush='{DynamicResource Line}' BorderThickness='1' CornerRadius='10'>
            <Path HorizontalAlignment='Right' VerticalAlignment='Center' Margin='0,0,14,0' Data='M0,0 L5,5 L10,0' Stroke='{DynamicResource Muted}' StrokeThickness='1.6'/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property='IsMouseOver' Value='True'><Setter TargetName='b' Property='BorderBrush' Value='{DynamicResource Accent}'/></Trigger>
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
          <Border x:Name='b' Background='Transparent' CornerRadius='8' Padding='10,8' Margin='2'>
            <ContentPresenter/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property='IsHighlighted' Value='True'><Setter TargetName='b' Property='Background' Value='{DynamicResource Hover}'/></Trigger>
            <Trigger Property='IsSelected' Value='True'><Setter TargetName='b' Property='Background' Value='{DynamicResource AccentSoft}'/></Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>
  <Style TargetType='ComboBox'>
    <Setter Property='Foreground' Value='{DynamicResource Text}'/>
    <Setter Property='FontSize' Value='14'/>
    <Setter Property='Height' Value='40'/>
    <Setter Property='Cursor' Value='Hand'/>
    <Setter Property='Template'>
      <Setter.Value>
        <ControlTemplate TargetType='ComboBox'>
          <Grid>
            <ToggleButton Style='{StaticResource ComboToggle}' Focusable='False' ClickMode='Press'
                          IsChecked='{Binding IsDropDownOpen, Mode=TwoWay, RelativeSource={RelativeSource TemplatedParent}}'/>
            <ContentPresenter IsHitTestVisible='False' Margin='14,0,36,0' VerticalAlignment='Center'
                              Content='{TemplateBinding SelectionBoxItem}' ContentTemplate='{TemplateBinding SelectionBoxItemTemplate}'/>
            <Popup x:Name='PART_Popup' Placement='Bottom' IsOpen='{TemplateBinding IsDropDownOpen}' AllowsTransparency='True' Focusable='False' PopupAnimation='Fade'>
              <Border Background='{DynamicResource Surface}' BorderBrush='{DynamicResource Line}' BorderThickness='1' CornerRadius='10' Margin='0,4,0,0' Padding='4'
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
  </Style>
</ResourceDictionary>";
    }
}
