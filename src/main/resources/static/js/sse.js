// Liest einen Server-Sent-Events-Stream aus einer fetch-Antwort. Nötig, weil EventSource nur GET kann,
// die Anfrage aber per POST mit JSON-Body gesendet wird.
//
//   const response = await fetch('/agent', {method: 'POST', ...});
//   await readEventStream(response, (name, data) => { ... });
//
// Der Aufruf kehrt zurück, wenn der Server den Stream beendet. Ein Event endet mit einer Leerzeile.
// Eine Zeile "feld:wert" hat höchstens ein Leerzeichen nach dem Doppelpunkt, das nicht zum Wert gehört
// (Spring schreibt "data: Wert"). Mehrere data-Zeilen eines Events werden mit Zeilenumbruch verbunden.
// Ein am Ende unvollständiges Event wird, wie bei EventSource, verworfen.
window.readEventStream = async function readEventStream(response, onEvent) {
    const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
    let buffer = '';

    const dispatch = (block) => {
        let name = 'message';
        const data = [];
        for (const line of block.split(/\r?\n/)) {
            if (!line || line.startsWith(':')) {
                continue;   // Leerzeile oder Kommentar (z. B. Keep-alive)
            }
            const colon = line.indexOf(':');
            const field = colon < 0 ? line : line.slice(0, colon);
            let value = colon < 0 ? '' : line.slice(colon + 1);
            if (value.startsWith(' ')) {
                value = value.slice(1);
            }
            if (field === 'event') {
                name = value;
            } else if (field === 'data') {
                data.push(value);
            }
        }
        if (data.length || name !== 'message') {
            onEvent(name, data.join('\n'));
        }
    };

    for (;;) {
        const {value, done} = await reader.read();
        if (done) {
            return;
        }
        buffer += value;
        let separator;
        while ((separator = /\r?\n\r?\n/.exec(buffer))) {
            const block = buffer.slice(0, separator.index);
            buffer = buffer.slice(separator.index + separator[0].length);
            dispatch(block);
        }
    }
};
