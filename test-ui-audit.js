const fs = require('fs');
const path = require('path');

function extractStrings(xmlPath) {
    const content = fs.readFileSync(xmlPath, 'utf8');
    const keys = new Set();
    const re = /<string\s+name="([^"]+)"/g;
    let m;
    while ((m = re.exec(content)) !== null) {
        keys.add(m[1]);
    }
    return keys;
}

function auditModule(modName) {
    const base = path.join(__dirname, modName, 'src', 'main');
    const enPath = path.join(base, 'res', 'values', 'strings.xml');
    const esPath = path.join(base, 'res', 'values-es', 'strings.xml');

    const enKeys = extractStrings(enPath);
    const esKeys = extractStrings(esPath);

    const missingInEs = [...enKeys].filter(k => !esKeys.has(k));
    const extraInEs = [...esKeys].filter(k => !enKeys.has(k));

    if (missingInEs.length > 0 || extraInEs.length > 0) {
        throw new Error(`[${modName}] String parity failed. Missing in ES: ${missingInEs.join(',')}; Extra in ES: ${extraInEs.join(',')}`);
    }

    // Virtual DOM XML layout audit: zero hardcoded text/hint/contentDescription & extract all IDs
    const layoutDir = path.join(base, 'res', 'layout');
    const layoutIds = new Set();
    const hardcoded = [];
    for (const file of fs.readdirSync(layoutDir)) {
        if (!file.endsWith('.xml')) continue;
        const xml = fs.readFileSync(path.join(layoutDir, file), 'utf8');
        let idMatch;
        const idRe = /android:id="@\+id\/([^"]+)"/g;
        while ((idMatch = idRe.exec(xml)) !== null) {
            layoutIds.add(idMatch[1]);
        }
        const textRe = /android:(?:text|hint|contentDescription)="([^"]+)"/g;
        let tMatch;
        while ((tMatch = textRe.exec(xml)) !== null) {
            const val = tMatch[1];
            if (!val.startsWith('@string/')) {
                hardcoded.push(`${file}: ${val}`);
            }
        }
    }

    if (hardcoded.length > 0) {
        throw new Error(`[${modName}] Hardcoded XML strings found:\n${hardcoded.join('\n')}`);
    }

    // Verify Java R.id references exist in XML layouts
    const javaRoot = path.join(base, 'java');
    const missingIds = [];
    function scanJava(dir) {
        for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
            const full = path.join(dir, entry.name);
            if (entry.isDirectory()) scanJava(full);
            else if (entry.name.endsWith('.java')) {
                const src = fs.readFileSync(full, 'utf8');
                const rIdRe = /R\.id\.([a-zA-Z0-9_]+)/g;
                let m;
                while ((m = rIdRe.exec(src)) !== null) {
                    const id = m[1];
                    // menu IDs live in menu XML
                    if (id.startsWith('nav_')) continue;
                    if (!layoutIds.has(id)) {
                        missingIds.push(`${entry.name}: R.id.${id}`);
                    }
                }
            }
        }
    }
    scanJava(javaRoot);

    if (missingIds.length > 0) {
        throw new Error(`[${modName}] Missing R.id references in XML layouts:\n${missingIds.join('\n')}`);
    }

    console.log(`[PASS] ${modName}: ${enKeys.size} EN/ES strings matched, ${layoutIds.size} layout IDs verified, 0 hardcoded strings.`);
}

auditModule('app');
auditModule('wear');
console.log('ALL VIRTUAL DOM & i18n AUDITS PASSED.');
