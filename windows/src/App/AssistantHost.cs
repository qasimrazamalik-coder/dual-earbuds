// Dual Earbuds - the app side of the assistant: the bundled help (embedded by build.ps1) and the session
// snapshot the assistant's live facts and the MCP tools read. Runs on the UI thread.
using System;
using System.Collections.Generic;

namespace DualEarbuds
{
    public static class HelpContent
    {
        static Assistant cached;
        static readonly object lk = new object();

        // Parsed once, on first use (about 150 sections: a few milliseconds).
        public static Assistant Assistant
        {
            get
            {
                lock (lk)
                {
                    if (cached == null) cached = Assistant.FromAssembly(typeof(HelpContent).Assembly);
                    return cached;
                }
            }
        }
    }

    public static class SessionInfo
    {
        // What the app is doing right now, without the PIN or link key.
        public static SessionSnapshot From(Controller c)
        {
            var s = new SessionSnapshot();
            s.Listening = c.Listening;
            s.Sharing = c.Running;
            s.Waiting = !c.Running && c.WantRunning;
            s.ListenHost = c.Listening ? (c.ListenHostName ?? "") : "";
            s.PhoneLinkOn = c.Settings.PhoneLink;
            s.Party = c.Settings.Party;
            s.PinOn = c.PinActive;
            s.Capacity = c.ListenerCapacity;
            s.Priority = c.Priority;
            s.PlayoutMs = c.PlayoutMs;
            s.Source = c.SourceDescription ?? "";
            s.NetworkCategory = c.NetworkCategory;
            s.Status = c.StatusText ?? "";
            if (c.StatusKind == StateKind.Problem) s.LastError = c.StatusText;
            s.Wifi = c.Wifi;
            s.Firewall = c.FirewallNow;
            s.Power = c.PowerNow;
            s.LocalNeedMs = c.LocalNeedMs;
            if (c.LipSync)
            {
                var r = c.ReferenceOutput;
                s.LocalNeedName = r != null ? r.Name : "";
            }
            else
            {
                OutputVm slow = null;
                foreach (var o in c.Outputs) if (o.Enabled && !o.IsSource && (slow == null || o.EffectiveLatencyMs > slow.EffectiveLatencyMs)) slow = o;
                s.LocalNeedName = slow != null ? slow.Name : "";
            }
            foreach (var o in c.Outputs)
            {
                bool reference = !c.Listening && c.LipSync && c.IsReference(o);
                s.Outputs.Add(new OutputSnap
                {
                    Id = o.Id, Name = o.Name ?? "", State = o.State ?? "", Eq = o.Effects != null ? (o.Effects.Auto ? "auto" : o.Effects.EffectivePreset ?? "") : "",
                    On = o.Enabled, Source = o.IsSource || reference, Calibrated = o.LatencyMeasured, Bluetooth = o.IsBluetooth,
                    Volume = o.Volume, DelayMs = o.DelayMs, ExtraDelayMs = o.ExtraDelayMs, Battery = o.Battery,
                    LatencyMs = reference ? c.SourceLatencyMs : o.EffectiveLatencyMs,
                    SyncErrMs = c.Listening ? c.ListenOffsetMs(o) : c.LocalOffsetMs(o),
                });
            }
            foreach (var p in c.Phones)
            {
                s.Listeners.Add(new ListenerSnap
                {
                    Id = p.Id ?? "", Name = p.Name ?? "", Client = p.Client ?? "", Earbuds = p.Earbuds ?? "", Transport = p.Transport ?? "ws",
                    Battery = p.Battery, Volume = p.Volume, NeedMs = p.NeedMs, RttMs = p.RttMs, Synced = p.Synced,
                    SyncErrMs = Controller.PhoneOffsetMs(p),
                });
            }
            foreach (var chk in c.Checks())
                if (chk.Status == CheckStatus.Bad || chk.Status == CheckStatus.Warn) s.Warnings.Add(chk.Title + ": " + (chk.Detail ?? ""));
            return s;
        }
    }
}
