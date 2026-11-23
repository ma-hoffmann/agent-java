// Favoriten: häufig genutzte Eingaben werden im Backend gespeichert (/api/favorites) und lassen
// sich per Dropdown wieder in das Eingabefeld laden. Alle Texte kommen über textContent ins DOM.
(() => {
    'use strict';

    const API = '/api/favorites';

    const input = document.getElementById('input');
    const dropdown = document.getElementById('favorites');
    const summary = document.getElementById('favorites-summary');
    const list = document.getElementById('favorites-list');
    const addButton = document.getElementById('favorite-add');
    const statusLine = document.getElementById('favorites-status');
    if (!input || !dropdown || !summary || !list || !addButton || !statusLine) {
        return;
    }

    let favorites = [];

    function say(text) {
        statusLine.textContent = text;
    }

    async function request(method, url, body) {
        const response = await fetch(url, {
            method,
            headers: body ? {'Content-Type': 'application/json'} : {},
            body: body ? JSON.stringify(body) : undefined,
        });
        if (!response.ok) {
            let message = 'Fehler ' + response.status;
            try {
                const problem = await response.json();
                message = problem.detail || problem.message || message;
            } catch (e) {
                // Antwort ohne JSON, es bleibt bei der Statusmeldung.
            }
            throw new Error(message);
        }
        return response.status === 204 ? null : response.json();
    }

    function render() {
        summary.textContent = '★ Favoriten (' + favorites.length + ')';
        list.replaceChildren();

        if (!favorites.length) {
            const empty = document.createElement('li');
            empty.className = 'favorite-empty';
            empty.textContent = 'Noch keine Favoriten. Mit „☆ Merken“ speicherst du den aktuellen Text.';
            list.append(empty);
            return;
        }

        favorites.forEach((favorite) => {
            const item = document.createElement('li');
            item.className = 'favorite-item';

            const use = document.createElement('button');
            use.type = 'button';
            use.className = 'favorite-use';
            use.textContent = favorite.text;
            use.title = favorite.text;
            use.addEventListener('click', () => useFavorite(favorite));

            const remove = document.createElement('button');
            remove.type = 'button';
            remove.className = 'favorite-remove';
            remove.setAttribute('aria-label', 'Favorit löschen');
            remove.title = 'Löschen';
            remove.textContent = '×';
            remove.addEventListener('click', () => removeFavorite(favorite));

            item.append(use, remove);
            list.append(item);
        });
    }

    async function load() {
        const messpunkt = Messpunkt.start('favorites.laden');
        try {
            favorites = await request('GET', API);
            render();
            messpunkt.ende('ok anzahl=' + favorites.length);
        } catch (error) {
            messpunkt.ende('fehler ' + error.name);
            say('Die Favoriten konnten nicht geladen werden: ' + error.message);
        }
    }

    function useFavorite(favorite) {
        input.value = favorite.text;
        dropdown.open = false;
        input.focus();
        input.setSelectionRange(input.value.length, input.value.length);
        say('');
        // Die Nutzung merkt sich das Backend, damit häufig genutzte Favoriten oben stehen.
        request('POST', API + '/' + favorite.id + '/used').then(load).catch(() => {});
    }

    async function removeFavorite(favorite) {
        const messpunkt = Messpunkt.start('favorites.loeschen');
        try {
            await request('DELETE', API + '/' + favorite.id);
            messpunkt.ende('ok');
            say('Favorit gelöscht.');
            await load();
        } catch (error) {
            messpunkt.ende('fehler ' + error.name);
            say('Löschen fehlgeschlagen: ' + error.message);
        }
    }

    addButton.addEventListener('click', async () => {
        const text = input.value.trim();
        if (!text) {
            say('Es gibt keinen Text zum Merken.');
            return;
        }
        const messpunkt = Messpunkt.start('favorites.speichern');
        try {
            await request('POST', API, {text});
            messpunkt.ende('ok');
            say('Als Favorit gemerkt.');
            await load();
        } catch (error) {
            messpunkt.ende('fehler ' + error.name);
            say('Speichern fehlgeschlagen: ' + error.message);
        }
    });

    // Ein Klick außerhalb schließt das Dropdown.
    document.addEventListener('click', (event) => {
        if (dropdown.open && !dropdown.contains(event.target)) {
            dropdown.open = false;
        }
    });

    load();
})();
