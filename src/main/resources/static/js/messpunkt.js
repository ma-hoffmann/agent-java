// Misst die Durchlaufzeit eines Abschnitts und schreibt sie in die Browser-Konsole (F12). Das Format ist
// dasselbe wie im Server-Log (Messpunkt.java): je eine Zeile bei START und bei ENDE mit Durchlaufzeit.
//
//   const mp = Messpunkt.start('speech.erkennung', 'audio-sekunden=5.9');
//   ...
//   mp.ende('ok');
//
// Jede Zeile beginnt mit "MESSPUNKT <name>". Der Name steht als Textkonstante an der Stelle im Code, die er
// misst, und lässt sich mit grep -rn "'speech.erkennung'" src/main/resources finden. Geloggt werden nie
// Texte der Benutzer (Eingaben, erkannter Text, Favoriten), nur Zeiten und Mengen.
window.Messpunkt = (() => {
    const uhrzeit = (d) => d.toTimeString().slice(0, 8) + '.' + String(d.getMilliseconds()).padStart(3, '0');

    function start(name, details) {
        const info = details ? ' ' + details : '';
        const startNanos = performance.now();
        const startZeit = new Date();
        let beendet = false;
        console.info('MESSPUNKT ' + name + ' START' + info);
        return {
            // Gibt die Durchlaufzeit in ms zurück. Ein zweiter Aufruf wird ignoriert.
            ende(ergebnis) {
                const millis = Math.round(performance.now() - startNanos);
                if (!beendet) {
                    beendet = true;
                    console.info('MESSPUNKT ' + name + ' ENDE Durchlaufzeit=' + millis + ' ms | Start=' + uhrzeit(startZeit)
                        + ' | Ende=' + uhrzeit(new Date()) + (details ? ' | ' + details : '') + ' | ergebnis=' + (ergebnis || 'ok'));
                }
                return millis;
            },
        };
    }

    return {start};
})();
