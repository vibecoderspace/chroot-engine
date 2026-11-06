package com.vibecoderspace.chrootengine.sample;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;
import com.vibecoderspace.chrootengine.ChrootRuntime;
import com.vibecoderspace.chrootengine.ChrootTerminal;
import com.vibecoderspace.chrootengine.DefaultProfiles;
import com.vibecoderspace.chrootengine.InstallProgress;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kotlin.Unit;

/**
 * On-device test for chroot-engine.
 *
 * <p>Installs the Ubuntu 24.04.5 image with {@link ChrootRuntime#installBlocking},
 * builds a {@link ChrootTerminal}, and binds it to the PTY owned by the vendored
 * Termux {@link TerminalSession} / {@link TerminalView}.</p>
 */
public final class MainActivity extends Activity {

    private static final String TAG = "ChrootSample";
    private static final int TRANSCRIPT_ROWS = 2000;
    private static final int DEFAULT_TEXT_SIZE_SP = 14;
    private static final int MIN_TEXT_SIZE_SP = 8;
    private static final int MAX_TEXT_SIZE_SP = 32;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final SessionClient sessionClient = new SessionClient(this);
    private final ViewClient viewClient = new ViewClient(this);

    private TerminalView terminalView;
    private TextView statusText;
    private ProgressBar progressBar;
    private Button restartButton;
    private Button reinstallButton;
    private Button ctrlButton;
    private Button altButton;
    private Button keyboardButton;

    private TerminalSession session;
    private boolean resumed;
    private boolean busy;
    private boolean ctrlDown;
    private boolean altDown;
    private int textSizeSp = DEFAULT_TEXT_SIZE_SP;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        terminalView = findViewById(R.id.terminal);
        statusText = findViewById(R.id.status);
        progressBar = findViewById(R.id.progress);
        restartButton = findViewById(R.id.restart);
        reinstallButton = findViewById(R.id.reinstall);
        ctrlButton = findViewById(R.id.ctrl);
        altButton = findViewById(R.id.alt);
        keyboardButton = findViewById(R.id.keyboard);

        terminalView.setTerminalViewClient(viewClient);
        applyTextSize();

        restartButton.setOnClickListener(v -> {
            if (ChrootRuntime.image() == null) {
                install(false);
            } else {
                startSession();
            }
        });

        reinstallButton.setOnClickListener(v -> install(true));

        ctrlButton.setOnClickListener(v -> {
            ctrlDown = !ctrlDown;
            ctrlButton.setSelected(ctrlDown);
            ctrlButton.setText(ctrlDown ? "Ctrl*" : "Ctrl");
            terminalView.requestFocus();
        });

        altButton.setOnClickListener(v -> {
            altDown = !altDown;
            altButton.setSelected(altDown);
            altButton.setText(altDown ? "Alt*" : "Alt");
            terminalView.requestFocus();
        });

        keyboardButton.setOnClickListener(v -> toggleKeyboard());

        // install() is idempotent: a valid image is reused without downloading.
        install(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (session != null && session.isRunning()) {
            terminalView.setTerminalCursorBlinkerState(true, true);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        terminalView.setTerminalCursorBlinkerState(false, false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (session != null) {
            session.finishIfRunning();
            session = null;
        }
        io.shutdownNow();
        super.onDestroy();
    }

    // ----------------------------------------------------------- installation

    private void install(boolean force) {
        if (busy) return;
        busy = true;

        // Never replace the sandbox while a PTY child is running in it.
        if (session != null) {
            session.finishIfRunning();
            session = null;
        }

        boolean reinstall = force && ChrootRuntime.image() != null;

        setControlsEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        statusText.setText(reinstall ? "Reinstalling Ubuntu 24.04.5…" : "Preparing Ubuntu 24.04.5…");

        io.execute(() -> {
            try {
                if (reinstall) {
                    ChrootRuntime.reinstallBlocking(this, true,
                            progress -> postProgress(progress));
                } else {
                    ChrootRuntime.installBlocking(this, DefaultProfiles.INSTANCE.getUbuntu(),
                            progress -> postProgress(progress));
                }
                mainHandler.post(() -> {
                    busy = false;
                    progressBar.setVisibility(View.GONE);
                    setControlsEnabled(true);
                    statusText.setText("Ubuntu 24.04.5 ready");
                    startSession();
                });
            } catch (Throwable e) {
                Log.e(TAG, "Rootfs installation failed", e);
                mainHandler.post(() -> {
                    busy = false;
                    progressBar.setVisibility(View.GONE);
                    setControlsEnabled(true);
                    statusText.setText("Install failed: " + describe(e));
                    Toast.makeText(this, "Install failed", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private Unit postProgress(InstallProgress progress) {
        mainHandler.post(() -> {
            if (isFinishing()) return;
            String stage = progress.getStage().name();
            String message = progress.getMessage();
            statusText.setText(message == null ? stage : stage + " · " + message);
            float fraction = progress.getFraction();
            if (fraction >= 0f && fraction <= 1f) {
                progressBar.setIndeterminate(false);
                progressBar.setMax(1000);
                progressBar.setProgress(Math.round(fraction * 1000));
            } else {
                progressBar.setIndeterminate(true);
            }
        });
        return Unit.INSTANCE;
    }

    private void setControlsEnabled(boolean enabled) {
        restartButton.setEnabled(enabled);
        reinstallButton.setEnabled(enabled);
        terminalView.setEnabled(enabled);
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    // ---------------------------------------------------------------- session

    private void startSession() {
        if (isFinishing()) return;

        if (session != null) {
            session.finishIfRunning();
            session = null;
        }

        try {
            ChrootTerminal terminal = ChrootRuntime.terminal();
            session = terminal.bindToPty(
                    (shellPath, cwd, args, env) -> new TerminalSession(
                            shellPath, cwd, args, env, TRANSCRIPT_ROWS, sessionClient));

            terminalView.attachSession(session);
            statusText.setText("Ubuntu 24.04.5 · " + session.getCwd());

            terminalView.post(() -> {
                terminalView.requestFocus();
                showKeyboard();
            });
        } catch (Throwable e) {
            Log.e(TAG, "Cannot start the chroot session", e);
            statusText.setText("Shell failed: " + describe(e));
            Toast.makeText(this, "Shell failed", Toast.LENGTH_LONG).show();
        }
    }

    void onScreenTextChanged() {
        terminalView.onScreenUpdated();
    }

    void onSessionTitleChanged(TerminalSession changedSession) {
        String title = changedSession.getTitle();
        setTitle(title == null || title.isEmpty() ? "chroot-engine sample" : title);
    }

    void onSessionFinished(TerminalSession finishedSession) {
        int status = finishedSession.getExitStatus();
        statusText.setText("Shell exited (" + status + "). Tap Shell to restart.");
        Toast.makeText(this, "Shell exited (" + status + ")", Toast.LENGTH_SHORT).show();
    }

    void onColorsChanged() {
        terminalView.invalidate();
    }

    void onCursorStateChanged(boolean state) {
        terminalView.setTerminalCursorBlinkerState(state && resumed, false);
    }

    void onEmulatorSet() {
        terminalView.setTerminalCursorBlinkerState(resumed, true);
    }

    void onBell() {
        terminalView.invalidate();
    }

    // ------------------------------------------------------------------ input

    boolean isCtrlDown() {
        return ctrlDown;
    }

    boolean isAltDown() {
        return altDown;
    }

    float onScale(float scale) {
        if (scale < 0.9f || scale > 1.1f) {
            textSizeSp += scale > 1f ? 1 : -1;
            textSizeSp = Math.max(MIN_TEXT_SIZE_SP, Math.min(MAX_TEXT_SIZE_SP, textSizeSp));
            applyTextSize();
            return 1f;
        }
        return scale;
    }

    private void applyTextSize() {
        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        terminalView.setTextSize(Math.max(1, Math.round(textSizeSp * scaledDensity)));
    }

    void toggleKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        if (imm.isAcceptingText()) {
            imm.hideSoftInputFromWindow(terminalView.getWindowToken(), 0);
        } else {
            terminalView.requestFocus();
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void showKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
        }
    }
}
