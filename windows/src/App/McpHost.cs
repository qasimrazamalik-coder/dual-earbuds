// Dual Earbuds - the MCP tools over the Controller (docs/assistant-and-mcp.md section 3), and the
// "DualEarbuds.exe --mcp" stdio bridge. Every request runs on the UI thread, like a click would.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Text;
using System.Threading;
using System.Windows.Threading;

namespace DualEarbuds
{
    class McpHost : IDeviceControl
    {
        public const int UiTimeoutMs = 10000;
        readonly Controller c;
        readonly Dispatcher ui;
        readonly McpServer server;

        public McpHost(Controller ctl)
        {
            c = ctl;
            ui = Dispatcher.CurrentDispatcher;
            server = new McpServer(this, HelpContent.Assistant);
            c.Mcp.Handle = HandleOnUi;
        }

        // Called on the link server's HTTP thread.
        string HandleOnUi(string body)
        {
            if (ui.CheckAccess()) return server.Handle(body);
            string result = null;
            var op = ui.BeginInvoke(new Action(() => { result = server.Handle(body); }));
            if (op.Wait(TimeSpan.FromMilliseconds(UiTimeoutMs)) != DispatcherOperationStatus.Completed)
                throw new TimeoutException("the app didn't answer within 10 s");
            return result;
        }

        OutputVm Out(string id) { return c.Outputs.Find(o => o.Id == id); }

        public SessionSnapshot Snapshot() { return SessionInfo.From(c); }

        public string SetOutput(string id, bool? on, int? volume, int? delayMs, string eq)
        {
            var o = Out(id);
            if (o == null) return "No output with id " + id + ".";
            if (on != null && o.IsSource && on.Value) return o.Name + " is the sound source; it already plays the sound.";
            if (on != null && on.Value != o.Enabled) c.SetEnabled(o, on.Value);
            if (volume != null) c.SetVolume(o, volume.Value);
            if (delayMs != null) c.SetDelay(o, delayMs.Value);
            if (eq != null) c.SetOutputEq(o, eq);
            c.NotifyExternalChange();
            return null;
        }

        public string SetListenerVolume(string id, int volume)
        {
            if (c.Phones.Find(p => p.Id == id) == null) return "No listener with id " + id + ".";
            c.SetPhoneVolume(id, volume);
            c.NotifyExternalChange();
            return null;
        }

        public string KickListener(string id)
        {
            if (c.Phones.Find(p => p.Id == id) == null) return "No listener with id " + id + ".";
            c.KickPhone(id);
            c.NotifyExternalChange();
            return null;
        }

        public string StartSharing()
        {
            if (c.Listening) return "This laptop is listening to another device. Stop listening first (Listen page).";
            if (!(c.Running || c.WantRunning)) c.Start(true);
            return null;
        }

        public string StopSharing()
        {
            if (c.Running || c.WantRunning) c.Stop();
            return null;
        }

        public string SetSyncPriority(string priority)
        {
            c.SetPriority(priority);
            c.NotifyExternalChange();
            return null;
        }

        public string SetPartyMode(bool on, bool? requirePin)
        {
            if (c.Party != on) c.SetParty(on);
            if (requirePin != null) c.SetPinOn(on && requirePin.Value);
            c.NotifyExternalChange();
            return null;
        }

        public string JoinLink()
        {
            if (!c.Settings.PhoneLink || (c.Phone == null && !c.Demo)) return null;
            return c.LinkUrl;
        }

        public List<CheckItem> SetupChecks() { return c.Checks(); }
        public string Diagnostics() { return c.DiagnosticsText(); }
        public string[] Secrets() { return new[] { c.Settings.Pin }; }
        public void Report(string client, string tool, string summary) { c.NoteAssistantAction(client, tool, summary); }
    }

    // "DualEarbuds.exe --mcp": started by the AI assistant with pipes for stdin/stdout. Never opens a window.
    static class McpBridge
    {
        public const int StartWaitMs = 5000;

        public static int Run()
        {
            var utf8 = new UTF8Encoding(false);
            var stdin = new StreamReader(Console.OpenStandardInput(), utf8);
            var stdout = new StreamWriter(Console.OpenStandardOutput(), utf8) { NewLine = "\n", AutoFlush = false };
            var stderr = new StreamWriter(Console.OpenStandardError(), utf8) { NewLine = "\n", AutoFlush = true };
            int port = Settings.Load().PhonePort;
            bool started = false;
            stderr.WriteLine("dual-earbuds mcp: forwarding to http://127.0.0.1:" + port + "/mcp");
            Func<string, string> forward = msg =>
            {
                string token = McpToken.Read(McpToken.DefaultPath);
                if (token == null) throw new McpForwardException("AI assistants are off in Dual Earbuds. Open Dual Earbuds > Settings > AI assistants and turn on \"Allow AI assistants to control Dual Earbuds\".");
                var client = new McpHttpClient(port, token);
                try { return client.Post(msg); }
                catch (WebException)
                {
                    // not running: start it in the tray once and wait up to 5 s
                    if (!started)
                    {
                        started = true;
                        stderr.WriteLine("dual-earbuds mcp: starting Dual Earbuds in the tray");
                        try { Process.Start(new ProcessStartInfo(System.Reflection.Assembly.GetEntryAssembly().Location, "--tray") { UseShellExecute = false }); }
                        catch (Exception ex) { throw new McpForwardException("Dual Earbuds couldn't be started: " + ex.Message); }
                    }
                    var sw = Stopwatch.StartNew();
                    while (sw.ElapsedMilliseconds < StartWaitMs)
                    {
                        Thread.Sleep(250);
                        try { return client.Post(msg); }
                        catch (WebException) { }
                    }
                    throw new McpForwardException("Dual Earbuds isn't running (it didn't answer on port " + port + " within 5 s).");
                }
            };
            try { McpStdio.Run(stdin, stdout, stderr, forward); }
            catch (Exception ex) { stderr.WriteLine("dual-earbuds mcp: " + ex.Message); return 1; }
            return 0;
        }
    }
}
