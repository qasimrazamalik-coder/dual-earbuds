// Dual Earbuds Pro - the browser player page served to phones.
using System;
using System.IO;
using System.Text;

namespace DualEarbuds
{
    public static class WebPlayer
    {
        public const string ForbiddenHtml = @"<!doctype html><html lang='en'><head><meta charset='utf-8'>
<meta name='viewport' content='width=device-width,initial-scale=1'><title>Dual Earbuds</title>
<style>body{margin:0;font-family:system-ui,sans-serif;background:#0B1220;color:#E6EDF7;display:flex;min-height:100vh;align-items:center;justify-content:center;text-align:center;padding:24px}
h1{font-size:22px}p{color:#8FA3BF;line-height:1.5}</style></head><body><div>
<h1>This link is missing its key</h1>
<p>Open the Dual Earbuds app on your laptop and scan the QR code on the Phones page,<br>or copy the full link shown there.</p>
</div></body></html>";

        // The player page lives in shared/web-player.html (the same page every host serves) and is embedded
        // into the app at build time as the manifest resource "web-player.html". Read once, then cached.
        public const string ResourceName = "web-player.html";
        static string html;
        static readonly object lk = new object();

        public static string Html
        {
            get
            {
                string h = html;
                if (h != null) return h;
                lock (lk)
                {
                    if (html == null) html = Load() ?? MissingHtml;
                    return html;
                }
            }
        }

        static byte[] htmlBytes;
        public static byte[] HtmlBytes
        {
            get
            {
                byte[] b = htmlBytes;
                if (b == null) htmlBytes = b = Encoding.UTF8.GetBytes(Html);
                return b;
            }
        }

        // Null when the build did not embed the page.
        public static string Load()
        {
            try
            {
                using (var s = typeof(WebPlayer).Assembly.GetManifestResourceStream(ResourceName))
                {
                    if (s == null) { Log.Error("The phone player page is missing from this build (resource " + ResourceName + ")"); return null; }
                    using (var r = new StreamReader(s, new UTF8Encoding(false), true)) return r.ReadToEnd();
                }
            }
            catch (Exception ex) { Log.Error("Reading the phone player page: " + ex.Message); return null; }
        }

        public const string MissingHtml = @"<!doctype html><html lang='en'><head><meta charset='utf-8'>
<meta name='viewport' content='width=device-width,initial-scale=1'><title>Dual Earbuds</title>
<style>body{margin:0;font-family:system-ui,sans-serif;background:#0B1220;color:#E6EDF7;display:flex;min-height:100vh;align-items:center;justify-content:center;text-align:center;padding:24px}
p{color:#8FA3BF;line-height:1.5}</style></head><body><div>
<h1>Player page unavailable</h1>
<p>This copy of Dual Earbuds was built without its phone player page. Use the Dual Earbuds Android app, or reinstall the app on the laptop.</p>
</div></body></html>";
    }
}
