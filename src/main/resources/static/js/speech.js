// Spracheingabe: nimmt per Push-to-talk auf, wandelt die Aufnahme lokal mit Whisper in Text um
// (siehe whisper-worker.js) und fügt den Text in das Eingabefeld ein. Gesendet wird nichts,
// der Benutzer prüft und korrigiert den Text zuerst.
(() => {
    'use strict';

    const MODEL_SIZE_TEXT = 'ca. 80 MB';
    const MAX_RECORDING_MS = 60000;
    const CONSENT_KEY = 'speech.consent.v1';

    const input = document.getElementById('input');
    const button = document.getElementById('mic-button');
    const statusLine = document.getElementById('speech-status');
    const dialog = document.getElementById('speech-consent');
    if (!input || !button || !statusLine || !dialog) {
        return;
    }

    // Geräteprüfung: Die Spracheingabe wird nur angeboten, wenn der Browser sie leisten kann.
    const supported = window.isSecureContext
        && !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)
        && 'MediaRecorder' in window
        && 'Worker' in window
        && 'WebAssembly' in window
        && !(navigator.deviceMemory && navigator.deviceMemory < 2);
    if (!supported) {
        button.hidden = true;
        return;
    }
    button.hidden = false;
    dialog.querySelector('[data-model-size]').textContent = MODEL_SIZE_TEXT;

    let state = 'idle';          // idle | loading | recording | transcribing
    let worker = null;
    let readyPromise = null;
    let pending = null;          // laufende Transkription {resolve, reject}
    let progressHandler = () => {};
    let recorder = null;
    let stream = null;
    let chunks = [];
    let timer = null;
    let caret = null;            // Cursorposition, die das Eingabefeld beim Verlassen hatte
    let aufnahmeMesspunkt = null;

    input.addEventListener('blur', () => {
        caret = [input.selectionStart, input.selectionEnd];
    });

    function say(text) {
        statusLine.textContent = text;
    }

    function setState(next, message) {
        state = next;
        button.classList.toggle('recording', next === 'recording');
        button.disabled = next === 'loading' || next === 'transcribing';
        button.textContent = next === 'recording' ? '⏹ Stopp' : '🎤 Sprechen';
        button.setAttribute('aria-pressed', String(next === 'recording'));
        say(message || '');
    }

    function hasConsent() {
        try {
            return localStorage.getItem(CONSENT_KEY) === 'yes';
        } catch (e) {
            return false;
        }
    }

    function storeConsent() {
        try {
            localStorage.setItem(CONSENT_KEY, 'yes');
        } catch (e) {
            // Ohne Speicher wird beim nächsten Mal erneut gefragt.
        }
    }

    function askConsent() {
        return new Promise((resolve) => {
            dialog.addEventListener('close', () => resolve(dialog.returnValue === 'ok'), {once: true});
            dialog.returnValue = '';
            dialog.showModal();
        });
    }

    function ensureModel() {
        if (readyPromise) {
            return readyPromise;
        }
        worker = new Worker('/js/whisper-worker.js', {type: 'module'});
        readyPromise = new Promise((resolve, reject) => {
            worker.onmessage = (event) => {
                const message = event.data;
                if (message.type === 'progress') {
                    progressHandler(message.percent);
                } else if (message.type === 'ready') {
                    resolve();
                } else if (message.type === 'result' && pending) {
                    pending.resolve(message.text);
                    pending = null;
                } else if (message.type === 'error') {
                    const error = new Error(message.message);
                    if (pending) {
                        pending.reject(error);
                        pending = null;
                    } else {
                        reject(error);
                    }
                }
            };
            worker.onerror = (event) => reject(new Error(event.message || 'Der Worker konnte nicht gestartet werden.'));
            worker.postMessage({type: 'load'});
        });
        // Bei einem Fehler wird beim nächsten Versuch neu geladen.
        readyPromise.catch(() => {
            if (worker) {
                worker.terminate();
            }
            worker = null;
            readyPromise = null;
        });
        return readyPromise;
    }

    function transcribe(audio) {
        return new Promise((resolve, reject) => {
            pending = {resolve, reject};
            worker.postMessage({type: 'transcribe', audio}, [audio.buffer]);
        });
    }

    // Dekodiert die Aufnahme und rechnet sie auf Mono mit 16 kHz um, wie Whisper es erwartet.
    async function toMono16k(blob) {
        const context = new AudioContext();
        let decoded;
        try {
            decoded = await context.decodeAudioData(await blob.arrayBuffer());
        } finally {
            await context.close();
        }
        const offline = new OfflineAudioContext(1, Math.max(1, Math.ceil(decoded.duration * 16000)), 16000);
        const source = offline.createBufferSource();
        source.buffer = decoded;
        source.connect(offline.destination);
        source.start();
        const rendered = await offline.startRendering();
        return rendered.getChannelData(0).slice();   // Kopie, damit der Puffer an den Worker übergeben werden kann
    }

    function insertText(text) {
        // Bei Stille oder Geräuschen liefert Whisper Platzhalter wie "[Musik]" oder "* Musik *".
        const spoken = text.replace(/\[[^\]]*\]|\*[^*]*\*/g, ' ').replace(/\s+/g, ' ').trim();
        if (!spoken) {
            say('Es wurde keine Sprache erkannt.');
            return;
        }
        const length = input.value.length;
        const start = caret ? Math.min(caret[0], length) : length;
        const end = caret ? Math.min(caret[1], length) : length;
        const before = input.value.slice(0, start);
        const after = input.value.slice(end);
        const spaceBefore = before && !/\s$/.test(before) ? ' ' : '';
        const spaceAfter = after && !/^\s/.test(after) ? ' ' : '';
        input.value = before + spaceBefore + spoken + spaceAfter + after;
        const position = (before + spaceBefore + spoken).length;
        input.focus();
        input.setSelectionRange(position, position);
        say('Text eingefügt. Du kannst ihn noch ändern, bevor du ihn abschickst.');
    }

    async function startRecording() {
        try {
            stream = await navigator.mediaDevices.getUserMedia({audio: true});
        } catch (error) {
            const denied = error && (error.name === 'NotAllowedError' || error.name === 'SecurityError');
            setState('idle', denied
                ? 'Der Zugriff auf das Mikrofon wurde nicht erlaubt.'
                : 'Das Mikrofon konnte nicht geöffnet werden.');
            return;
        }
        chunks = [];
        recorder = new MediaRecorder(stream);
        recorder.ondataavailable = (event) => {
            if (event.data && event.data.size > 0) {
                chunks.push(event.data);
            }
        };
        recorder.onstop = onRecorded;
        recorder.start();
        aufnahmeMesspunkt = Messpunkt.start('speech.aufnahme');
        timer = setTimeout(stopRecording, MAX_RECORDING_MS);
        setState('recording', 'Aufnahme läuft … zum Beenden „Stopp“ drücken.');
    }

    function stopRecording() {
        clearTimeout(timer);
        if (recorder && recorder.state === 'recording') {
            recorder.stop();
        }
        if (stream) {
            stream.getTracks().forEach((track) => track.stop());
            stream = null;
        }
    }

    async function onRecorded() {
        if (aufnahmeMesspunkt) {
            aufnahmeMesspunkt.ende('ok');
            aufnahmeMesspunkt = null;
        }
        setState('transcribing', 'Text wird erkannt … (lokal auf diesem Gerät)');
        // Die Zeit, die der Benutzer nach "Stopp" bis zum eingefügten Text wartet (Aufbereiten plus Erkennen).
        const wartezeit = Messpunkt.start('speech.wartezeit-nach-stopp');
        try {
            const aufbereiten = Messpunkt.start('speech.audio-aufbereiten');
            const audio = await toMono16k(new Blob(chunks, {type: recorder.mimeType}));
            const sekunden = (audio.length / 16000).toFixed(1);
            aufbereiten.ende('ok audio-sekunden=' + sekunden);
            if (audio.length < 16000 * 0.3) {
                wartezeit.ende('zu-kurz audio-sekunden=' + sekunden);
                setState('idle', 'Die Aufnahme war zu kurz.');
                return;
            }
            const erkennung = Messpunkt.start('speech.erkennung', 'audio-sekunden=' + sekunden);
            const text = await transcribe(audio);
            erkennung.ende('ok zeichen=' + text.length);
            wartezeit.ende('ok audio-sekunden=' + sekunden);
            setState('idle');
            insertText(text);
        } catch (error) {
            wartezeit.ende('fehler ' + error.name);
            setState('idle', 'Die Spracherkennung ist fehlgeschlagen: ' + error.message);
        }
    }

    button.addEventListener('click', async () => {
        if (state === 'recording') {
            stopRecording();
            return;
        }
        if (state !== 'idle') {
            return;
        }
        if (!hasConsent()) {
            if (!await askConsent()) {
                return;
            }
            storeConsent();
        }
        if (!readyPromise) {
            // Erster Start in dieser Sitzung: Modell laden (aus dem Browser-Cache, falls schon vorhanden).
            setState('loading', 'Spracherkennung wird geladen … 0 %');
            // Die Gesamtmenge wird erst beim Laden der einzelnen Dateien bekannt, daher nie zurückspringen.
            let shown = 0;
            progressHandler = (percent) => {
                shown = Math.max(shown, percent);
                say('Spracherkennung wird geladen … ' + shown + ' %');
            };
            const laden = Messpunkt.start('speech.modell-laden');
            try {
                await ensureModel();
                laden.ende('ok');
            } catch (error) {
                laden.ende('fehler ' + error.name);
                setState('idle', 'Die Spracherkennung konnte nicht geladen werden: ' + error.message);
                return;
            }
            // Nach einem Download nicht sofort aufnehmen, der Benutzer hat womöglich weggeschaut.
            setState('idle', 'Die Spracherkennung ist bereit. Zum Sprechen noch einmal klicken.');
            return;
        }
        await startRecording();
    });
})();
