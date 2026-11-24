// ==ToolboxScript==
// @id c06c0abb
// @name SSID password guesser
// @description Generates likely passwords from the Wi-Fi name. Not suitable for Chinese SSIDs; best combined with a common-password list.
// @author jsfmytg
// @version 1.0.0
// ==/ToolboxScript==

(function() {
    console.log("Processing: [" + task.ssid + "]");

    const currentYear = new Date().getFullYear();
    const years = [];
    for (let i = 0; i < 4; i++) {
        years.push(String(currentYear - i));
    }

    const baseSuffixes = [
        "123", "1234", "12345", "123456", "12345678", "123456789",
        "666", "888", "666888", "999", "000", "520", "1314",
        "6666", "8888", "9999", "0000", "1111",
        "666666", "888888", "999999", "123123", "112233",
        "66666666", "88888888", "qwer", "asdf", "abc"
    ];

    let l1Parts = task.ssid.split(/[-_.\s]+|@|wifi|net|5g|2\.4g/i).filter(p => p);
    console.log("Level 1 tokens: " + JSON.stringify(l1Parts));

    let keys = new Set();
    l1Parts.forEach(part => {
        const low = part.toLowerCase();
        keys.add(low);
        if (/^[a-zA-Z]+$/.test(part)) {
            keys.add(low.charAt(0).toUpperCase() + low.slice(1));
        }
        let l2Parts = low.match(/[a-z]+|[0-9]+/gi);
        console.log("  Level 2 tokens (" + low + "): " + JSON.stringify(l2Parts));
        if (l2Parts && l2Parts.length > 1) {
            l2Parts.forEach(sp => {
                keys.add(sp);
                if (/^[a-zA-Z]+$/.test(sp)) {
                    keys.add(sp.charAt(0).toUpperCase() + sp.slice(1));
                }
            });
        }
    });

    const p1 = new Set(); // lowercase / base suffixes
    const p2 = new Set(); // capitalized variants
    const p3 = new Set(); // year-based variants

    Array.from(keys).forEach(key => {
        const isCapBase = /^[A-Z]/.test(key);
        let baseWords = [key];

        if (key.length <= 3) {
            const repeat = isCapBase
                ? key.charAt(0).toUpperCase() + key.slice(1).toLowerCase() + key.toLowerCase()
                : key.toLowerCase() + key.toLowerCase();
            baseWords.push(repeat);
        }

        baseWords.forEach(base => {
            const isPureNum = /^\d+$/.test(base);
            const endsWithLetter = /[a-zA-Z]$/.test(base);
            const endsWithNum = /\d$/.test(base);
            const targetSet = isCapBase ? p2 : p1;

            if (base.length >= 8) targetSet.add(base);

            if (!endsWithNum) {
                const selfRepeat = base + key.toLowerCase();
                if (selfRepeat.length >= 8) targetSet.add(selfRepeat);

                baseSuffixes.forEach(sfx => {
                    targetSet.add(base + sfx);
                    if (endsWithLetter && /^[a-zA-Z]/.test(sfx)) {
                        targetSet.add(base + sfx.toUpperCase());
                    }
                });

                if (!isPureNum) {
                    years.forEach(yr => p3.add(base + yr));
                }
            }

            if (!isPureNum) {
                years.forEach(yr => {
                    p3.add(base + "@" + yr);
                    p3.add(yr + "@" + base);
                    p3.add(base + "#" + yr);
                    p3.add(yr + "#" + base);
                });
            }
        });
    });

    const comparePasswords = (a, b) => a.length - b.length || a.localeCompare(b);

    const finalP1 = Array.from(p1).filter(p => p.length >= 8).sort(comparePasswords);
    const finalP2 = Array.from(p2).filter(p => p.length >= 8).sort(comparePasswords);
    const finalP3 = Array.from(p3).filter(p => p.length >= 8).sort(comparePasswords);

    const allPasswords = [...finalP1, ...finalP2, ...finalP3];

    allPasswords.forEach(pwd => {
        task.addItem(pwd);
    });

    console.log("Done, generated " + allPasswords.length + " passwords");
})();
