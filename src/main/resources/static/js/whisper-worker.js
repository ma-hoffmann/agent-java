// Web Worker: führt Whisper lokal im Browser aus (transformers.js, ONNX Runtime Web).
// Es wird kein Audio an einen Server geschickt. Von außen geladen werden nur die Bibliothek
// (jsDelivr) und die Modelldateien (Hugging Face); beide Quellen sind unten konfiguriert und
// lassen sich durch eigene Server ersetzen.

import {pipeline, env} from 'https://cdn.jsdelivr.net/npm/@huggingface/transformers@3.8.1';

const MODEL = 'onnx-community/whisper-base';   // Alternative: 'onnx-community/whisper-small' (ca. 250 MB)
const DTYPE = 'q8';                            // quantisiert: kleinster Download, läuft per WASM auf der CPU

// Für Modelle vom eigenen Server:
// env.allowLocalModels = true; env.allowRemoteModels = false; env.localModelPath = '/models/';
env.allowLocalModels = false;
// Mehrere Threads gibt es nur, wenn die Seite cross-origin isolated ist (siehe CrossOriginIsolationFilter).
// Ohne die Header läuft WebAssembly mit einem Thread und ist etwa dreimal langsamer.
env.backends.onnx.wasm.numThreads = self.crossOriginIsolated
    ? Math.min(8, navigator.hardwareConcurrency || 4)
    : 1;

let transcriber = null;

// Fasst die Fortschrittsmeldungen der einzelnen Dateien zu einem Gesamtwert zusammen.
const files = new Map();

function reportProgress(info) {
    if (info.status === 'progress' && info.total) {
        files.set(info.file, {loaded: info.loaded, total: info.total});
    } else if (info.status === 'done' && files.has(info.file)) {
        const entry = files.get(info.file);
        files.set(info.file, {loaded: entry.total, total: entry.total});
    } else {
        return;
    }
    let loaded = 0;
    let total = 0;
    files.forEach((entry) => {
        loaded += entry.loaded;
        total += entry.total;
    });
    self.postMessage({type: 'progress', percent: total ? Math.round(100 * loaded / total) : 0});
}

async function load() {
    transcriber = await pipeline('automatic-speech-recognition', MODEL, {
        dtype: DTYPE,
        device: 'wasm',
        progress_callback: reportProgress,
    });
    self.postMessage({type: 'ready'});
}

async function transcribe(audio) {
    const output = await transcriber(audio, {
        language: 'german',
        task: 'transcribe',
        chunk_length_s: 30,   // längere Aufnahmen werden in 30-Sekunden-Blöcken verarbeitet
    });
    self.postMessage({type: 'result', text: output.text || ''});
}

self.onmessage = async (event) => {
    try {
        if (event.data.type === 'load') {
            await load();
        } else if (event.data.type === 'transcribe') {
            await transcribe(event.data.audio);
        }
    } catch (error) {
        self.postMessage({type: 'error', message: String((error && error.message) || error)});
    }
};
