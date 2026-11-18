# Sound quality and smart features
<!-- keywords: sound quality, eq, equalizer, bass, voice, movie, smart eq, dialogue boost, night mode, hearing profile, hearing test, talk-over, microphone, noise cancelling, smart buffer, awaz saaf, bass, dialogue, awaz behtar, sunai, mic -->

## What are the smart features in Dual Earbuds?
Smart features are optional sound tools that run on the device itself: Smart EQ, Dialogue boost, Smart buffer, hearing profiles and mic noise cancelling for talk-over. Nothing is sent anywhere, there is no account and no cost, and each one turns off instantly without stopping playback.
**Windows:** Settings > **Smart features**. **Android:** Settings > **Smart features**; Hearing profiles has its own row.

## How does Smart EQ pick the equalizer by itself?
Smart EQ chooses the EQ preset (Flat, Bass, Voice, Movie) from what is playing: **By app** (for example Netflix or YouTube gets Movie, Spotify gets Bass, Zoom or podcasts get Voice), **By sound** (a small built-in classifier listens for speech, music or movie mix), or **Both**. It waits until the same kind of sound wins for 5 seconds before switching, and crossfades.
1. Windows: Settings > Smart features > **Smart EQ**, then set an output's EQ to **EQ auto** on Home.
2. Android: Settings > Smart features > **Smart EQ**, and keep **Equalizer** on **Auto** on the main screen.
A manual EQ choice always wins until you pick Auto again.

## How do I make voices clearer in a movie?
Turn on **Dialogue boost**: it lifts voices over music and effects. **Night** also evens out loud and quiet scenes, so you can keep the volume low.
1. Windows: Settings > Smart features > **Dialogue boost**: Off, Light, Strong or Night (works on the laptop's outputs).
2. Android: Settings > Smart features > **Dialogue boost**: Off, Light, Strong or Night.
3. The **Voice** or **Movie** EQ preset also helps.

## What is a hearing profile?
A hearing profile is a short tone test, about 3 minutes, that gently tunes the EQ to what one person hears. It is not a medical hearing test.
1. Windows: Settings > Smart features > Hearing profiles > **New profile**; give it a name, pick the earbuds under **Test with**, press **Start the test**.
2. For each tone answer **I heard it** or **I can't hear it**; **Play again** repeats it (14 steps, both ears).
3. Choose the profile per output on Home with the **Hearing** list.
**Android:** Settings > **Hearing profiles** > **Start test**, answer **I hear it** or **Can't hear**, then **Save profile**. Each pair of earbuds remembers its own choice.

## How do I retest or delete a hearing profile?
Open the hearing profiles list and press **Retest** or **Delete** next to the profile. Outputs that used a deleted profile go back to none.
**Windows:** Settings > Smart features > Hearing profiles. **Android:** Settings > **Hearing profiles**.

## How do I talk over the sound to everyone?
Talk-over mixes the host's microphone into what everyone hears, and the music drops 12 dB while you talk. You must be sharing first.
1. Windows: Listeners page > **Talk over the sound** > choose **Hold** or **Tap**, then hold **Hold to talk** (or tap **Talk** and **Stop talking**).
2. Android host: Share this phone > Talk-over > **Talk to your listeners**.
3. Listeners see "The host is talking" (Windows) or "Host talking" (Android).
**Mic noise cancelling** (Off, Light, Strong) removes fans, traffic and keys from your voice; it is on Settings > Smart features (Windows) or in the Talk-over section (Android host).

## What does Smart buffer do?
Smart buffer learns how much your Wi-Fi hiccups and keeps the listener's buffer as small as is safe; after a dropout it grows at once and then shrinks slowly. It matters mainly when a listener isn't synced to the host's clock.
1. Windows (when listening): Settings > Smart features > **Buffer when listening**: Smart, Steady or Manual.
2. Android: Settings > Smart features > **Smart buffer**: Smart, Steady or Manual.
**Steady** uses fixed rules (start at 60 ms, grow after a dropout, shrink slowly). **Manual** lets you set the buffer yourself: lower means less delay, higher means fewer dropouts.

## Why does the sound crackle or click?
Crackles usually mean lost packets or a volume above 100 %. Dual Earbuds fills one missing 10 ms packet smoothly, but many losses in a row are audible.
1. Set volumes to 100 % or lower.
2. Use 5 GHz Wi-Fi or a hotspot and keep listeners near it.
3. Make sure listeners use UDP (the row shows UDP, not WebSocket).
4. Turn off battery saver on the listener.

## Is the audio quality reduced when sharing?
No compression is used: the sound goes as uncompressed 16-bit stereo PCM, normally at 48 kHz. Quality is limited only by the earbuds and their Bluetooth codec.
