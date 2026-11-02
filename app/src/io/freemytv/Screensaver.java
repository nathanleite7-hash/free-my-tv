package io.freemytv;

import android.service.dreams.DreamService;

/**
 * The Free My TV screensaver (enabled in Free My TV mode via `settings put secure screensaver_components`). The system
 * still decides when it starts and when the TV turns off (e.g. after 10 min idle, then 2 h later), as before: the app
 * doesn't change those timings. Any key exits.
 */
public class Screensaver extends DreamService {
    private DreamView view;
    private NowPlayingActivity.NowPlayingView music;   // instead of the photos while music plays
    private SaverV2 v2;   // the V2 one (home_layout=v2): photos, with the song over them while music plays

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Watchdog.install(this);
        Ui.init(this);
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        // Music playing: its cover and its colors, like Apple TV's screensaver for music; else the photos.
        if (Config.HOME_V2) {
            v2 = new SaverV2(this);
            setContentView(v2);
            return;
        }
        NowPlaying np = NowPlaying.get(this);
        np.prime();
        if (np.active() && np.playing) {
            music = new NowPlayingActivity.NowPlayingView(this, null, true);
            setContentView(music);
            return;
        }
        view = new DreamView(this);
        setContentView(view);
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        if (view != null) view.start();
        if (music != null) music.start();
        if (v2 != null) v2.start();
    }

    @Override public void onDreamingStopped() {
        if (view != null) view.stop();
        if (music != null) music.stop();
        if (v2 != null) v2.stop();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        if (view != null) view.stop();
        if (music != null) music.stop();
        if (v2 != null) v2.stop();
        super.onDetachedFromWindow();
    }
}
